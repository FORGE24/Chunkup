package cn.sanrolnet.chunkup.client.mixin.sodium;

import cn.sanrolnet.chunkup.client.render.SectionBuildCache;
import cn.sanrolnet.chunkup.client.sodium.LayeredSectionPolicy;
import cn.sanrolnet.chunkup.render.SectionKey;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkUpdateType;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;

@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class RenderSectionManagerMixin {
	@Shadow
	@Final
	private Map<ChunkUpdateType, ArrayDeque<RenderSection>> rebuildLists;

	@Inject(method = "onSectionRemoved", at = @At("HEAD"), remap = false)
	private void chunkup$invalidateCache(int x, int y, int z, CallbackInfo ci) {
		SectionBuildCache.INSTANCE.invalidate(new SectionKey(x, y, z));
	}

	/** 同一队列内优先 mesh 更高 Y 的 section（地表优先）。 */
	@Inject(method = "submitRebuildTasks", at = @At("HEAD"), remap = false)
	private void chunkup$sortRebuildQueueByHeight(
			ChunkJobCollector collector,
			ChunkUpdateType type,
			CallbackInfo ci
	) {
		if (!LayeredSectionPolicy.getEnabled()) {
			return;
		}
		ArrayDeque<RenderSection> queue = this.rebuildLists.get(type);
		if (queue.size() <= 1) {
			return;
		}
		ArrayList<RenderSection> sorted = new ArrayList<>(queue);
		sorted.sort(Comparator.comparingInt(RenderSection::getChunkY).reversed());
		queue.clear();
		queue.addAll(sorted);
	}
}
