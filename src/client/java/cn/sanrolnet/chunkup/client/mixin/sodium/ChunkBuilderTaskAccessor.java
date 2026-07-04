package cn.sanrolnet.chunkup.client.mixin.sodium;

import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public interface ChunkBuilderTaskAccessor {
	@Accessor("render")
	RenderSection chunkup$getRender();

	@Accessor("buildTime")
	int chunkup$getBuildTime();
}
