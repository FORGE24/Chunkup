//! 驻留调度器:Disk / CPU RAM / VRAM 三层缓存 + LRU+Pin 驱逐(设计 §9)。
//!
//! ## 职责
//!
//! [ResidencyScheduler] 不直接持有 chunk 数据,而是观察 [ChunkRuntime](crate::runtime::ChunkRuntime)
//! 的当前驻留量与各 slot 的 `(distance, priority_hint, last_access_tick, PINNED)` 四元组,
//! 计算驱逐评分并返回有序候选列表。调用方(主 tick)按候选顺序对
//! [ChunkRuntime::begin_evict](crate::runtime::ChunkRuntime::begin_evict) /
//! [ChunkRuntime::finish_evict](crate::runtime::ChunkRuntime::finish_evict) 逐一回收。
//!
//! ## 评分公式
//!
//! `score = distance * W_DIST + age * W_AGE - priority * W_PRIO`
//!
//! - `distance`:玩家 chunk 到目标 chunk 的 Manhattan 距离(chunk 单位)
//! - `age = current_tick - last_access_tick`:越久未访问分越高(LRU)
//! - `priority`:来自 [ChunkSlot::priority_hint],越高越应保留(从分中扣减)
//! - `PINNED` 的 chunk 直接跳过,不进候选池
//!
//! 分越高 → 越优先驱逐。

use chunkup_cwa::id::ChunkId;
use chunkup_cwa::state::Lifecycle;

use crate::runtime::ChunkRuntime;
use crate::slot::ChunkSlot;

/// 距离权重(每 chunk 距离 1000 分)。
const W_DIST: u64 = 1000;
/// 年龄权重(每 tick 1 分)。
const W_AGE: u64 = 1;
/// 优先级权重(每点优先级 100 分,从总分扣减)。
const W_PRIO: u64 = 100;

/// 可驱逐的 lifecycle 集合。
fn is_evictable(lc: Lifecycle) -> bool {
    matches!(
        lc,
        Lifecycle::CpuResident | Lifecycle::GpuResident | Lifecycle::GpuActive | Lifecycle::Archived
    )
}

/// 驱逐评分上下文(每 tick 由调用方填充)。
#[derive(Clone, Debug)]
pub struct EvictionContext {
    /// 玩家所在 chunk。
    pub player_chunk: ChunkId,
    /// 当前全局 tick(用于计算 age = current_tick - last_access_tick)。
    pub current_tick: u32,
}

/// 单个驱逐候选。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EvictionCandidate {
    /// 目标 chunk。
    pub chunk_id: ChunkId,
    /// 驱逐评分(越高越优先驱逐)。
    pub score: u64,
    /// 驱逐后可释放的 CPU 字节。
    pub freed_cpu_bytes: u32,
    /// 驱逐后可释放的 GPU 字节。
    pub freed_gpu_bytes: u32,
    /// 当前 lifecycle。
    pub lifecycle: Lifecycle,
}

/// 驻留调度器。
///
/// 持有 CPU RAM / GPU VRAM 的预算上限,观察 [ChunkRuntime] 实际驻留量并产出驱逐计划。
pub struct ResidencyScheduler {
    /// CPU RAM 驻留预算(字节)。
    cpu_budget: u64,
    /// GPU VRAM 驻留预算(字节)。
    gpu_budget: u64,
}

impl ResidencyScheduler {
    /// 构造调度器,指定 CPU/GPU 预算。
    pub fn new(cpu_budget: u64, gpu_budget: u64) -> Self {
        ResidencyScheduler {
            cpu_budget,
            gpu_budget,
        }
    }

    /// CPU RAM 预算。
    pub fn cpu_budget(&self) -> u64 {
        self.cpu_budget
    }

    /// GPU VRAM 预算。
    pub fn gpu_budget(&self) -> u64 {
        self.gpu_budget
    }

    /// 调整预算(运行时可变,例如显存动态分配)。
    pub fn set_budgets(&mut self, cpu_budget: u64, gpu_budget: u64) {
        self.cpu_budget = cpu_budget;
        self.gpu_budget = gpu_budget;
    }

    /// CPU 超额字节数(0 表示未超)。
    pub fn cpu_overrun(&self, rt: &ChunkRuntime) -> u64 {
        rt.cpu_resident_bytes().saturating_sub(self.cpu_budget)
    }

    /// GPU 超额字节数(0 表示未超)。
    pub fn gpu_overrun(&self, rt: &ChunkRuntime) -> u64 {
        rt.gpu_resident_bytes().saturating_sub(self.gpu_budget)
    }

