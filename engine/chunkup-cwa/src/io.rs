//! CWA Reader/Writer:基于偏移的随机访问 IO。
//!
//! Reader 包装 `&[u8]`(mmap 友好);Writer 包装 `Vec<u8>`。
//!
//! ## 压缩集成(自 v0.1 起)
//!
//! 当 [Header](crate::header::Header) 的 [COMPRESSED_PAYLOAD](crate::header::flags::COMPRESSED_PAYLOAD)
//! flag 置位时,所有 chunk payload 均以 zstd 编码存储。
//!
//! - [CwaReader::read_chunk_payload]:零拷贝返回原始字节(可能是压缩的)
//! - [CwaReader::read_chunk_payload_decoded]:返回 `Vec<u8>`,如压缩则透明解压
//! - [CwaWriter::write_chunk_payload]:写入原始字节(不做压缩)
//! - [CwaWriter::write_chunk_payload_compressed]:压缩后写入,自动设置 Header flag

use crate::compress::CompressionBackend;
use crate::descriptor::{
    ChunkDescriptor, RegionIndexEntry, SectionDescriptor,
};
use crate::error::{CwaError, CwaResult};
use crate::header::{Header, HEADER_SIZE, flags as header_flags};
use crate::state::StateEntry;

/// 基于 `&[u8]` 的随机访问 Reader(mmap 友好,零拷贝 payload)。
pub struct CwaReader<'a> {
    data: &'a [u8],
    header: Header,
}

impl<'a> CwaReader<'a> {
    /// 打开(校验 magic 与 checksum)。
    pub fn open(data: &'a [u8]) -> CwaResult<Self> {
        if data.len() < HEADER_SIZE {
            return Err(CwaError::UnexpectedEof {
                need: HEADER_SIZE,
                have: data.len(),
            });
        }
        let mut hb = [0u8; HEADER_SIZE];
        hb.copy_from_slice(&data[..HEADER_SIZE]);
        let header = Header::from_bytes(&hb)?;
        Ok(CwaReader { data, header })
    }

    pub fn header(&self) -> &Header {
        &self.header
    }

