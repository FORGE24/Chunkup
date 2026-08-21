use std::collections::{HashMap, VecDeque};
use std::sync::atomic::{AtomicU32, AtomicU64, Ordering};
use std::sync::{Mutex, Condvar};

use chunkup_cwa::id::ChunkId;

static GLOBAL_EPOCH: AtomicU32 = AtomicU32::new(1);

pub fn next_epoch() -> u32 {
    GLOBAL_EPOCH.fetch_add(1, Ordering::SeqCst)
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum BackendLane {
    Gpu,
    Cpu,
    Either,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum TaskStage {
    DensityFill,
    SurfaceBuild,
    MeshBuild,
    LightCompute,
    FeatureDecorate,
    ChunkLoad,
}

impl TaskStage {
    fn default_lane(self) -> BackendLane {
        match self {
            TaskStage::DensityFill => BackendLane::Gpu,
            TaskStage::MeshBuild => BackendLane::Gpu,
            TaskStage::LightCompute => BackendLane::Gpu,
            TaskStage::SurfaceBuild => BackendLane::Cpu,
            TaskStage::FeatureDecorate => BackendLane::Cpu,
            TaskStage::ChunkLoad => BackendLane::Either,
        }
    }

    fn priority_weight(self) -> u32 {
        match self {
            TaskStage::DensityFill => 100,
            TaskStage::MeshBuild => 80,
            TaskStage::LightCompute => 60,
            TaskStage::SurfaceBuild => 40,
            TaskStage::ChunkLoad => 50,
            TaskStage::FeatureDecorate => 20,
        }
    }
}

#[derive(Clone, Debug)]
pub struct DispatchTask {
    pub chunk_id: ChunkId,
    pub stage: TaskStage,
    pub epoch: u32,
    pub preferred: BackendLane,
    pub priority_boost: u32,
    pub chunk_x: i32,
    pub chunk_z: i32,
    pub min_y: i32,
    pub height: i32,
}

impl DispatchTask {
    pub fn new(chunk_id: ChunkId, stage: TaskStage, chunk_x: i32, chunk_z: i32) -> Self {
        Self {
            chunk_id,
            stage,
            epoch: next_epoch(),
            preferred: stage.default_lane(),
            priority_boost: 0,
            chunk_x,
            chunk_z,
            min_y: -64,
            height: 384,
        }
    }

    pub fn priority_score(&self) -> u32 {
        self.stage.priority_weight() + self.priority_boost
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum DispatchStatus {
    Completed,
    Stale,
    Failed,
    Pending,
}

#[derive(Clone, Debug)]
pub struct DispatchResult {
    pub chunk_id: ChunkId,
    pub stage: TaskStage,
    pub epoch: u32,
    pub backend: BackendLane,
    pub status: DispatchStatus,
}

struct LaneQueue {
    tasks: VecDeque<DispatchTask>,
    inflight: u32,
}

impl LaneQueue {
    fn new() -> Self {
        Self { tasks: VecDeque::new(), inflight: 0 }
    }

    fn len(&self) -> usize {
        self.tasks.len()
    }

    fn is_empty(&self) -> bool {
        self.tasks.is_empty()
    }

    fn push(&mut self, task: DispatchTask) {
        let score = task.priority_score();
        let mut idx = self.tasks.len();
        for (i, t) in self.tasks.iter().enumerate() {
            if t.priority_score() < score {
                idx = i;
                break;
            }
        }
        self.tasks.insert(idx, task);
    }

    fn pop(&mut self) -> Option<DispatchTask> {
        self.tasks.pop_front()
    }

    fn peek_score(&self) -> u32 {
        self.tasks.front().map(|t| t.priority_score()).unwrap_or(0)
    }
}

pub struct DualBackendCoordinator {
    gpu_lane: Mutex<LaneQueue>,
    cpu_lane: Mutex<LaneQueue>,
    completed: Mutex<VecDeque<DispatchResult>>,
    completed_cv: Condvar,
    epoch_table: Mutex<HashMap<(ChunkId, TaskStage), u32>>,
    gpu_capacity: AtomicU32,
    cpu_capacity: AtomicU32,
    gpu_threshold: usize,
    cpu_threshold: usize,
    stats_gpu_dispatched: AtomicU64,
    stats_cpu_dispatched: AtomicU64,
    stats_stale_discarded: AtomicU64,
    stats_cpu_stole_gpu: AtomicU64,
    stats_gpu_stole_cpu: AtomicU64,
}

impl DualBackendCoordinator {
    pub fn new(gpu_capacity: u32, cpu_capacity: u32) -> Self {
        Self {
            gpu_lane: Mutex::new(LaneQueue::new()),
            cpu_lane: Mutex::new(LaneQueue::new()),
            completed: Mutex::new(VecDeque::new()),
            completed_cv: Condvar::new(),
            epoch_table: Mutex::new(HashMap::new()),
            gpu_capacity: AtomicU32::new(gpu_capacity),
            cpu_capacity: AtomicU32::new(cpu_capacity),
            gpu_threshold: (gpu_capacity as f32 * 0.75) as usize,
            cpu_threshold: (cpu_capacity as f32 * 0.75) as usize,
            stats_gpu_dispatched: AtomicU64::new(0),
            stats_cpu_dispatched: AtomicU64::new(0),
            stats_stale_discarded: AtomicU64::new(0),
            stats_cpu_stole_gpu: AtomicU64::new(0),
            stats_gpu_stole_cpu: AtomicU64::new(0),
        }
    }

    pub fn submit(&self, mut task: DispatchTask) {
        {
            let mut table = self.epoch_table.lock().unwrap();
            let key = (task.chunk_id, task.stage);
            task.epoch = next_epoch();
            table.insert(key, task.epoch);
        }

        if task.preferred == BackendLane::Gpu || task.preferred == BackendLane::Either {
            let gpu = self.gpu_lane.lock().unwrap();
            if gpu.inflight + (gpu.len() as u32) < self.gpu_capacity.load(Ordering::Relaxed) {
                drop(gpu);
                let mut gpu = self.gpu_lane.lock().unwrap();
                gpu.push(task);
                return;
            }
            if task.preferred == BackendLane::Gpu {
                drop(gpu);
                let mut cpu = self.cpu_lane.lock().unwrap();
                cpu.push(task);
                self.stats_cpu_stole_gpu.fetch_add(1, Ordering::Relaxed);
                return;
            }
        }

        let mut cpu = self.cpu_lane.lock().unwrap();
        cpu.push(task);
    }

    pub fn dispatch_gpu(&self, max_batch: usize) -> Vec<DispatchTask> {
        let mut gpu = self.gpu_lane.lock().unwrap();
        let mut result = Vec::new();
        let avail = self.gpu_capacity.load(Ordering::Relaxed).saturating_sub(gpu.inflight) as usize;
        let take = max_batch.min(avail).min(gpu.len());

        for _ in 0..take {
            if let Some(task) = gpu.pop() {
                gpu.inflight += 1;
                self.stats_gpu_dispatched.fetch_add(1, Ordering::Relaxed);
                result.push(task);
            }
        }

        if result.is_empty() && gpu.len() < self.gpu_threshold {
            drop(gpu);
            let mut cpu = self.cpu_lane.lock().unwrap();
            let steal_count = cpu.len().min(max_batch.min(4));
            for _ in 0..steal_count {
                if let Some(task) = cpu.pop() {
                    self.stats_gpu_stole_cpu.fetch_add(1, Ordering::Relaxed);
                    self.stats_gpu_dispatched.fetch_add(1, Ordering::Relaxed);
                    result.push(task);
                }
            }
        }

        result
    }

    pub fn dispatch_cpu(&self, max_batch: usize) -> Vec<DispatchTask> {
        let mut cpu = self.cpu_lane.lock().unwrap();
        let mut result = Vec::new();
        let avail = self.cpu_capacity.load(Ordering::Relaxed).saturating_sub(cpu.inflight) as usize;
        let take = max_batch.min(avail).min(cpu.len());

        for _ in 0..take {
            if let Some(task) = cpu.pop() {
                cpu.inflight += 1;
                self.stats_cpu_dispatched.fetch_add(1, Ordering::Relaxed);
                result.push(task);
            }
        }

        if result.is_empty() && cpu.len() < self.cpu_threshold {
            drop(cpu);
            let mut gpu = self.gpu_lane.lock().unwrap();
            let steal_count = gpu.len().min(max_batch.min(4));
            for _ in 0..steal_count {
                if let Some(task) = gpu.pop() {
                    self.stats_cpu_stole_gpu.fetch_add(1, Ordering::Relaxed);
                    self.stats_cpu_dispatched.fetch_add(1, Ordering::Relaxed);
                    result.push(task);
                }
            }
        }

        result
    }

    pub fn complete(&self, result: DispatchResult) {
        let is_stale = {
            let table = self.epoch_table.lock().unwrap();
            let key = (result.chunk_id, result.stage);
            match table.get(&key) {
                Some(&current_epoch) => current_epoch != result.epoch,
                None => true,
            }
        };

        if is_stale {
            self.stats_stale_discarded.fetch_add(1, Ordering::Relaxed);
            self._release_inflight(&result);
            return;
        }

        {
            let table = self.epoch_table.lock().unwrap();
            let key = (result.chunk_id, result.stage);
            let mut table = table;
            table.remove(&key);
        }

        {
            let mut completed = self.completed.lock().unwrap();
            completed.push_back(result.clone());
            self.completed_cv.notify_one();
        }

        self._release_inflight(&result);
    }

    fn _release_inflight(&self, result: &DispatchResult) {
        match result.backend {
            BackendLane::Gpu => {
                let mut gpu = self.gpu_lane.lock().unwrap();
                if gpu.inflight > 0 { gpu.inflight -= 1; }
            }
            BackendLane::Cpu => {
                let mut cpu = self.cpu_lane.lock().unwrap();
                if cpu.inflight > 0 { cpu.inflight -= 1; }
            }
            BackendLane::Either => {
                let mut cpu = self.cpu_lane.lock().unwrap();
                if cpu.inflight > 0 { cpu.inflight -= 1; }
            }
        }
    }

    pub fn collect(&self, max_count: usize) -> Vec<DispatchResult> {
        let mut completed = self.completed.lock().unwrap();
        let take = max_count.min(completed.len());
        let mut result = Vec::with_capacity(take);
        for _ in 0..take {
            if let Some(r) = completed.pop_front() {
                result.push(r);
            }
        }
        result
    }

    pub fn collect_blocking(&self, max_count: usize, timeout_ms: u64) -> Vec<DispatchResult> {
        let mut completed = self.completed.lock().unwrap();
        if completed.is_empty() {
            let timeout = std::time::Duration::from_millis(timeout_ms);
            let (lock, wait_result) = self.completed_cv.wait_timeout(completed, timeout).unwrap();
            completed = lock;
            if wait_result.timed_out() && completed.is_empty() {
                return Vec::new();
            }
        }
        let take = max_count.min(completed.len());
        let mut result = Vec::with_capacity(take);
        for _ in 0..take {
            if let Some(r) = completed.pop_front() {
                result.push(r);
            }
        }
        result
    }

    pub fn invalidate(&self, chunk_id: ChunkId, stage: TaskStage) {
        let mut table = self.epoch_table.lock().unwrap();
        let key = (chunk_id, stage);
        table.remove(&key);
    }

    pub fn pending_gpu(&self) -> usize {
        self.gpu_lane.lock().unwrap().len()
    }

    pub fn pending_cpu(&self) -> usize {
        self.cpu_lane.lock().unwrap().len()
    }

    pub fn inflight_gpu(&self) -> u32 {
        self.gpu_lane.lock().unwrap().inflight
    }

    pub fn inflight_cpu(&self) -> u32 {
        self.cpu_lane.lock().unwrap().inflight
    }

    pub fn completed_pending(&self) -> usize {
        self.completed.lock().unwrap().len()
    }

    pub fn set_gpu_capacity(&self, cap: u32) {
        self.gpu_capacity.store(cap, Ordering::Relaxed);
    }

    pub fn set_cpu_capacity(&self, cap: u32) {
        self.cpu_capacity.store(cap, Ordering::Relaxed);
    }

    pub fn stats(&self) -> CoordinatorStats {
        CoordinatorStats {
            gpu_dispatched: self.stats_gpu_dispatched.load(Ordering::Relaxed),
            cpu_dispatched: self.stats_cpu_dispatched.load(Ordering::Relaxed),
            stale_discarded: self.stats_stale_discarded.load(Ordering::Relaxed),
            cpu_stole_gpu: self.stats_cpu_stole_gpu.load(Ordering::Relaxed),
            gpu_stole_cpu: self.stats_gpu_stole_cpu.load(Ordering::Relaxed),
            pending_gpu: self.pending_gpu(),
            pending_cpu: self.pending_cpu(),
            inflight_gpu: self.inflight_gpu(),
            inflight_cpu: self.inflight_cpu(),
            completed_ready: self.completed_pending(),
        }
    }

    pub fn drain_all(&self) -> Vec<DispatchTask> {
        let mut gpu = self.gpu_lane.lock().unwrap();
        let mut cpu = self.cpu_lane.lock().unwrap();
        let mut result = Vec::new();
        while let Some(t) = gpu.pop() { result.push(t); }
        while let Some(t) = cpu.pop() { result.push(t); }
        let mut table = self.epoch_table.lock().unwrap();
        table.clear();
        result
    }
}

#[derive(Clone, Debug)]
pub struct CoordinatorStats {
    pub gpu_dispatched: u64,
    pub cpu_dispatched: u64,
    pub stale_discarded: u64,
    pub cpu_stole_gpu: u64,
    pub gpu_stole_cpu: u64,
    pub pending_gpu: usize,
    pub pending_cpu: usize,
    pub inflight_gpu: u32,
    pub inflight_cpu: u32,
    pub completed_ready: usize,
}

#[cfg(test)]
mod tests {
    use super::*;
    use chunkup_cwa::id::ChunkId;

    #[test]
    fn epoch_monotonic() {
        let a = next_epoch();
        let b = next_epoch();
        assert!(b > a, "epoch must be monotonic");
    }

    #[test]
    fn submit_and_dispatch_gpu() {
        let coord = DualBackendCoordinator::new(16, 4);
        let cid = ChunkId(0);
        let task = DispatchTask::new(cid, TaskStage::DensityFill, 0, 0);
        coord.submit(task);
        let batch = coord.dispatch_gpu(8);
        assert_eq!(batch.len(), 1);
        assert_eq!(batch[0].stage, TaskStage::DensityFill);
    }

    #[test]
    fn gpu_overflow_spills_to_cpu() {
        let coord = DualBackendCoordinator::new(2, 4);
        for i in 0..6 {
            let cid = ChunkId(i);
            let task = DispatchTask::new(cid, TaskStage::DensityFill, i as i32, 0);
            coord.submit(task);
        }
        let gpu_batch = coord.dispatch_gpu(4);
        assert!(gpu_batch.len() <= 2);
        assert!(coord.pending_cpu() > 0, "overflow must spill to CPU lane");
    }

    #[test]
    fn stale_result_discarded() {
        let coord = DualBackendCoordinator::new(8, 4);
        let cid = ChunkId(42);
        let task = DispatchTask::new(cid, TaskStage::DensityFill, 0, 0);
        let epoch = task.epoch;
        coord.submit(task);

        let batch = coord.dispatch_gpu(1);
        assert_eq!(batch.len(), 1);

        let task2 = DispatchTask::new(cid, TaskStage::DensityFill, 0, 0);
        coord.submit(task2);

        let stale_result = DispatchResult {
            chunk_id: cid,
            stage: TaskStage::DensityFill,
            epoch,
            backend: BackendLane::Gpu,
            status: DispatchStatus::Completed,
        };
        coord.complete(stale_result);

        let stats = coord.stats();
        assert!(stats.stale_discarded >= 1, "stale result must be discarded");
    }

    #[test]
    fn collect_returns_completed() {
        let coord = DualBackendCoordinator::new(8, 4);
        let cid = ChunkId(1);
        let task = DispatchTask::new(cid, TaskStage::SurfaceBuild, 0, 0);
        coord.submit(task);

        let batch = coord.dispatch_cpu(1);
        assert_eq!(batch.len(), 1);

        coord.complete(DispatchResult {
            chunk_id: cid,
            stage: TaskStage::SurfaceBuild,
            epoch: batch[0].epoch,
            backend: BackendLane::Cpu,
            status: DispatchStatus::Completed,
        });

        let results = coord.collect(8);
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].status, DispatchStatus::Completed);
    }

    #[test]
    fn priority_ordering() {
        let coord = DualBackendCoordinator::new(16, 8);
        coord.submit(DispatchTask::new(ChunkId(1), TaskStage::FeatureDecorate, 0, 0));
        coord.submit(DispatchTask::new(ChunkId(2), TaskStage::DensityFill, 0, 0));
        coord.submit(DispatchTask::new(ChunkId(3), TaskStage::SurfaceBuild, 0, 0));

        let batch = coord.dispatch_gpu(8);
        assert!(!batch.is_empty());
        assert_eq!(batch[0].stage, TaskStage::DensityFill, "highest priority dispatched first");
    }

    #[test]
    fn invalidate_clears_epoch() {
        let coord = DualBackendCoordinator::new(8, 4);
        let cid = ChunkId(99);
        let task = DispatchTask::new(cid, TaskStage::MeshBuild, 0, 0);
        coord.submit(task);

        coord.invalidate(cid, TaskStage::MeshBuild);

        let batch = coord.dispatch_gpu(1);
        let result = DispatchResult {
            chunk_id: cid,
            stage: TaskStage::MeshBuild,
            epoch: batch[0].epoch,
            backend: BackendLane::Gpu,
            status: DispatchStatus::Completed,
        };
        coord.complete(result);

        let stats = coord.stats();
        assert!(stats.stale_discarded >= 1, "invalidated epoch must cause stale discard");
    }

    #[test]
    fn drain_all_clears_queues() {
        let coord = DualBackendCoordinator::new(8, 4);
        coord.submit(DispatchTask::new(ChunkId(1), TaskStage::DensityFill, 0, 0));
        coord.submit(DispatchTask::new(ChunkId(2), TaskStage::SurfaceBuild, 0, 0));
        coord.submit(DispatchTask::new(ChunkId(3), TaskStage::MeshBuild, 0, 0));

        let drained = coord.drain_all();
        assert_eq!(drained.len(), 3);
        assert_eq!(coord.pending_gpu(), 0);
        assert_eq!(coord.pending_cpu(), 0);
    }

    #[test]
    fn concurrent_gpu_cpu_full_lifecycle() {
        let coord = DualBackendCoordinator::new(4, 4);
        let chunks = [
            ChunkId(10), ChunkId(11), ChunkId(12), ChunkId(13),
            ChunkId(20), ChunkId(21), ChunkId(22), ChunkId(23),
        ];
        let stages = [
            TaskStage::DensityFill,
            TaskStage::SurfaceBuild,
            TaskStage::MeshBuild,
            TaskStage::FeatureDecorate,
            TaskStage::ChunkLoad,
            TaskStage::LightCompute,
        ];

        for &cid in &chunks {
            for &stage in &stages {
                coord.submit(DispatchTask::new(cid, stage, cid.x(), cid.z()));
            }
        }

        let total_submitted = chunks.len() * stages.len();
        assert_eq!(total_submitted, 48);

        let s = coord.stats();
        assert!(s.pending_gpu + s.pending_cpu + s.inflight_gpu as usize + s.inflight_cpu as usize <= total_submitted);

        let mut total_completed = 0;
        let mut gpu_tasks = 0;
        let mut cpu_tasks = 0;

        for round in 0..20 {
            let gpu_batch = coord.dispatch_gpu(4);
            let cpu_batch = coord.dispatch_cpu(4);

            for task in gpu_batch {
                coord.complete(DispatchResult {
                    chunk_id: task.chunk_id,
                    stage: task.stage,
                    epoch: task.epoch,
                    backend: BackendLane::Gpu,
                    status: DispatchStatus::Completed,
                });
                gpu_tasks += 1;
            }
            for task in cpu_batch {
                coord.complete(DispatchResult {
                    chunk_id: task.chunk_id,
                    stage: task.stage,
                    epoch: task.epoch,
                    backend: BackendLane::Cpu,
                    status: DispatchStatus::Completed,
                });
                cpu_tasks += 1;
            }

            let results = coord.collect(16);
            total_completed += results.len();

            let s = coord.stats();
            if s.pending_gpu == 0 && s.pending_cpu == 0 && s.inflight_gpu == 0 && s.inflight_cpu == 0 {
                break;
            }
            if round == 19 {
                panic!("tasks did not drain after 20 rounds: pending_gpu={} pending_cpu={} inflight_gpu={} inflight_cpu={}",
                    s.pending_gpu, s.pending_cpu, s.inflight_gpu, s.inflight_cpu);
            }
        }

        let s = coord.stats();
        assert_eq!(s.pending_gpu, 0);
        assert_eq!(s.pending_cpu, 0);
        assert_eq!(s.inflight_gpu, 0);
        assert_eq!(s.inflight_cpu, 0);
        assert_eq!(total_completed, 48);
        assert!(gpu_tasks > 0);
        assert!(cpu_tasks > 0);
        assert_eq!(gpu_tasks + cpu_tasks, 48);
    }

    #[test]
    fn collect_blocking_with_timeout() {
        let coord = DualBackendCoordinator::new(4, 2);
        let cid = ChunkId(100);
        let task = DispatchTask::new(cid, TaskStage::DensityFill, 0, 0);
        coord.submit(task);

        let batch = coord.dispatch_gpu(1);
        assert_eq!(batch.len(), 1);
        let epoch = batch[0].epoch;

        let results = coord.collect_blocking(1, 100);
        assert!(results.is_empty(), "no results before complete");

        coord.complete(DispatchResult {
            chunk_id: cid,
            stage: TaskStage::DensityFill,
            epoch,
            backend: BackendLane::Gpu,
            status: DispatchStatus::Completed,
        });

        let results = coord.collect(1);
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].status, DispatchStatus::Completed);
    }

    #[test]
    fn lane_stealing_gpu_takes_cpu_tasks() {
        let coord = DualBackendCoordinator::new(4, 2);
        for i in 0..10 {
            let cid = ChunkId(i);
            coord.submit(DispatchTask::new(cid, TaskStage::SurfaceBuild, i as i32, 0));
        }

        assert_eq!(coord.pending_cpu(), 10);
        assert_eq!(coord.pending_gpu(), 0);

        let gpu_batch = coord.dispatch_gpu(4);
        assert!(gpu_batch.len() > 0, "GPU should steal CPU tasks when idle");
        let s = coord.stats();
        assert!(s.gpu_stole_cpu > 0, "gpu_stole_cpu should be > 0");
    }

    #[test]
    fn lane_stealing_cpu_takes_gpu_tasks() {
        let coord = DualBackendCoordinator::new(6, 2);
        for i in 0..6 {
            coord.submit(DispatchTask::new(ChunkId(i), TaskStage::ChunkLoad, i as i32, 0));
        }
        assert_eq!(coord.pending_gpu(), 6);
        assert_eq!(coord.pending_cpu(), 0);

        let cpu_batch = coord.dispatch_cpu(4);
        assert!(cpu_batch.len() > 0, "CPU should steal GPU tasks when idle");
        let s = coord.stats();
        assert!(s.cpu_stole_gpu > 0, "cpu_stole_gpu should be > 0");
    }

    #[test]
    fn multiple_stale_discards() {
        let coord = DualBackendCoordinator::new(8, 4);
        let cid = ChunkId(50);

        for i in 0..10 {
            let mut task = DispatchTask::new(cid, TaskStage::DensityFill, 0, 0);
            task.priority_boost = i;
            coord.submit(task);
        }

        for _ in 0..10 {
            let batch = coord.dispatch_gpu(1);
            for t in batch {
                coord.complete(DispatchResult {
                    chunk_id: t.chunk_id,
                    stage: t.stage,
                    epoch: t.epoch,
                    backend: BackendLane::Gpu,
                    status: DispatchStatus::Completed,
                });
            }
        }

        let s = coord.stats();
        assert!(s.stale_discarded >= 9, "expected >=9 stale discards for 10 submits, got {}", s.stale_discarded);
        assert!(coord.completed_pending() <= 1, "at most 1 should succeed, got {}", coord.completed_pending());
    }

    #[test]
    fn invalidate_all_clears_epoch_table() {
        let coord = DualBackendCoordinator::new(8, 4);
        let mut epochs = Vec::new();
        for i in 0..5 {
            let cid = ChunkId(i);
            let task = DispatchTask::new(cid, TaskStage::DensityFill, i as i32, 0);
            coord.submit(task);
            let task = DispatchTask::new(cid, TaskStage::SurfaceBuild, i as i32, 0);
            coord.submit(task);
        }

        for i in 0..5 {
            coord.invalidate(ChunkId(i), TaskStage::DensityFill);
            coord.invalidate(ChunkId(i), TaskStage::SurfaceBuild);
        }

        let batch_gpu = coord.dispatch_gpu(8);
        for t in batch_gpu {
            epochs.push(t.epoch);
            coord.complete(DispatchResult {
                chunk_id: t.chunk_id,
                stage: t.stage,
                epoch: t.epoch,
                backend: BackendLane::Gpu,
                status: DispatchStatus::Completed,
            });
        }
        let batch_cpu = coord.dispatch_cpu(8);
        for t in batch_cpu {
            epochs.push(t.epoch);
            coord.complete(DispatchResult {
                chunk_id: t.chunk_id,
                stage: t.stage,
                epoch: t.epoch,
                backend: BackendLane::Cpu,
                status: DispatchStatus::Completed,
            });
        }

        let s = coord.stats();
        assert_eq!(s.stale_discarded, epochs.len() as u64, "all invalidated tasks should be stale");
        assert_eq!(coord.completed_pending(), 0, "no completed should survive invalidate");
    }

    #[test]
    fn capacity_resize_during_operation() {
        let coord = DualBackendCoordinator::new(4, 2);
        for i in 0..20 {
            coord.submit(DispatchTask::new(ChunkId(i), TaskStage::DensityFill, i as i32, 0));
        }

        coord.set_gpu_capacity(16);
        coord.set_cpu_capacity(8);

        let gpu_batch = coord.dispatch_gpu(20);
        assert!(gpu_batch.len() <= 16, "should respect new gpu capacity");

        for t in gpu_batch {
            coord.complete(DispatchResult {
                chunk_id: t.chunk_id,
                stage: t.stage,
                epoch: t.epoch,
                backend: BackendLane::Gpu,
                status: DispatchStatus::Completed,
            });
        }
    }
}
