package cn.sanrolnet.chunkup.client.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public interface ChunkBuilderMeshingTaskAccess {
	@Accessor("renderContext")
	ChunkRenderContext chunkup$getRenderContext();
}
