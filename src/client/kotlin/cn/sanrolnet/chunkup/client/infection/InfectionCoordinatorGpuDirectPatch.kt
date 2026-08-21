package cn.sanrolnet.chunkup.client.infection

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

object InfectionCoordinatorGpuDirectPatch {
    private val LOGGER = LoggerFactory.getLogger("chunkup.infection.coordinator.gpuDirectRender")

    @JvmStatic
    fun tryAdvanceGpuDirectRender(
        stage: InfectionStage,
        batchStatus: GpuBatchStatus?
    ): InfectionStage {
        if (stage !== InfectionStage.GPU_DIRECT_RENDER_PENDING) {
            return stage
        }

        if (batchStatus == null) {
            LOGGER.warn("GPU_DIRECT_RENDER_PENDING with null batchStatus — advancing to INFECTED (fallback)")
            return InfectionStage.INFECTED
        }

        if (batchStatus.failed) {
            LOGGER.warn("GPU_DIRECT_RENDER_PENDING batch failed — advancing to INFECTED (recovery)")
            return InfectionStage.INFECTED
        }

        return if (batchStatus.ready.get()) {
            InfectionStage.INFECTED
        } else {
            InfectionStage.GPU_DIRECT_RENDER_PENDING
        }
    }

    @JvmStatic
    fun tryAdvanceGpuDirectRender(stage: InfectionStage, readyFlag: AtomicBoolean?): InfectionStage {
        if (stage !== InfectionStage.GPU_DIRECT_RENDER_PENDING) return stage
        if (readyFlag == null) {
            LOGGER.warn("GPU_DIRECT_RENDER_PENDING with null readyFlag — advancing to INFECTED (fallback)")
            return InfectionStage.INFECTED
        }
        return if (readyFlag.get()) InfectionStage.INFECTED else InfectionStage.GPU_DIRECT_RENDER_PENDING
    }
}

enum class InfectionStage {
    INITIAL,
    ARCHIVED,
    CPU_LOADING,
    CPU_RESIDENT,
    GPU_STAGING,
    GPU_RESIDENT,
    PACKING,
    GPU_DIRECT_RENDER_PENDING,
    INFECTED
}