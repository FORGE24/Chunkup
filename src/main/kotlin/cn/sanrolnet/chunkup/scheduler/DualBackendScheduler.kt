package cn.sanrolnet.chunkup.scheduler

import cn.sanrolnet.chunkup.ChunkupConfig
import cn.sanrolnet.chunkup.bridge.EngineBridge
import cn.sanrolnet.chunkup.minecraft.generation.ChunkDensityFill
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

object DualBackendScheduler {

    private val LOGGER = LoggerFactory.getLogger("chunkup.scheduler.dual")

    enum class TaskStage(val weight: Int, val preferredBackend: String) {
        DENSITY_FILL(100, "gpu"),
        MESH_BUILD(80, "gpu"),
        LIGHT_COMPUTE(60, "gpu"),
        CHUNK_LOAD(50, "either"),
        SURFACE_BUILD(40, "cpu"),
        FEATURE_DECORATE(20, "cpu"),
    }

    enum class DispatchBackend { GPU, CPU, EITHER }

    data class DispatchEntry(
        val chunkX: Int,
        val chunkZ: Int,
        val chunkId: Long,
        val stage: TaskStage,
        val epoch: Long,
        val preferred: DispatchBackend,
        val priorityBoost: Int,
    ) {
        val priorityScore: Int
            get() = stage.weight + priorityBoost
    }

    data class ResultEntry(
        val chunkX: Int,
        val chunkZ: Int,
        val stage: TaskStage,
        val epoch: Long,
        val backend: DispatchBackend,
        val success: Boolean,
    )

    data class CoordinatorStats(
        val gpuDispatched: Long,
        val cpuDispatched: Long,
        val staleDiscarded: Long,
        val cpuStoleGpu: Long,
        val gpuStoleCpu: Long,
        val pendingGpu: Int,
        val pendingCpu: Int,
        val inflightGpu: Int,
        val inflightCpu: Int,
        val completedReady: Int,
    )

    private val lock = ReentrantLock()

    private val pendingGpu = java.util.concurrent.PriorityBlockingQueue<DispatchEntry>(128) { a, b ->
        b.priorityScore.compareTo(a.priorityScore)
    }

    private val pendingCpu = java.util.concurrent.PriorityBlockingQueue<DispatchEntry>(128) { a, b ->
        b.priorityScore.compareTo(a.priorityScore)
    }

    private val inflightGpu = java.util.concurrent.atomic.AtomicInteger(0)
    private val inflightCpu = java.util.concurrent.atomic.AtomicInteger(0)

    private val completedQueue = java.util.concurrent.ConcurrentLinkedQueue<ResultEntry>()

    private val epochTable = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    private val epochCounter = AtomicLong(1)

    @Volatile private var gpuCapacity: Int = 32
    @Volatile private var cpuCapacity: Int = 8
    @Volatile private var stealThreshold: Double = 0.75
    @Volatile private var enabled: Boolean = true

    @Volatile private var engine: EngineBridge? = null

    private val statsGpuDispatched = AtomicLong(0)
    private val statsCpuDispatched = AtomicLong(0)
    private val statsStaleDiscarded = AtomicLong(0)
    private val statsCpuStoleGpu = AtomicLong(0)
    private val statsGpuStoleCpu = AtomicLong(0)

    @JvmStatic
    fun initialize(engine: EngineBridge) {
        this.engine = engine
        this.enabled = ChunkupConfig.gpuCpuCoordination
        this.gpuCapacity = ChunkupConfig.gpuCapacity
        this.cpuCapacity = ChunkupConfig.cpuCapacity
        this.stealThreshold = ChunkupConfig.laneStealThreshold
        LOGGER.info(
            "DualBackendScheduler init: enabled={}, gpuCap={}, cpuCap={}, steal={}",
            enabled, gpuCapacity, cpuCapacity, ChunkupConfig.enableLaneStealing
        )
    }

    @JvmStatic
    fun isEnabled(): Boolean = enabled

    @JvmStatic
    fun setGpuCapacity(cap: Int) {
        gpuCapacity = cap.coerceIn(1, 256)
    }

    @JvmStatic
    fun setCpuCapacity(cap: Int) {
        cpuCapacity = cap.coerceIn(1, 64)
    }

    private fun nextEpoch(): Long = epochCounter.getAndIncrement()

    private fun makeChunkId(chunkX: Int, chunkZ: Int, dim: Int = 0): Long {
        val dimU = (dim.toLong()) and 0xFF
        val xU = (chunkX.toLong()) and 0x0FFFFFFF
        val zU = (chunkZ.toLong()) and 0x0FFFFFFF
        return (dimU shl 56) or (xU shl 28) or zU
    }

    private fun epochKey(chunkId: Long, stage: TaskStage): Long {
        return (chunkId shl 8) or stage.ordinal.toLong()
    }