    pub fn data(&self) -> &'a [u8] {
        self.data
    }

    /// 计算表中某 entry 的字节范围。
    fn table_range(
        &self,
        table_offset: u64,
        entry_size: u32,
        idx: usize,
        need: usize,
    ) -> CwaResult<(usize, usize)> {
        let table_off = table_offset as usize;
        let es = entry_size as usize;
        if es < need {
            return Err(CwaError::SizeInvariant {
                what: "entry_size",
                expected: need,
                got: es,
            });
        }
        let idx_off = idx.checked_mul(es).ok_or(CwaError::OutOfRange {
            what: "index_offset",
            value: idx as u64,
            max: u64::MAX,
        })?;
        let off = table_off.checked_add(idx_off).ok_or(CwaError::OutOfRange {
            what: "offset",
            value: table_off as u64,
            max: u64::MAX,
        })?;
        let end = off.checked_add(need).ok_or(CwaError::OutOfRange {
            what: "end",
            value: off as u64,
            max: u64::MAX,
        })?;
        if end > self.data.len() {
            return Err(CwaError::OutOfRange {
                what: "read",
                value: end as u64,
                max: self.data.len() as u64,
            });
        }
        Ok((off, end))
    }

    pub fn read_region_index(&self, idx: usize) -> CwaResult<RegionIndexEntry> {
        let (off, end) = self.table_range(
            self.header.region_index_offset,
            self.header.region_index_entry_size,
            idx,
            RegionIndexEntry::SIZE,
        )?;
        let mut b = [0u8; RegionIndexEntry::SIZE];
        b.copy_from_slice(&self.data[off..end]);
        Ok(RegionIndexEntry::from_bytes(&b))
    }

    pub fn read_chunk_descriptor(&self, idx: usize) -> CwaResult<ChunkDescriptor> {
        let (off, end) = self.table_range(
            self.header.chunk_desc_offset,
            self.header.chunk_desc_entry_size,
            idx,
            ChunkDescriptor::SIZE,
        )?;
        let mut b = [0u8; ChunkDescriptor::SIZE];
        b.copy_from_slice(&self.data[off..end]);
        Ok(ChunkDescriptor::from_bytes(&b))
    }

    pub fn read_section_descriptor(&self, idx: usize) -> CwaResult<SectionDescriptor> {
        let (off, end) = self.table_range(
            self.header.section_desc_offset,
            self.header.section_desc_entry_size,
            idx,
            SectionDescriptor::SIZE,
        )?;
        let mut b = [0u8; SectionDescriptor::SIZE];
        b.copy_from_slice(&self.data[off..end]);
        Ok(SectionDescriptor::from_bytes(&b))
    }

    pub fn read_state_entry(&self, idx: usize) -> CwaResult<StateEntry> {
        let (off, end) = self.table_range(
            self.header.state_table_offset,
            StateEntry::SIZE as u32,
            idx,
            StateEntry::SIZE,
        )?;
        let mut b = [0u8; StateEntry::SIZE];
        b.copy_from_slice(&self.data[off..end]);
        Ok(StateEntry::from_bytes(&b))
    }

    /// 读取 chunk 整个 payload(零拷贝,返回引用)。
    ///
    /// 返回的是磁盘上存储的原始字节:若 Header 启用了
    /// [COMPRESSED_PAYLOAD](crate::header::flags::COMPRESSED_PAYLOAD),
    /// 则返回的是 zstd 压缩字节,需要用 [read_chunk_payload_decoded](CwaReader::read_chunk_payload_decoded)
    /// 才能拿到解压后的数据。
    pub fn read_chunk_payload(&self, desc: &ChunkDescriptor) -> CwaResult<&'a [u8]> {
        let base = self.header.payload_offset as usize;
        let off = base
            .checked_add(desc.payload_offset as usize)
            .ok_or(CwaError::OutOfRange {
                what: "payload_offset",
                value: desc.payload_offset as u64,
                max: u64::MAX,
            })?;
        // 注意:压缩时 payload_size_comp 是压缩后大小;但 roundtrip 测试中两者常相等。
        // 真实压缩文件应使用 payload_size_comp 圈定磁盘字节范围。
        let stored_size = if self.is_payload_compressed() {
            desc.payload_size_comp as usize
        } else {
            desc.payload_size_raw as usize
        };
        let end = off
            .checked_add(stored_size)
            .ok_or(CwaError::OutOfRange {
                what: "payload_end",
                value: off as u64,
                max: u64::MAX,
            })?;
        if end > self.data.len() {
            return Err(CwaError::OutOfRange {
                what: "payload",
                value: end as u64,
                max: self.data.len() as u64,
            });
        }
        Ok(&self.data[off..end])
    }

    /// Header 是否声明 chunk payload 已压缩。
    pub fn is_payload_compressed(&self) -> bool {
        self.header.flags & header_flags::COMPRESSED_PAYLOAD != 0
    }

    /// 读取 chunk payload 并按需解压,返回 `Vec<u8>`。
    ///
    /// 若 Header 标记压缩,则用 zstd 解压;否则直接拷贝。
    /// `desc.payload_size_raw` 用作解压期望大小预分配。
    pub fn read_chunk_payload_decoded(&self, desc: &ChunkDescriptor) -> CwaResult<Vec<u8>> {
        let raw = self.read_chunk_payload(desc)?;
        if self.is_payload_compressed() {
            CompressionBackend::Zstd.decompress(raw, desc.payload_size_raw as usize)
        } else {
            // 未压缩:验证大小一致(防错配)
            if raw.len() != desc.payload_size_raw as usize {
                return Err(CwaError::SizeInvariant {
                    what: "payload_size_raw",
                    expected: desc.payload_size_raw as usize,
                    got: raw.len(),
                });
            }
            Ok(raw.to_vec())
        }
    }

    /// 读取 chunk payload 内某资源(block/biome/density/light/mesh)。
    pub fn read_resource(
        &self,
        desc: &ChunkDescriptor,
        rel_off: u32,
        size: u32,
    ) -> CwaResult<&'a [u8]> {
        let payload = self.read_chunk_payload(desc)?;
        let off = rel_off as usize;
        let end = off
            .checked_add(size as usize)
            .ok_or(CwaError::OutOfRange {
                what: "resource_end",
                value: off as u64,
                max: u64::MAX,
            })?;
        if end > payload.len() {
            return Err(CwaError::OutOfRange {
                what: "resource",
                value: end as u64,
                max: payload.len() as u64,
            });
        }
        Ok(&payload[off..end])
    }
}

/// 基于 `Vec<u8>` 的 Writer(顺序构建 CWA)。
pub struct CwaWriter {
    buf: Vec<u8>,
    header: Header,
}

impl CwaWriter {
    /// 创建(写入 header 占位,checksum 待 finish 重算)。
    pub fn new(header: Header) -> Self {
        let mut buf = vec![0u8; HEADER_SIZE];
        let h = header.to_bytes();
        buf[..HEADER_SIZE].copy_from_slice(&h);
        CwaWriter { buf, header }
    }

    pub fn header(&self) -> &Header {
        &self.header
    }

