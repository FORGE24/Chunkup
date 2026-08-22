package cn.sanrolnet.chunkup.client.infection

import cn.sanrolnet.chunkup.client.bridge.ClientEngineBridge

import com.mojang.blaze3d.pipeline.RenderCall
import com.mojang.blaze3d.systems.RenderSystem
import org.lwjgl.opengl.GL43
import org.lwjgl.system.MemoryStack
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

object InfectionBatchPackagerPhase2 {
    private const val VERTEX_STRIDE = 20
    private const val BLOCKS_PER_SECTION = 4096

    private val LOGGER = LoggerFactory.getLogger("chunkup.infection.batch.phase2")

    fun submitPackBatch(
        blockStates: ByteArray,
        sectionCount: Int,
        status: GpuBatchStatus
    ): GpuBatchStatus? {
        if (!ClientEngineBridge.interopAvailable()) {
            LOGGER.warn("GPU Direct Rendering unavailable; caller must fall back to CPU mesher")
            status.failed = true
            return null
        }

        val expectedBytes = sectionCount * BLOCKS_PER_SECTION
        if (blockStates.size != expectedBytes) {
            LOGGER.error("block_states size mismatch: {} vs {}", blockStates.size, expectedBytes)
            status.failed = true
            return null
        }

        val devicePtr = ClientEngineBridge.interopUploadBlockStates(blockStates)
        if (devicePtr == 0L) {
            LOGGER.error("VRAM block_states upload failed")
            status.failed = true
            return null
        }

        val counts = ClientEngineBridge.interopMeshCountOnlyDevice(devicePtr, sectionCount) ?: run {
            ClientEngineBridge.interopFreeBlockStates(devicePtr)
            LOGGER.error("Phase A mesh_count_only(VRAM) returned null")
            status.failed = true
            return null
        }

        val offsetTable = IntArray(sectionCount + 1)
        var acc = 0
        for (i in 0 until sectionCount) {
            offsetTable[i] = acc
            acc += counts[i]
        }
        offsetTable[sectionCount] = acc
        val totalVerts = acc

        if (totalVerts == 0) {
            LOGGER.warn("Batch has 0 visible vertices; freeing VRAM")
            ClientEngineBridge.interopFreeBlockStates(devicePtr)
            status.ready.set(true)
            status.vertexCount = 0
            return status
        }

        status.vertexCount = totalVerts
        status.sectionCount = sectionCount
        status.offsetTable = offsetTable
        val vboBytes = totalVerts.toLong() * VERTEX_STRIDE

        val cmds = IntArray(sectionCount * 4)

        RenderSystem.recordRenderCall(RenderCall {
            try {
                val buffers = IntArray(2)
                GL43.glGenBuffers(buffers)
                status.vboId = buffers[0]
                status.indirectBufferId = buffers[1]

                GL43.glBindBuffer(GL43.GL_ARRAY_BUFFER, status.vboId)
                GL43.glBufferData(GL43.GL_ARRAY_BUFFER, vboBytes, GL43.GL_STATIC_DRAW)
                GL43.glBindBuffer(GL43.GL_ARRAY_BUFFER, 0)

                val regOk = ClientEngineBridge.interopGlRegister(status.vboId)
                if (!regOk) {
                    LOGGER.error("interopGlRegister(vbo_id={}) failed", status.vboId)
                    GL43.glDeleteBuffers(intArrayOf(status.vboId, status.indirectBufferId))
                    status.failed = true
                    ClientEngineBridge.interopFreeBlockStates(devicePtr)
                    status.ready.set(true)
                    return@RenderCall
                }

                val rc = ClientEngineBridge.interopMeshToVboDevice(
                    devicePtr, sectionCount, VERTEX_STRIDE, status.vboId, offsetTable, cmds
                )

                ClientEngineBridge.interopFreeBlockStates(devicePtr)

                if (rc != 0) {
                    LOGGER.error("Phase B mesh-to-VBO rc={} for vbo_id={}", rc, status.vboId)
                    ClientEngineBridge.interopGlUnregister(status.vboId)
                    GL43.glDeleteBuffers(intArrayOf(status.vboId, status.indirectBufferId))
                    status.failed = true
                    status.ready.set(true)
                    return@RenderCall
                }

                GL43.glBindBuffer(GL43.GL_DRAW_INDIRECT_BUFFER, status.indirectBufferId)
                MemoryStack.stackPush().use { stack ->
                    val cmdBuf = stack.mallocInt(cmds.size)
                    cmdBuf.put(cmds)
                    cmdBuf.flip()
                    GL43.glBufferData(GL43.GL_DRAW_INDIRECT_BUFFER, cmdBuf, GL43.GL_STATIC_READ)
                }
                GL43.glBindBuffer(GL43.GL_DRAW_INDIRECT_BUFFER, 0)

                status.ready.set(true)
            } catch (t: Throwable) {
                LOGGER.error("Phase B render-thread block crashed", t)
                if (status.vboId != 0 || status.indirectBufferId != 0) {
                    if (status.vboId != 0) ClientEngineBridge.interopGlUnregister(status.vboId)
                    GL43.glDeleteBuffers(intArrayOf(status.vboId, status.indirectBufferId))
                }
                ClientEngineBridge.interopFreeBlockStates(devicePtr)
                status.failed = true
                status.ready.set(true)
            }
        })

        return status
    }

    fun renderBatch(status: GpuBatchStatus) {
        if (!status.ready.get() || status.failed) {
            LOGGER.warn("renderBatch called before ready or after failure; skipping")
            return
        }
        if (status.vertexCount == 0 || status.vboId == 0) return
        RenderSystem.recordRenderCall(RenderCall {
            GL43.glBindBuffer(GL43.GL_ARRAY_BUFFER, status.vboId)
            GL43.glBindBuffer(GL43.GL_DRAW_INDIRECT_BUFFER, status.indirectBufferId)
            GL43.glMultiDrawArraysIndirect(GL43.GL_TRIANGLES, 0L, status.sectionCount, 0)
            GL43.glBindBuffer(GL43.GL_DRAW_INDIRECT_BUFFER, 0)
            GL43.glBindBuffer(GL43.GL_ARRAY_BUFFER, 0)
        })
    }

    fun release(status: GpuBatchStatus) {
        if (status.vboId == 0 && status.indirectBufferId == 0) return
        RenderSystem.recordRenderCall(RenderCall {
            if (status.vboId != 0) ClientEngineBridge.interopGlUnregister(status.vboId)
            GL43.glDeleteBuffers(intArrayOf(status.vboId, status.indirectBufferId))
            status.vboId = 0
            status.indirectBufferId = 0
        })
    }
}

class GpuBatchStatus {
    @Volatile var vboId: Int = 0
    @Volatile var indirectBufferId: Int = 0
    @Volatile var vertexCount: Int = 0
    @Volatile var sectionCount: Int = 0
    @Volatile var offsetTable: IntArray = IntArray(0)
    @Volatile var failed: Boolean = false
    val ready: AtomicBoolean = AtomicBoolean(false)
}