    fun submit(
        chunkX: Int,
        chunkZ: Int,
        stage: TaskStage,
        priorityBoost: Int = 0,
    ): DispatchEntry {
        val chunkId = makeChunkId(chunkX, chunkZ)
        val epoch = nextEpoch()

        val preferred = when (stage.preferredBackend) {
            "gpu" -> DispatchBackend.GPU
            "cpu" -> DispatchBackend.CPU
            else -> DispatchBackend.EITHER
        }

        val entry = DispatchEntry(
            chunkX = chunkX,
            chunkZ = chunkZ,
            chunkId = chunkId,
            stage = stage,
            epoch = epoch,
            preferred = preferred,
            priorityBoost = priorityBoost,
        )

        epochTable[epochKey(chunkId, stage)] = epoch

        val gpuAvail = gpuCapacity - inflightGpu.get()
        if ((preferred == DispatchBackend.GPU || preferred == DispatchBackend.EITHER) && gpuAvail > 0) {
            pendingGpu.offer(entry)
            return entry
        }

        if (preferred == DispatchBackend.GPU && gpuAvail <= 0) {
            pendingCpu.offer(entry)
            statsCpuStoleGpu.incrementAndGet()
            return entry
        }

        pendingCpu.offer(entry)
        return entry
    }

    data class GpuDispatchBatch(
        val entries: List<DispatchEntry>,
        val chunkXs: IntArray,
        val chunkZs: IntArray,
    )

    fun dispatchGpu(maxBatch: Int): GpuDispatchBatch {
        val batch = mutableListOf<DispatchEntry>()
        val avail = gpuCapacity - inflightGpu.get()
        val take = minOf(maxBatch, avail)

        for (i in 0 until take) {
            val entry = pendingGpu.poll() ?: break
            inflightGpu.incrementAndGet()
            statsGpuDispatched.incrementAndGet()
            batch.add(entry)
        }

        if (batch.isEmpty() && ChunkupConfig.enableLaneStealing) {
            val cpuQueueSize = pendingCpu.size
            if (cpuQueueSize < (gpuCapacity * stealThreshold).toInt()) {
                val stealCount = minOf(maxBatch.coerceAtMost(4), pendingCpu.size)
                for (i in 0 until stealCount) {
                    val entry = pendingCpu.poll() ?: break
                    inflightGpu.incrementAndGet()
                    statsGpuStoleCpu.incrementAndGet()
                    statsGpuDispatched.incrementAndGet()
                    batch.add(entry)
                }
            }
        }

        val chunkXs = IntArray(batch.size) { batch[it].chunkX }
        val chunkZs = IntArray(batch.size) { batch[it].chunkZ }
        return GpuDispatchBatch(batch, chunkXs, chunkZs)
    }

    data class CpuDispatchBatch(
        val entries: List<DispatchEntry>,
        val chunkXs: IntArray,
        val chunkZs: IntArray,
    )

    fun dispatchCpu(maxBatch: Int): CpuDispatchBatch {
        val batch = mutableListOf<DispatchEntry>()
        val avail = cpuCapacity - inflightCpu.get()
        val take = minOf(maxBatch, avail)

        for (i in 0 until take) {
            val entry = pendingCpu.poll() ?: break
            inflightCpu.incrementAndGet()
            statsCpuDispatched.incrementAndGet()
            batch.add(entry)
        }

        if (batch.isEmpty() && ChunkupConfig.enableLaneStealing) {
            val gpuQueueSize = pendingGpu.size
            if (gpuQueueSize < (cpuCapacity * stealThreshold).toInt()) {
                val stealCount = minOf(maxBatch.coerceAtMost(4), pendingGpu.size)
                for (i in 0 until stealCount) {
                    val entry = pendingGpu.poll() ?: break
                    inflightCpu.incrementAndGet()
                    statsCpuStoleGpu.incrementAndGet()
                    statsCpuDispatched.incrementAndGet()
                    batch.add(entry)
                }
            }
        }

        val chunkXs = IntArray(batch.size) { batch[it].chunkX }
        val chunkZs = IntArray(batch.size) { batch[it].chunkZ }
        return CpuDispatchBatch(batch, chunkXs, chunkZs)
    }

    fun complete(
        chunkX: Int,
        chunkZ: Int,
        stage: TaskStage,
        epoch: Long,
        backend: DispatchBackend,
        success: Boolean,
    ) {
        val chunkId = makeChunkId(chunkX, chunkZ)
        val key = epochKey(chunkId, stage)
        val currentEpoch = epochTable[key]

        val isStale = currentEpoch == null || currentEpoch != epoch

        if (isStale) {
            statsStaleDiscarded.incrementAndGet()
            releaseInflight(backend)
            return
        }

        epochTable.remove(key)

        val result = ResultEntry(chunkX, chunkZ, stage, epoch, backend, success)
        completedQueue.offer(result)

        releaseInflight(backend)
    }

    private fun releaseInflight(backend: DispatchBackend) {
        when (backend) {
            DispatchBackend.GPU -> inflightGpu.decrementAndGet().coerceAtLeast(0)
            DispatchBackend.CPU -> inflightCpu.decrementAndGet().coerceAtLeast(0)
            DispatchBackend.EITHER -> inflightCpu.decrementAndGet().coerceAtLeast(0)
        }
    }