    pub fn buf(&self) -> &[u8] {
        &self.buf
    }

    fn ensure_size(&mut self, end: usize) {
        if end > self.buf.len() {
            self.buf.resize(end, 0);
        }
    }

    fn write_table_entry(
        &mut self,
        table_offset: u64,
        entry_size: u32,
        idx: usize,
        data: &[u8],
    ) -> CwaResult<()> {
        let table_off = table_offset as usize;
        let es = entry_size as usize;
        if es < data.len() {
            return Err(CwaError::SizeInvariant {
                what: "entry_size",
                expected: data.len(),
                got: es,
            });
        }
        let off = table_off + idx * es;
        let end = off + data.len();
        self.ensure_size(end);
        self.buf[off..end].copy_from_slice(data);
        Ok(())
    }

    pub fn write_region_index(&mut self, idx: usize, e: &RegionIndexEntry) -> CwaResult<()> {
        self.write_table_entry(
            self.header.region_index_offset,
            self.header.region_index_entry_size,
            idx,
            &e.to_bytes(),
        )
    }

    pub fn write_chunk_descriptor(&mut self, idx: usize, d: &ChunkDescriptor) -> CwaResult<()> {
        self.write_table_entry(
            self.header.chunk_desc_offset,
            self.header.chunk_desc_entry_size,
            idx,
            &d.to_bytes(),
        )
    }

    pub fn write_section_descriptor(
        &mut self,
        idx: usize,
        s: &SectionDescriptor,
    ) -> CwaResult<()> {
        self.write_table_entry(
            self.header.section_desc_offset,
            self.header.section_desc_entry_size,
            idx,
            &s.to_bytes(),
        )
    }

    pub fn write_state_entry(&mut self, idx: usize, s: &StateEntry) -> CwaResult<()> {
        self.write_table_entry(
            self.header.state_table_offset,
            StateEntry::SIZE as u32,
            idx,
            &s.to_bytes(),
        )
    }

    /// 写入 chunk payload 到指定绝对偏移。
    pub fn write_chunk_payload(&mut self, abs_offset: u64, data: &[u8]) -> CwaResult<()> {
        let off = abs_offset as usize;
        let end = off
            .checked_add(data.len())
            .ok_or(CwaError::OutOfRange {
                what: "payload_end",
                value: off as u64,
                max: u64::MAX,
            })?;
        self.ensure_size(end);
        self.buf[off..end].copy_from_slice(data);
        Ok(())
    }

    /// 用 zstd 压缩并写入 chunk payload,返回 `(压缩后大小, 是否实际压缩)`。
    ///
    /// - 若压缩未带来收益(短数据),退化为直接写入,`did_compress=false`。
    /// - 调用方应根据 `did_compress` 设置 `ChunkDescriptor.payload_size_comp`
    ///   与 `ChunkDescriptor.payload_size_raw`,并在 Header 中标记
    ///   [COMPRESSED_PAYLOAD](crate::header::flags::COMPRESSED_PAYLOAD)。
    pub fn write_chunk_payload_compressed(
        &mut self,
        abs_offset: u64,
        data: &[u8],
        level: i32,
    ) -> CwaResult<(usize, bool)> {
        let (payload, did_compress) = CompressionBackend::Zstd.compress(data, level)?;
        self.write_chunk_payload(abs_offset, &payload)?;
        Ok((payload.len(), did_compress))
    }

    /// 在 Header 上启用 / 关闭 payload 压缩标记。
    ///
    /// 当任一 chunk 走压缩路径时必须调用此方法置位 flag,
    /// 否则 [CwaReader::read_chunk_payload_decoded] 不会触发解压。
    pub fn set_payload_compressed(&mut self, compressed: bool) {
        if compressed {
            self.header.flags |= header_flags::COMPRESSED_PAYLOAD;
        } else {
            self.header.flags &= !header_flags::COMPRESSED_PAYLOAD;
        }
    }