    /// 收集所有可驱逐候选并按评分降序返回。
    ///
    /// PINNED chunk 与非可驱逐 lifecycle(Absent / CpuLoading / GpuStaging /
    /// GpuDirty / CpuSync / Evicting)的 chunk 不进候选池。
    pub fn collect_candidates(&self, rt: &ChunkRuntime, ctx: &EvictionContext) -> Vec<EvictionCandidate> {
        let mut cands: Vec<EvictionCandidate> = rt
            .iter()
            .filter(|(_, slot)| !slot.is_pinned() && is_evictable(slot.lifecycle()))
            .map(|(id, slot)| {
                let dist = chunk_distance(ctx.player_chunk, id);
                let score = eviction_score(slot, ctx, dist);
                EvictionCandidate {
                    chunk_id: id,
                    score,
                    freed_cpu_bytes: slot.cpu_payload_size(),
                    freed_gpu_bytes: slot.gpu_buffer_size(),
                    lifecycle: slot.lifecycle(),
                }
            })
            .collect();
        // 评分降序(高分优先驱逐)
        cands.sort_by(|a, b| b.score.cmp(&a.score));
        cands
    }

    /// 贪心选择驱逐候选,直到 CPU/GPU 驻留量都降到预算以内。
    ///
    /// 返回的列表已按评分降序排列,调用方按顺序逐个 evict 即可。
    /// 若候选耗尽仍不够,返回当前能选出的全部(调用方可考虑提升预算或 pin 检查)。
    pub fn select_for_budget(
        &self,
        rt: &ChunkRuntime,
        ctx: &EvictionContext,
    ) -> Vec<EvictionCandidate> {
        let mut cpu_need = self.cpu_overrun(rt);
        let mut gpu_need = self.gpu_overrun(rt);

        if cpu_need == 0 && gpu_need == 0 {
            return Vec::new();
        }

        let mut cands = self.collect_candidates(rt, ctx);
        let mut picked = Vec::new();
        for c in cands.drain(..) {
            if cpu_need == 0 && gpu_need == 0 {
                break;
            }
            cpu_need = cpu_need.saturating_sub(c.freed_cpu_bytes as u64);
            gpu_need = gpu_need.saturating_sub(c.freed_gpu_bytes as u64);
            picked.push(c);
        }
        picked
    }
}

/// 计算 chunk 间 Manhattan 距离(chunk 单位)。
fn chunk_distance(a: ChunkId, b: ChunkId) -> u32 {
    let dx = (a.x() as i64 - b.x() as i64).unsigned_abs() as u32;
    let dz = (a.z() as i64 - b.z() as i64).unsigned_abs() as u32;
    // 不同维度视为极远(强制驱逐)
    let dim_penalty = if a.dim() != b.dim() { u32::MAX / 4 } else { 0 };
    dx.saturating_add(dz).saturating_add(dim_penalty)
}