    @JvmStatic
    fun collect(maxCount: Int): List<ResultEntry> {
        val results = mutableListOf<ResultEntry>()
        var count = 0
        while (count < maxCount) {
            val entry = completedQueue.poll() ?: break
            results.add(entry)
            count++
        }
        return results
    }

    @JvmStatic
    fun collectBlocking(maxCount: Int, timeoutMs: Long): List<ResultEntry> {
        val results = collect(maxCount)
        if (results.isNotEmpty()) return results

        val deadline = System.currentTimeMillis() + timeoutMs
        while (results.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(1)
            val polled = collect(maxCount)
            if (polled.isNotEmpty()) return polled
        }
        return results
    }

    @JvmStatic
    fun invalidate(chunkX: Int, chunkZ: Int, stage: TaskStage) {
        val chunkId = makeChunkId(chunkX, chunkZ)
        epochTable.remove(epochKey(chunkId, stage))
    }

    @JvmStatic
    fun pendingGpuCount(): Int = pendingGpu.size

    @JvmStatic
    fun pendingCpuCount(): Int = pendingCpu.size

    @JvmStatic
    fun inflightGpuCount(): Int = inflightGpu.get()

    @JvmStatic
    fun inflightCpuCount(): Int = inflightCpu.get()

    @JvmStatic
    fun completedPendingCount(): Int = completedQueue.size

    @JvmStatic
    fun stats(): CoordinatorStats {
        return CoordinatorStats(
            gpuDispatched = statsGpuDispatched.get(),
            cpuDispatched = statsCpuDispatched.get(),
            staleDiscarded = statsStaleDiscarded.get(),
            cpuStoleGpu = statsCpuStoleGpu.get(),
            gpuStoleCpu = statsGpuStoleCpu.get(),
            pendingGpu = pendingGpu.size,
            pendingCpu = pendingCpu.size,
            inflightGpu = inflightGpu.get(),
            inflightCpu = inflightCpu.get(),
            completedReady = completedQueue.size,
        )
    }

    @JvmStatic
    fun drainAll(): List<DispatchEntry> {
        val results = mutableListOf<DispatchEntry>()
        while (true) {
            val entry = pendingGpu.poll() ?: break
            results.add(entry)
        }
        while (true) {
            val entry = pendingCpu.poll() ?: break
            results.add(entry)
        }
        epochTable.clear()
        return results
    }

    @JvmStatic
    fun shutdown() {
        drainAll()
        inflightGpu.set(0)
        inflightCpu.set(0)
    }

    fun executeGpuDensityBatch(
        engine: EngineBridge,
        batch: GpuDispatchBatch,
        minY: Int,
        height: Int,
        worldSeed: Long,
    ): List<ChunkDensityFill?> {
        if (batch.entries.isEmpty()) return emptyList()

        val result = engine.generateChunkDensityBatch(
            batch.chunkXs,
            batch.chunkZs,
            minY,
            height,
            worldSeed,
        )

        val results = result ?: batch.entries.map { null }

        for (i in batch.entries.indices) {
            val entry = batch.entries[i]
            val success = results[i] != null
            complete(
                entry.chunkX,
                entry.chunkZ,
                entry.stage,
                entry.epoch,
                DispatchBackend.GPU,
                success,
            )
        }

        return results
    }

    fun executeCpuDensityBatch(
        engine: EngineBridge,
        batch: CpuDispatchBatch,
        minY: Int,
        height: Int,
        worldSeed: Long,
    ): List<ChunkDensityFill?> {
        if (batch.entries.isEmpty()) return emptyList()

        val results = mutableListOf<ChunkDensityFill?>()
        for (i in batch.entries.indices) {
            val entry = batch.entries[i]
            val fill = engine.generateChunkDensity(
                entry.chunkX,
                entry.chunkZ,
                minY,
                height,
                worldSeed,
            )
            results.add(fill)
            val success = fill != null
            complete(
                entry.chunkX,
                entry.chunkZ,
                entry.stage,
                entry.epoch,
                DispatchBackend.CPU,
                success,
            )
        }

        return results
    }

    fun processTick(
        engine: EngineBridge,
        minY: Int,
        height: Int,
        worldSeed: Long,
    ): List<ResultEntry> {
        if (!enabled || engine == null) return emptyList()

        val batchSize = ChunkupConfig.coordinationBatchSize
        val gpuBatch = dispatchGpu(batchSize)
        if (gpuBatch.entries.isNotEmpty()) {
            executeGpuDensityBatch(engine, gpuBatch, minY, height, worldSeed)
        }

        val cpuBatch = dispatchCpu(batchSize)
        if (cpuBatch.entries.isNotEmpty()) {
            executeCpuDensityBatch(engine, cpuBatch, minY, height, worldSeed)
        }

        return collect(batchSize)
    }
}