    /// 完成写入:重算 header checksum 并写回。
    pub fn finish(mut self) -> Vec<u8> {
        self.header.header_checksum = self.header.compute_checksum();
        let hb = self.header.to_bytes();
        self.buf[..HEADER_SIZE].copy_from_slice(&hb);
        self.buf
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::descriptor::{region_flags, ChunkDescHotView, SectionKind, section_flags};
    use crate::id::{ChunkId, RegionId};
    use crate::state::{state_flags, Lifecycle};

    #[test]
    fn full_roundtrip() {
        // 构造 header
        let mut header = Header::default();
        header.world_seed = 0x1234_5678_9ABC_DEF0;
        header.region_count = 1;
        header.chunk_count = 2;
        header.section_count = 4;
        // 表布局:region_index @ 0x1200, 之后紧跟各表
        header.region_index_offset = 0x1200;
        header.chunk_desc_offset = 0x1200 + 32; // 1 region * 32
        header.section_desc_offset = header.chunk_desc_offset + 2 * 80; // 2 chunk * 80
        // SectionDescriptor v0.1 扩展后为 40B
        header.state_table_offset = header.section_desc_offset + 4 * 40; // 4 section * 40
        header.payload_offset = header.state_table_offset + 2 * 16; // 2 state * 16
        header.payload_size = 1024;

        let mut writer = CwaWriter::new(header.clone());

        // region index
        let region = RegionIndexEntry {
            region_id: RegionId::new(0, 0, 0).0,
            region_offset: header.chunk_desc_offset,
            chunk_count: 2,
            section_count: 4,
            region_checksum: 0xDEAD_BEEF,
            flags: region_flags::COMPRESSED,
        };
        writer.write_region_index(0, &region).unwrap();

        // chunk descriptors + payload
        let payload_a = vec![0xAAu8; 256];
        let payload_b = vec![0xBBu8; 256];

        let chunk_a = ChunkDescriptor {
            chunk_id: ChunkId::new(0, 0, 0).0,
            region_idx: 0,
            chunk_local_idx: 0,
            section_count: 2,
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
            checksum: crate::checksum::compute(&payload_a),
            face_off: 0,
        };
        let chunk_b = ChunkDescriptor {
            chunk_id: ChunkId::new(0, 1, 0).0,
            region_idx: 0,
            chunk_local_idx: 1,
            section_count: 2,
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
            checksum: crate::checksum::compute(&payload_b),
            face_off: 0,
        };
        writer.write_chunk_descriptor(0, &chunk_a).unwrap();
        writer.write_chunk_descriptor(1, &chunk_b).unwrap();

        // section descriptors
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
            checksum: 0xCAFEBABE,
            face_off: 0,
        };
        for i in 0..4 {
            writer.write_section_descriptor(i, &sec).unwrap();
        }

        // state entries
        let mut state = StateEntry::default();
        state.set_lifecycle(Lifecycle::CpuResident);
        state.cpu_epoch = 5;
        state.gpu_epoch = 3;
        state.mark_dirty(state_flags::DIRTY_BLOCK);
        writer.write_state_entry(0, &state).unwrap();
        writer.write_state_entry(1, &state).unwrap();

        // payload
        writer
            .write_chunk_payload(header.payload_offset + chunk_a.payload_offset, &payload_a)
            .unwrap();
        writer
            .write_chunk_payload(header.payload_offset + chunk_b.payload_offset, &payload_b)
            .unwrap();

        let buf = writer.finish();

        // 读取并校验
        let reader = CwaReader::open(&buf).expect("open");
        assert_eq!(reader.header().world_seed, header.world_seed);
        assert_eq!(reader.header().chunk_count, 2);

        let r2 = reader.read_region_index(0).unwrap();
        assert_eq!(r2.region_id, region.region_id);
        assert_eq!(r2.chunk_count, 2);

        let ca = reader.read_chunk_descriptor(0).unwrap();
        assert_eq!(ca.chunk_id, chunk_a.chunk_id);
        assert_eq!(ca.epoch, 1);
        assert_eq!(ca.checksum, chunk_a.checksum);

        let cb = reader.read_chunk_descriptor(1).unwrap();
        assert_eq!(cb.chunk_id, chunk_b.chunk_id);
        assert_eq!(cb.state_flags, state_flags::GPU_OWNED);

        let s = reader.read_state_entry(0).unwrap();
        assert_eq!(s.lifecycle(), Lifecycle::CpuResident);
        assert!(s.is_gpu_stale());
        assert!(s.is_dirty());

        let pa = reader.read_chunk_payload(&ca).unwrap();
        assert_eq!(pa, &payload_a[..]);
        let pb = reader.read_chunk_payload(&cb).unwrap();
        assert_eq!(pb, &payload_b[..]);

        // 资源读取
        let biome = reader.read_resource(&ca, ca.biome_off, 64).unwrap();
        assert_eq!(biome.len(), 64);

        // SoA Hot View
        let hv = ChunkDescHotView::from_descriptor(&ca);
        assert_eq!(hv.payload_offset, ca.payload_offset);
        assert_eq!(hv.epoch, ca.epoch);
    }

