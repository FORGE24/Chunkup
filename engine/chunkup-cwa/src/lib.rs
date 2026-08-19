//! Chunkup World Archive (CWA) v0.1 数据协议与序列化
//!
//! CWA 是 Chunkup 下一代核心数据格式,面向 GPU-native / CPU-efficient 的
//! Chunk Runtime:独立、分页、可缓存、可迁移的 chunk 数据。
//!
//! 设计目标:
//! - 三级寻址 Region(归档/预读单元) -> Chunk(状态/迁移单元) -> Section(计算/上传单元)
//! - SoA Hot View 优化 GPU coalesced 访问
//! - 基于 epoch 的 CPU/GPU 数据新旧追踪
//! - 手写 LE 字节序,byte-exact 偏移,不依赖 `#[repr(C)]` 内存布局
//!
//! 详见设计文档 §1-§19。

#![deny(rust_2018_idioms)]
#![warn(missing_docs)]
#![allow(clippy::module_inception)]

pub mod checksum;
pub mod compress;
pub mod descriptor;
pub mod error;
pub mod face;
pub mod face_mask_packet;
pub mod header;
pub mod id;
pub mod io;
pub mod state;
pub mod util;

pub use checksum::{compute as compute_checksum, verify as verify_checksum};
pub use descriptor::{
    build_hot_view_array, ChunkDescHotView, ChunkDescriptor, RegionIndexEntry, SectionDescriptor,
    SectionKind,
};
pub use error::{CwaError, CwaResult};
pub use compress::{
    CompressionBackend, RLE_MAX_RUN, ZSTD_DEFAULT_LEVEL, ZSTD_MAGIC, is_zstd_encoded, rle_compress,
    rle_decompress, zstd_compress, zstd_decompress,
};
pub use face::{
    FaceDir, FaceSection, SectionFace, FACE_SECTION_ENTRY_SIZE, SECTION_FACE_ENTRY_SIZE,
    face_section_flags, section_face_flags,
};
pub use face_mask_packet::{
    FaceMaskEntry, FaceMaskError, FaceMaskPacket, FACE_MASK_ENTRY_SIZE, FACE_MASK_HEADER_SIZE,
    FACE_MASK_MAGIC, FACE_MASK_VERSION, face_mask_flags,
};
pub use header::{Header, HEADER_SIZE, MAGIC};
pub use id::{morton_decode_2d, morton_encode_2d, ChunkId, RegionId};
pub use io::{CwaReader, CwaWriter};
pub use state::{state_flags, Lifecycle, StateEntry};

#[cfg(test)]
mod roundtrip {
    use super::*;
    use crate::descriptor::{region_flags, section_flags, ChunkDescHotView, SectionKind};
    use crate::id::{ChunkId, RegionId};
    use crate::state::{state_flags, Lifecycle};

