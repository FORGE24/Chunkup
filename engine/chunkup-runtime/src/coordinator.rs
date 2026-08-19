//! LoadCoordinator:按 SectionY 自上而下编排 CPU/GPU 面加载(设计 §6,§12)。
//!
//! ## 核心约束
//!
//! 1. **自上而下**:section_y 从高到低处理(天空 → 地底),保证上层 face 先就绪,
//!    下层依赖上层的 air 判定时不会等待。
//! 2. **跨 chunk 同 section_y 同 face 顶底一致**:由 [FaceSection] 聚合校验。
//!    不一致的组被标记为 [StaleReason::InconsistentHeights],需要重算。
//! 3. **air 跳过**:全 air 的面([SectionFace::is_all_air])不产生 mesh/light 任务。
//! 4. **CPU/GPU 分工(消除乒乓搬运)**:
//!    - air 判定:数据(density/block)在 GPU 时走 [GpuTask::AirDetermination](全程留 VRAM);
//!      否则回退 [CpuTask::AirDetermination]。
//!    - mesh/light:[GpuTask::MeshBuild] / [GpuTask::LightCompute] 消费 air 位图做 culling。
//!
//! ## 工作流
//!
//! ```text
//!  enqueue_section_y(15, [chunk_a, chunk_b])
//!  enqueue_section_y(14, [chunk_c])
//!  plan_next(face_lookup, data_location, face_sections)
//!      → SectionLoadPlan { section_y: 15, ... }   // 最高优先
//!  plan_next(...)  → SectionLoadPlan { section_y: 14, ... }
//!  plan_next(...)  → None
//! ```

use std::collections::BTreeMap;

use chunkup_cwa::face::{
    face_section_flags, FaceDir, FaceSection, SectionFace, HEIGHT_ALL_AIR,
};
use chunkup_cwa::id::ChunkId;

/// chunk 的 block/density 数据当前所在地。
///
/// 用于 [LoadCoordinator::plan_next] 分派 air 判定到 CPU 或 GPU。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DataLocation {
    /// 数据在 CPU RAM(`cpu_payload` 持有,`CPU_OWNED`)。
    Cpu,
    /// 数据在 GPU VRAM(`gpu_handle` 持有,`GPU_OWNED`),可直接喂 GPU kernel。
    Gpu,
    /// 数据未驻留(需要先 load 或 fallback CPU 路径)。
    Absent,
}

/// CPU 侧任务:air 判定。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CpuTask {
    /// 目标 chunk。
    pub chunk_id: ChunkId,
    /// 目标 section_y。
    pub section_y: u8,
    /// 目标面方向。
    pub face_dir: FaceDir,
}

/// GPU 侧任务:air 判定 / mesh / light。
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum GpuTask {
    /// air 判定(GPU kernel):扫描 VRAM 中的 block 数据填 air 位图。
    ///
    /// 数据全程留 VRAM,不回拉 CPU。输出 air 位图写入 VRAM 的 [SectionFace]。
    AirDetermination {
        /// 目标 chunk。
        chunk_id: ChunkId,
        /// 目标 section_y。
        section_y: u8,
        /// 目标面方向。
        face_dir: FaceDir,
    },
    /// 构建 mesh(消费 air 位图做 face culling)。
    MeshBuild {
        /// 目标 chunk。
        chunk_id: ChunkId,
        /// 目标 section_y。
        section_y: u8,
    },
    /// 光照计算。
    LightCompute {
        /// 目标 chunk。
        chunk_id: ChunkId,
        /// 目标 section_y。
        section_y: u8,
    },
}

/// 单个 section_y 的加载计划。
#[derive(Clone, Debug)]
pub struct SectionLoadPlan {
    /// 该计划的 section_y。
    pub section_y: u8,
    /// CPU 任务(air 判定),按 chunk 顺序。
    pub cpu_tasks: Vec<CpuTask>,
    /// GPU 任务(mesh / light),按 chunk 顺序。
    pub gpu_tasks: Vec<GpuTask>,
    /// 该 section_y 下的 face 聚合索引(每个 face_dir 一项)。
    pub face_sections: Vec<FaceSection>,
    /// 被跳过的全 air 面(chunk_id, face_dir)。
    pub skipped_air_faces: Vec<(ChunkId, FaceDir)>,
    /// 校验失败的面(face_dir, reason)。
    pub stale_faces: Vec<(FaceDir, StaleReason)>,
}