    #[test]
    fn compressed_roundtrip() {
        // 构造一个 4KB 高熵较低的 payload,zstd 能显著压缩
        let raw_payload: Vec<u8> = (0..4096).map(|i| (i % 7) as u8).collect();

        // 构造 header
        let mut header = Header::default();
        header.region_index_offset = 0x1200;
        header.chunk_desc_offset = header.region_index_offset + 32;
        header.section_desc_offset = header.chunk_desc_offset + 80;
        header.state_table_offset = header.section_desc_offset + 40;
        header.payload_offset = header.state_table_offset + 16;
        header.payload_size = raw_payload.len() as u64;
        // 启用压缩
        header.flags |= header_flags::COMPRESSED_PAYLOAD;

        let mut writer = CwaWriter::new(header.clone());

        // 压缩写入
        let abs_off = header.payload_offset;
        let (comp_size, did_compress) = writer
            .write_chunk_payload_compressed(abs_off, &raw_payload, 3)
            .unwrap();
        assert!(did_compress, "应当压缩");
        assert!(comp_size < raw_payload.len());

        // ChunkDescriptor 记录两个大小
        let chunk_desc = ChunkDescriptor {
            chunk_id: ChunkId::new(0, 0, 0).0,
            region_idx: 0,
            chunk_local_idx: 0,
            section_count: 1,
            payload_offset: 0,
            payload_size_comp: comp_size as u32,
            payload_size_raw: raw_payload.len() as u32,
            block_off: 0,
            biome_off: 0,
            density_off: 0,
            light_off: 0,
            mesh_off: 0,
            metadata_off: 0,
            state_flags: 0,
            epoch: 1,
            version: 1,
            priority_hint: 0,
            checksum: 0,
            face_off: 0,
        };
        writer
            .write_chunk_descriptor(0, &chunk_desc)
            .unwrap();

        let buf = writer.finish();

        // 读取并解压
        let reader = CwaReader::open(&buf).unwrap();
        assert!(reader.is_payload_compressed());

        let cd = reader.read_chunk_descriptor(0).unwrap();
        assert_eq!(cd.payload_size_raw, raw_payload.len() as u32);
        assert_eq!(cd.payload_size_comp, comp_size as u32);

        // read_chunk_payload 返回压缩字节
        let raw = reader.read_chunk_payload(&cd).unwrap();
        assert_eq!(raw.len(), comp_size);

        // read_chunk_payload_decoded 返回解压字节
        let decoded = reader.read_chunk_payload_decoded(&cd).unwrap();
        assert_eq!(decoded, raw_payload);
    }

    #[test]
    fn uncompressed_passthrough() {
        // 默认 Header 不开压缩 flag,read_chunk_payload_decoded 应直接拷贝
        let raw_payload = b"some uncompressed bytes here, just for testing passthrough";

        let mut header = Header::default();
        header.region_index_offset = 0x1200;
        header.chunk_desc_offset = header.region_index_offset + 32;
        header.section_desc_offset = header.chunk_desc_offset + 80;
        header.state_table_offset = header.section_desc_offset + 40;
        header.payload_offset = header.state_table_offset + 16;
        header.payload_size = raw_payload.len() as u64;
        // 不设 COMPRESSED_PAYLOAD

        let mut writer = CwaWriter::new(header.clone());
        writer
            .write_chunk_payload(header.payload_offset, raw_payload)
            .unwrap();

        let chunk_desc = ChunkDescriptor {
            payload_offset: 0,
            payload_size_comp: raw_payload.len() as u32,
            payload_size_raw: raw_payload.len() as u32,
            ..ChunkDescriptor::default()
        };
        writer.write_chunk_descriptor(0, &chunk_desc).unwrap();

        let buf = writer.finish();
        let reader = CwaReader::open(&buf).unwrap();
        assert!(!reader.is_payload_compressed());

        let cd = reader.read_chunk_descriptor(0).unwrap();
        let decoded = reader.read_chunk_payload_decoded(&cd).unwrap();
        assert_eq!(decoded, raw_payload);
    }

    #[test]
    fn write_compressed_short_payload_falls_back() {
        // 短数据应回退到不压缩
        let mut header = Header::default();
        header.region_index_offset = 0x1200;
        header.chunk_desc_offset = header.region_index_offset + 32;
        header.section_desc_offset = header.chunk_desc_offset + 80;
        header.state_table_offset = header.section_desc_offset + 40;
        header.payload_offset = header.state_table_offset + 16;
        header.payload_size = 32;

        let mut writer = CwaWriter::new(header.clone());

        let short = b"short data, less than 32 bytes";
        let (size, did_compress) = writer
            .write_chunk_payload_compressed(header.payload_offset, short, 3)
            .unwrap();
        assert!(!did_compress);
        assert_eq!(size, short.len());
    }
}