/// 计算驱逐评分:越高越优先驱逐。
///
/// `score = distance * W_DIST + age * W_AGE` (基础分,正向)
/// 再减去 `priority * W_PRIO`(优先级越高越应保留)。
/// 用 saturating_sub 避免下溢:高优先级 chunk 评分可降到 0(最后才轮到驱逐)。
fn eviction_score(slot: &ChunkSlot, ctx: &EvictionContext, distance: u32) -> u64 {
    let age = ctx.current_tick.saturating_sub(slot.state.last_access_tick) as u64;
    let prio = slot.priority_hint as u64;
    let base = distance as u64 * W_DIST + age * W_AGE;
    base.saturating_sub(prio * W_PRIO)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::gpu_handle::GpuBufferHandle;

    fn rt_with_slots() -> ChunkRuntime {
        ChunkRuntime::new()
    }

    fn cid(x: i32, z: i32) -> ChunkId {
        ChunkId::new(0, x, z)
    }

    fn make_resident_cpu(rt: &mut ChunkRuntime, id: ChunkId, size: usize, prio: u16, tick: u32) {
        rt.register_archived(id);
        rt.begin_cpu_load(id).unwrap();
        rt.finish_cpu_load(id, vec![0u8; size].into_boxed_slice()).unwrap();
        if let Some(slot) = rt.get_mut(id) {
            slot.priority_hint = prio;
            slot.state.last_access_tick = tick;
        }
    }

    fn make_resident_gpu(rt: &mut ChunkRuntime, id: ChunkId, cpu_size: usize, gpu_size: u32, tick: u32) {
        make_resident_cpu(rt, id, cpu_size, 0, tick);
        rt.begin_gpu_stage(id).unwrap();
        rt.finish_gpu_stage(id, GpuBufferHandle::new(id.0, gpu_size)).unwrap();
        rt.activate(id).unwrap();
        if let Some(slot) = rt.get_mut(id) {
            slot.state.last_access_tick = tick;
        }
    }

    #[test]
    fn chunk_distance_manhattan() {
        let a = cid(0, 0);
        let b = cid(3, 4);
        assert_eq!(chunk_distance(a, b), 7);
        assert_eq!(chunk_distance(b, a), 7);
        assert_eq!(chunk_distance(a, a), 0);
    }

    #[test]
    fn chunk_distance_cross_dim_is_huge() {
        let a = ChunkId::new(0, 0, 0);
        let b = ChunkId::new(1, 0, 0);
        assert!(chunk_distance(a, b) > 100_000);
    }

    #[test]
    fn no_overrun_returns_empty() {
        let mut rt = rt_with_slots();
        make_resident_cpu(&mut rt, cid(0, 0), 100, 0, 0);
        let sched = ResidencyScheduler::new(1024, 1024);
        let ctx = EvictionContext { player_chunk: cid(0, 0), current_tick: 10 };
        let picked = sched.select_for_budget(&rt, &ctx);
        assert!(picked.is_empty());
    }

    #[test]
    fn picks_farthest_first() {
        let mut rt = rt_with_slots();
        // 近 chunk(距离 0)与远 chunk(距离 5),相同 age/prio
        make_resident_cpu(&mut rt, cid(0, 0), 100, 0, 5);
        make_resident_cpu(&mut rt, cid(5, 0), 100, 0, 5);
        // 预算 150 → 超额 50,需要驱逐 1 个
        let sched = ResidencyScheduler::new(150, 0);
        let ctx = EvictionContext { player_chunk: cid(0, 0), current_tick: 10 };
        let picked = sched.select_for_budget(&rt, &ctx);
        assert_eq!(picked.len(), 1);
        // 远的优先
        assert_eq!(picked[0].chunk_id, cid(5, 0));
    }

    #[test]
    fn picks_oldest_first_lru() {
        let mut rt = rt_with_slots();
        // 两个等距 chunk,一个旧(tick=1)一个新(tick=9)
        make_resident_cpu(&mut rt, cid(1, 0), 100, 0, 1);
        make_resident_cpu(&mut rt, cid(0, 1), 100, 0, 9);
        let sched = ResidencyScheduler::new(150, 0);
        let ctx = EvictionContext { player_chunk: cid(0, 0), current_tick: 10 };
        let picked = sched.select_for_budget(&rt, &ctx);
        assert_eq!(picked.len(), 1);
        // 旧的优先(age=9 > age=1)
        assert_eq!(picked[0].chunk_id, cid(1, 0));
    }

    #[test]
    fn priority_protects_chunk() {
        let mut rt = rt_with_slots();
        // 两个等距等龄 chunk,prio 不同
        make_resident_cpu(&mut rt, cid(1, 0), 100, 50, 5);
        make_resident_cpu(&mut rt, cid(0, 1), 100, 0, 5);
        let sched = ResidencyScheduler::new(150, 0);
        let ctx = EvictionContext { player_chunk: cid(0, 0), current_tick: 10 };
        let picked = sched.select_for_budget(&rt, &ctx);
        assert_eq!(picked.len(), 1);
        // 低 prio 的优先驱逐
        assert_eq!(picked[0].chunk_id, cid(0, 1));
    }

    #[test]
    fn pinned_skipped() {
        let mut rt = rt_with_slots();
        make_resident_cpu(&mut rt, cid(0, 0), 100, 0, 0);
        rt.pin(cid(0, 0));
        let sched = ResidencyScheduler::new(50, 0);
        let ctx = EvictionContext { player_chunk: cid(0, 0), current_tick: 10 };
        let cands = sched.collect_candidates(&rt, &ctx);
        assert!(cands.is_empty(), "pinned chunk 不应进候选池");
    }

    #[test]
    fn gpu_and_cpu_freed_reported() {
        let mut rt = rt_with_slots();
        make_resident_gpu(&mut rt, cid(0, 0), 200, 300, 0);
        let sched = ResidencyScheduler::new(50, 50);
        let ctx = EvictionContext { player_chunk: cid(0, 0), current_tick: 10 };
        let picked = sched.select_for_budget(&rt, &ctx);
        assert_eq!(picked.len(), 1);
        // GpuActive:cpu_payload 仍持有(200)+ gpu_handle(300)
        assert_eq!(picked[0].freed_cpu_bytes, 200);
        assert_eq!(picked[0].freed_gpu_bytes, 300);
    }

    #[test]
    fn overrun_calculation() {
        let mut rt = rt_with_slots();
        make_resident_cpu(&mut rt, cid(0, 0), 500, 0, 0);
        let sched = ResidencyScheduler::new(300, 1024);
        assert_eq!(sched.cpu_overrun(&rt), 200);
        assert_eq!(sched.gpu_overrun(&rt), 0);
    }

    #[test]
    fn multiple_picks_to_meet_budget() {
        let mut rt = rt_with_slots();
        // 3 个 chunk 各 100 字节,预算 150 → 需驱逐 2 个
        make_resident_cpu(&mut rt, cid(3, 0), 100, 0, 0);
        make_resident_cpu(&mut rt, cid(2, 0), 100, 0, 0);
        make_resident_cpu(&mut rt, cid(1, 0), 100, 0, 0);
        let sched = ResidencyScheduler::new(150, 0);
        let ctx = EvictionContext { player_chunk: cid(0, 0), current_tick: 10 };
        let picked = sched.select_for_budget(&rt, &ctx);
        assert_eq!(picked.len(), 2);
        // 最远的两个优先
        assert_eq!(picked[0].chunk_id, cid(3, 0));
        assert_eq!(picked[1].chunk_id, cid(2, 0));
    }
}