/// 面过期原因。
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum StaleReason {
    /// 跨 chunk 顶底高度不一致。
    InconsistentHeights,
    /// 组内全 air(无可渲染面)。
    AllAir,
    /// face 位图未同步(BITMAP_STALE)。
    BitmapStale,
}

/// LoadCoordinator:按 SectionY 自上而下编排面加载。
///
/// 内部维护 `BTreeMap<section_y, Vec<ChunkId>>`,利用 BTreeMap 的有序性
/// 保证 `plan_next()` 总返回最大的 section_y(自上而下)。
pub struct LoadCoordinator {
    /// 待编排的 section_y → chunks 映射(降序消费)。
    pending: BTreeMap<u8, Vec<ChunkId>>,
    /// 已完成的 section_y(避免重复入队)。
    done: Vec<u8>,
}

impl Default for LoadCoordinator {
    fn default() -> Self {
        LoadCoordinator::new()
    }
}

impl LoadCoordinator {
    /// 创建空协调器。
    pub fn new() -> Self {
        LoadCoordinator {
            pending: BTreeMap::new(),
            done: Vec::new(),
        }
    }

    /// 入队一个 section_y 及其涉及的 chunks。
    ///
    /// 重复入队同一 section_y 会合并 chunk 列表(去重)。
    pub fn enqueue_section_y(&mut self, section_y: u8, chunks: &[ChunkId]) {
        let entry = self.pending.entry(section_y).or_default();
        for &c in chunks {
            if !entry.contains(&c) {
                entry.push(c);
            }
        }
    }

    /// 待编排的 section_y 数。
    pub fn pending_count(&self) -> usize {
        self.pending.len()
    }

    /// 已完成的 section_y 数。
    pub fn done_count(&self) -> usize {
        self.done.len()
    }

    /// 取出最高 section_y 的加载计划(自上而下)。
    ///
    /// 调用方需提供:
    /// - `face_lookup`:每个 (chunk_id, face_dir) 对应的 [SectionFace](返回 `None` 视为邻居缺失,跳过)。
    /// - `data_location`:每个 chunk 的 block/density 数据所在地。数据在 GPU 时
    ///   air 判定走 [GpuTask::AirDetermination](全程留 VRAM),否则走 [CpuTask::AirDetermination]。
    /// - `face_sections`:该 section_y 下每个 face_dir 的 [FaceSection] 聚合索引。
    pub fn plan_next<F, L>(
        &mut self,
        mut face_lookup: F,
        data_location: L,
        face_sections: Vec<FaceSection>,
    ) -> Option<SectionLoadPlan>
    where
        F: FnMut(ChunkId, FaceDir) -> Option<SectionFace>,
        L: Fn(ChunkId) -> DataLocation,
    {
        // BTreeMap 最大 key = 最高 section_y
        let (&section_y, chunks) = self.pending.iter().last()?;

        let mut cpu_tasks = Vec::new();
        let mut gpu_tasks = Vec::new();
        let mut skipped_air_faces = Vec::new();
        let mut stale_faces = Vec::new();

        // 校验 face_sections
        for fs in &face_sections {
            if fs.section_y != section_y {
                continue;
            }
            let dir = match FaceDir::from_u8(fs.face_dir) {
                Some(d) => d,
                None => continue,
            };
            let consistent = fs.flags & face_section_flags::CONSISTENT != 0;
            let all_air = fs.top_height_min == HEIGHT_ALL_AIR;
            if !consistent {
                stale_faces.push((dir, StaleReason::InconsistentHeights));
            } else if all_air {
                stale_faces.push((dir, StaleReason::AllAir));
            }
        }

        // 为每个 chunk × 每个 face_dir 产出任务
        for &chunk_id in chunks {
            // 查询数据所在地:决定 air 判定走 CPU 还是 GPU
            let loc = data_location(chunk_id);
            for dir_index in 0..FaceDir::COUNT {
                let dir = FaceDir::from_u8(dir_index as u8).unwrap();
                let face = match face_lookup(chunk_id, dir) {
                    Some(f) => f,
                    None => {
                        // 邻居缺失,跳过(不产任务)
                        continue;
                    }
                };

                // air 判定:数据在 GPU → GPU kernel(数据留 VRAM);否则 CPU
                match loc {
                    DataLocation::Gpu => gpu_tasks.push(GpuTask::AirDetermination {
                        chunk_id,
                        section_y,
                        face_dir: dir,
                    }),
                    DataLocation::Cpu | DataLocation::Absent => cpu_tasks.push(CpuTask {
                        chunk_id,
                        section_y,
                        face_dir: dir,
                    }),
                }

                if face.is_all_air() {
                    // 全 air:跳过 mesh/light
                    skipped_air_faces.push((chunk_id, dir));
                } else {
                    // 非 air:产 GPU mesh + light
                    gpu_tasks.push(GpuTask::MeshBuild {
                        chunk_id,
                        section_y,
                    });
                    gpu_tasks.push(GpuTask::LightCompute {
                        chunk_id,
                        section_y,
                    });
                }
            }
        }

        // 从 pending 移到 done
        self.pending.remove(&section_y);
        self.done.push(section_y);

        Some(SectionLoadPlan {
            section_y,
            cpu_tasks,
            gpu_tasks,
            face_sections,
            skipped_air_faces,
            stale_faces,
        })
    }

