package cn.sanrolnet.chunkup.client.mixin.sodium;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import me.jellysquid.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = RenderSectionManager.class, remap = false)
public interface RenderSectionManagerAccess {
	@Accessor("lastUpdatedFrame")
	int chunkupGetLastUpdatedFrame();

	@Accessor("builder")
	ChunkBuilder chunkupGetBuilder();

	@Accessor("sectionByPosition")
	Long2ReferenceMap<RenderSection> chunkupGetSectionByPosition();

	@Invoker("createRebuildTask")
	@Nullable
	ChunkBuilderMeshingTask chunkupCreateRebuildTask(RenderSection render, int frame);
}
