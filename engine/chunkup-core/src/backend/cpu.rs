use chunkup_cpu::CpuBackend;

pub fn probe() -> bool {
    CpuBackend::probe()
}