    /// 构造一个完整的最小 CWA(header + 1 region + 2 chunk + 2 section + 2 state + payload),
    /// 写出 -> 读回 -> 逐字段比对。
    #[test]
    fn full_archive_roundtrip() {
        let mut header = Header::default();
        header.world_seed = 0x0BAD_C0FFEE;
        header.region_count = 1;
        header.chunk_count = 2;
        header.section_count = 2;

        // 表布局:region_index @ 0x1200, 之后紧跟各表
        header.region_index_offset = 0x1200;
        header.chunk_desc_offset = header.region_index_offset + 1 * 32;
        header.section_desc_offset = header.chunk_desc_offset + 2 * 80;
        // SectionDescriptor v0.1 扩展后为 40B
        header.state_table_offset = header.section_desc_offset + 2 * 40;
        header.payload_offset = header.state_table_offset + 2 * 16;
        header.payload_size = 512;

        let mut writer = CwaWriter::new(header.clone());

        let region = RegionIndexEntry {
            region_id: RegionId::new(0, 0, 0).0,
            region_offset: header.chunk_desc_offset,
            chunk_count: 2,
            section_count: 2,
            region_checksum: 0xDEAD_BEEF,
            flags: region_flags::COMPRESSED,
        };
        writer.write_region_index(0, &region).unwrap();

        let payload_a = vec![0xAAu8; 256];
        let payload_b = vec![0xBBu8; 256];

        let chunk_a = ChunkDescriptor {
            chunk_id: ChunkId::new(0, 0, 0).0,
            region_idx: 0,
            chunk_local_idx: 0,
            section_count: 1,
            payload_offset: 0,
            payload_size_comp: 128,
            payload_size_raw: payload_a.len() as u32,
            block_off: 0,
            biome_off: 64,
            density_off: 128,
            light_off: 192,
            mesh_off: 0,
            metadata_off: 0,
            state_flags: state_flags::CPU_OWNED,
            epoch: 1,
            version: 1,
            priority_hint: 100,
            checksum: compute_checksum(&payload_a),
            face_off: 0,
        };
        let chunk_b = ChunkDescriptor {
            chunk_id: ChunkId::new(0, 1, 0).0,
            region_idx: 0,
            chunk_local_idx: 1,
            section_count: 1,
            payload_offset: 256,
            payload_size_comp: 128,
            payload_size_raw: payload_b.len() as u32,
            block_off: 0,
            biome_off: 64,
            density_off: 128,
            light_off: 192,
            mesh_off: 0,
            metadata_off: 0,
            state_flags: state_flags::GPU_OWNED,
            epoch: 2,
            version: 1,
            priority_hint: 200,
            checksum: compute_checksum(&payload_b),
            face_off: 0,
        };
        writer.write_chunk_descriptor(0, &chunk_a).unwrap();
        writer.write_chunk_descriptor(1, &chunk_b).unwrap();

        let sec = SectionDescriptor {
            chunk_desc_idx: 0,
            section_y: 0,
            kind: SectionKind::Mixed as u8,
            flags: section_flags::DIRTY,
            block_off: 0,
            palette_off: 32,
            size_raw: 4096,
            size_comp: 512,
            mesh_off: 1024,
            checksum: 0xCAFE_BABE,
            face_off: 0,
        };
        writer.write_section_descriptor(0, &sec).unwrap();
        writer.write_section_descriptor(1, &sec).unwrap();

        let mut state = StateEntry::default();
        state.set_lifecycle(Lifecycle::GpuResident);
        state.cpu_epoch = 5;
        state.gpu_epoch = 3;
        state.mark_dirty(state_flags::DIRTY_BLOCK);
        writer.write_state_entry(0, &state).unwrap();
        writer.write_state_entry(1, &state).unwrap();

        writer
            .write_chunk_payload(header.payload_offset + chunk_a.payload_offset, &payload_a)
            .unwrap();
        writer
            .write_chunk_payload(header.payload_offset + chunk_b.payload_offset, &payload_b)
            .unwrap();

        let buf = writer.finish();
        assert!(buf.len() >= header.payload_offset as usize + 512);

        let reader = CwaReader::open(&buf).expect("open");
        assert_eq!(reader.header().world_seed, header.world_seed);
        assert_eq!(reader.header().chunk_count, 2);
        assert_eq!(reader.header().section_count, 2);

        let r = reader.read_region_index(0).unwrap();
        assert_eq!(r.region_id, region.region_id);
        assert_eq!(r.chunk_count, 2);
        assert_eq!(r.flags, region_flags::COMPRESSED);

        let ca = reader.read_chunk_descriptor(0).unwrap();
        assert_eq!(ca.chunk_id, chunk_a.chunk_id);
        assert_eq!(ca.epoch, 1);
        assert_eq!(ca.checksum, chunk_a.checksum);
        assert_eq!(ca.state_flags, state_flags::CPU_OWNED);

        let cb = reader.read_chunk_descriptor(1).unwrap();
        assert_eq!(cb.chunk_id, chunk_b.chunk_id);
        assert_eq!(cb.state_flags, state_flags::GPU_OWNED);

        let s0 = reader.read_section_descriptor(0).unwrap();
        assert_eq!(s0.kind_enum().unwrap(), SectionKind::Mixed);
        assert_eq!(s0.flags, section_flags::DIRTY);
        assert_eq!(s0.checksum, 0xCAFE_BABE);

        let st = reader.read_state_entry(0).unwrap();
        assert_eq!(st.lifecycle(), Lifecycle::GpuResident);
        assert!(st.is_gpu_stale());
        assert!(st.is_dirty());

        let pa = reader.read_chunk_payload(&ca).unwrap();
        assert_eq!(pa, &payload_a[..]);
        let pb = reader.read_chunk_payload(&cb).unwrap();
        assert_eq!(pb, &payload_b[..]);

        let biome = reader.read_resource(&ca, ca.biome_off, 64).unwrap();
        assert_eq!(biome.len(), 64);
        assert_eq!(&biome[..], &payload_a[64..128]);

        let hv = ChunkDescHotView::from_descriptor(&ca);
        assert_eq!(hv.payload_offset, ca.payload_offset);
        assert_eq!(hv.epoch, ca.epoch);
        assert_eq!(hv.state_flags, ca.state_flags);

        let hot_arr = build_hot_view_array(&[chunk_a, chunk_b]);
        assert_eq!(hot_arr.len(), 24 * 2);
        let hv0 = ChunkDescHotView::from_bytes(hot_arr[0..24].try_into().unwrap());
        assert_eq!(hv0.epoch, 1);
    }

    /// 损坏的 magic / checksum 必须被拒绝。
    #[test]
    fn corrupt_header_rejected() {
        let mut buf = CwaWriter::new(Header::default()).finish();
        buf[0] = b'X';
        assert!(CwaReader::open(&buf).is_err());

        let mut buf = CwaWriter::new(Header::default()).finish();
        buf[1] ^= 0xFF;
        assert!(CwaReader::open(&buf).is_err());
    }

    /// 越界索引必须返回错误而非 panic。
    #[test]
    fn out_of_range_index_errors() {
        // default header 的 region_index_offset=0x1200 远超 buf 长度(512),必然越界。
        // 注:chunk_desc_offset=0 落在 header 内,read_chunk_descriptor(0) 会读到
        // header 数据并返回 Ok(全零 descriptor),因此不在此断言。
        let writer = CwaWriter::new(Header::default());
        let buf = writer.finish();
        let reader = CwaReader::open(&buf).unwrap();
        assert!(reader.read_region_index(0).is_err());
        assert!(reader.read_region_index(usize::MAX).is_err());
    }
}