    /// 重置协调器(清空 pending + done)。
    pub fn reset(&mut self) {
        self.pending.clear();
        self.done.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chunkup_cwa::face::{section_face_flags, AIR_BITMAP_SIZE};

    fn cid(x: i32, z: i32) -> ChunkId {
        ChunkId::new(0, x, z)
    }

    fn solid_face(dir: FaceDir) -> SectionFace {
        SectionFace::all_solid(dir)
    }

    fn air_face(dir: FaceDir) -> SectionFace {
        SectionFace::all_air(dir)
    }

    /// 默认 data_location closure:所有 chunk 都在 CPU(保持原测试语义)。
    fn cpu_loc(_: ChunkId) -> DataLocation {
        DataLocation::Cpu
    }

    /// 所有 chunk 都在 GPU。
    fn gpu_loc(_: ChunkId) -> DataLocation {
        DataLocation::Gpu
    }

    /// 所有 chunk 未驻留。
    fn absent_loc(_: ChunkId) -> DataLocation {
        DataLocation::Absent
    }

    #[test]
    fn enqueue_dedupes() {
        let mut coord = LoadCoordinator::new();
        coord.enqueue_section_y(10, &[cid(0, 0), cid(1, 0)]);
        coord.enqueue_section_y(10, &[cid(1, 0), cid(2, 0)]); // cid(1,0) 重复
        assert_eq!(coord.pending_count(), 1);
    }

    #[test]
    fn plan_next_returns_highest_section_y_first() {
        let mut coord = LoadCoordinator::new();
        coord.enqueue_section_y(5, &[cid(0, 0)]);
        coord.enqueue_section_y(15, &[cid(0, 0)]);
        coord.enqueue_section_y(10, &[cid(0, 0)]);

        let plan = coord.plan_next(|_, _| Some(solid_face(FaceDir::PosY)), cpu_loc, Vec::new()).unwrap();
        assert_eq!(plan.section_y, 15);

        let plan = coord.plan_next(|_, _| Some(solid_face(FaceDir::PosY)), cpu_loc, Vec::new()).unwrap();
        assert_eq!(plan.section_y, 10);

        let plan = coord.plan_next(|_, _| Some(solid_face(FaceDir::PosY)), cpu_loc, Vec::new()).unwrap();
        assert_eq!(plan.section_y, 5);

        assert!(coord.plan_next(|_, _| None, cpu_loc, Vec::new()).is_none());
    }

    #[test]
    fn all_air_face_skips_gpu() {
        let mut coord = LoadCoordinator::new();
        coord.enqueue_section_y(3, &[cid(0, 0)]);

        let plan = coord
            .plan_next(|_, dir| Some(air_face(dir)), cpu_loc, Vec::new())
            .unwrap();
        // 6 个面全 air → 6 个 CPU 任务,0 GPU 任务
        assert_eq!(plan.cpu_tasks.len(), 6);
        assert!(plan.gpu_tasks.is_empty());
        assert_eq!(plan.skipped_air_faces.len(), 6);
    }

    #[test]
    fn solid_face_produces_mesh_and_light() {
        let mut coord = LoadCoordinator::new();
        coord.enqueue_section_y(3, &[cid(0, 0)]);

        let plan = coord
            .plan_next(|_, dir| Some(solid_face(dir)), cpu_loc, Vec::new())
            .unwrap();
        // 6 个面全 solid → 6 CPU + 12 GPU(每面 mesh + light)
        assert_eq!(plan.cpu_tasks.len(), 6);
        assert_eq!(plan.gpu_tasks.len(), 12);
        assert!(plan.skipped_air_faces.is_empty());
    }

    #[test]
    fn missing_face_lookup_skipped() {
        let mut coord = LoadCoordinator::new();
        coord.enqueue_section_y(3, &[cid(0, 0)]);

        // 只返回 PosY 的 face,其他 None
        let plan = coord
            .plan_next(|_, dir| if dir == FaceDir::PosY { Some(solid_face(dir)) } else { None }, cpu_loc, Vec::new())
            .unwrap();
        assert_eq!(plan.cpu_tasks.len(), 1);
        assert_eq!(plan.cpu_tasks[0].face_dir, FaceDir::PosY);
    }

    #[test]
    fn inconsistent_face_section_marked_stale() {
        let mut coord = LoadCoordinator::new();
        coord.enqueue_section_y(7, &[cid(0, 0)]);

        let mut fs = FaceSection::new(7, FaceDir::PosY, 2);
        fs.merge_heights(10, 4);
        fs.merge_heights(12, 4); // top 不一致
        fs.check_consistency();
        // CONSISTENT 不应置位
        assert_eq!(fs.flags & face_section_flags::CONSISTENT, 0);

        let plan = coord
            .plan_next(|_, _| Some(solid_face(FaceDir::PosY)), cpu_loc, vec![fs])
            .unwrap();
        assert!(plan.stale_faces.iter().any(|(d, r)| {
            *d == FaceDir::PosY && *r == StaleReason::InconsistentHeights
        }));
    }

    #[test]
    fn consistent_face_section_not_stale() {
        let mut coord = LoadCoordinator::new();
        coord.enqueue_section_y(7, &[cid(0, 0)]);

        let mut fs = FaceSection::new(7, FaceDir::PosY, 2);
        fs.merge_heights(10, 4);
        fs.merge_heights(10, 4);
        fs.check_consistency();
        assert!(fs.flags & face_section_flags::CONSISTENT != 0);

        let plan = coord
            .plan_next(|_, _| Some(solid_face(FaceDir::PosY)), cpu_loc, vec![fs])
            .unwrap();
        // PosY 不应在 stale_faces 中(consistent 且非全 air)
        assert!(!plan.stale_faces.iter().any(|(d, _)| *d == FaceDir::PosY));
    }

    #[test]
    fn multiple_chunks_produce_tasks_per_chunk() {
        let mut coord = LoadCoordinator::new();
        coord.enqueue_section_y(5, &[cid(0, 0), cid(1, 0), cid(2, 0)]);

        let plan = coord
            .plan_next(|_, dir| Some(solid_face(dir)), cpu_loc, Vec::new())
            .unwrap();
        // 3 chunks × 6 faces = 18 CPU, 36 GPU
        assert_eq!(plan.cpu_tasks.len(), 18);
        assert_eq!(plan.gpu_tasks.len(), 36);
    }

    #[test]
    fn done_count_tracks_progress() {
        let mut coord = LoadCoordinator::new();
        coord.enqueue_section_y(1, &[cid(0, 0)]);
        coord.enqueue_section_y(2, &[cid(0, 0)]);
        assert_eq!(coord.done_count(), 0);

        coord.plan_next(|_, _| Some(solid_face(FaceDir::PosY)), cpu_loc, Vec::new()).unwrap();
        assert_eq!(coord.done_count(), 1);
        assert_eq!(coord.pending_count(), 1);

        coord.plan_next(|_, _| Some(solid_face(FaceDir::PosY)), cpu_loc, Vec::new()).unwrap();
        assert_eq!(coord.done_count(), 2);
        assert_eq!(coord.pending_count(), 0);
    }

    #[test]
    fn reset_clears_all() {
        let mut coord = LoadCoordinator::new();
        coord.enqueue_section_y(1, &[cid(0, 0)]);
        coord.enqueue_section_y(2, &[cid(0, 0)]);
        coord.plan_next(|_, _| None, cpu_loc, Vec::new()).unwrap();

        coord.reset();
        assert_eq!(coord.pending_count(), 0);
        assert_eq!(coord.done_count(), 0);
    }

    #[test]
    fn empty_coordinator_returns_none() {
        let mut coord = LoadCoordinator::new();
        assert!(coord.plan_next(|_, _| None, cpu_loc, Vec::new()).is_none());
    }

    #[test]
    fn air_bitmap_size_sanity() {
        // 确保 256-bit = 32 字节
        assert_eq!(AIR_BITMAP_SIZE, 32);
    }

    // =========================================================================
    // GPU air 判定分派测试(消除乒乓搬运)
    // =========================================================================

    #[test]
    fn gpu_resident_chunk_uses_gpu_air_determination() {
        let mut coord = LoadCoordinator::new();
        coord.enqueue_section_y(3, &[cid(0, 0)]);

        // 数据在 GPU → air 判定走 GpuTask::AirDetermination
        let plan = coord
            .plan_next(|_, dir| Some(solid_face(dir)), gpu_loc, Vec::new())
            .unwrap();
        // 6 个面全 solid → 0 CPU 任务,6 GPU air + 12 GPU mesh/light = 18 GPU
        assert!(plan.cpu_tasks.is_empty(), "GPU 数据应走 GPU air 判定");
        let gpu_air_count = plan
            .gpu_tasks
            .iter()
            .filter(|t| matches!(t, GpuTask::AirDetermination { .. }))
            .count();
        assert_eq!(gpu_air_count, 6);
        // mesh + light 仍各 6
        assert_eq!(plan.gpu_tasks.len(), 18);
    }

    #[test]
    fn cpu_resident_chunk_uses_cpu_air_determination() {
        let mut coord = LoadCoordinator::new();
        coord.enqueue_section_y(3, &[cid(0, 0)]);

        let plan = coord
            .plan_next(|_, dir| Some(solid_face(dir)), cpu_loc, Vec::new())
            .unwrap();
        // CPU 路径:air 判定走 CpuTask,GPU 只有 mesh/light
        assert_eq!(plan.cpu_tasks.len(), 6);
        let gpu_air_count = plan
            .gpu_tasks
            .iter()
            .filter(|t| matches!(t, GpuTask::AirDetermination { .. }))
            .count();
        assert_eq!(gpu_air_count, 0);
    }

    #[test]
    fn absent_chunk_falls_back_to_cpu() {
        let mut coord = LoadCoordinator::new();
        coord.enqueue_section_y(3, &[cid(0, 0)]);

        // 数据未驻留 → fallback CPU air 判定(调用方需先 load)
        let plan = coord
            .plan_next(|_, dir| Some(solid_face(dir)), absent_loc, Vec::new())
            .unwrap();
        assert_eq!(plan.cpu_tasks.len(), 6);
        assert_eq!(plan.gpu_tasks.len(), 12);
    }

    #[test]
    fn mixed_locations_in_one_plan() {
        let mut coord = LoadCoordinator::new();
        // chunk_a 在 GPU,chunk_b 在 CPU
        coord.enqueue_section_y(5, &[cid(0, 0), cid(1, 0)]);

        let plan = coord
            .plan_next(
                |_, dir| Some(solid_face(dir)),
                |c| if c == cid(0, 0) { DataLocation::Gpu } else { DataLocation::Cpu },
                Vec::new(),
            )
            .unwrap();
        // chunk_a:6 GPU air + 12 GPU mesh/light = 18 GPU,0 CPU
        // chunk_b:6 CPU air + 12 GPU mesh/light = 6 CPU,12 GPU
        assert_eq!(plan.cpu_tasks.len(), 6);
        assert_eq!(plan.gpu_tasks.len(), 30); // 18 + 12
        // CPU 任务全属于 chunk_b
        assert!(plan.cpu_tasks.iter().all(|t| t.chunk_id == cid(1, 0)));
    }

    #[test]
    fn gpu_air_determination_skipped_for_all_air_face() {
        let mut coord = LoadCoordinator::new();
        coord.enqueue_section_y(3, &[cid(0, 0)]);

        // 数据在 GPU 但面全 air → air 判定仍跑(GPU 校验位图),mesh/light 跳过
        let plan = coord
            .plan_next(|_, dir| Some(air_face(dir)), gpu_loc, Vec::new())
            .unwrap();
        // 6 GPU air(校验),0 mesh/light
        let gpu_air_count = plan
            .gpu_tasks
            .iter()
            .filter(|t| matches!(t, GpuTask::AirDetermination { .. }))
            .count();
        assert_eq!(gpu_air_count, 6);
        let mesh_light_count = plan
            .gpu_tasks
            .iter()
            .filter(|t| matches!(t, GpuTask::MeshBuild { .. } | GpuTask::LightCompute { .. }))
            .count();
        assert_eq!(mesh_light_count, 0);
        assert_eq!(plan.skipped_air_faces.len(), 6);
    }
}
