package cn.sanrolnet.chunkup.client.mixin.sodium;



import cn.sanrolnet.chunkup.client.pipeline.SectionLoadPreRenderer;

import cn.sanrolnet.chunkup.client.render.SectionBuildCache;

import cn.sanrolnet.chunkup.client.sodium.LayeredSectionPolicy;

import cn.sanrolnet.chunkup.render.SectionKey;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;

import me.jellysquid.mods.sodium.client.render.chunk.ChunkUpdateType;

import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;

import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;

import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;

import me.jellysquid.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;

import me.jellysquid.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector;

import me.jellysquid.mods.sodium.client.render.chunk.compile.executor.ChunkJobResult;

import net.minecraft.core.SectionPos;

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

import java.util.concurrent.ConcurrentLinkedDeque;



@Mixin(value = RenderSectionManager.class, remap = false)

public abstract class RenderSectionManagerMixin {

	@Shadow

	@Final

	private Map<ChunkUpdateType, ArrayDeque<RenderSection>> rebuildLists;



	@Shadow

	@Final

	private Long2ReferenceMap<RenderSection> sectionByPosition;



	@Shadow

	@Final

	private ConcurrentLinkedDeque<ChunkJobResult<ChunkBuildOutput>> buildResults;



	@Shadow

	@Final

	private ChunkBuilder builder;



	@Inject(method = "onSectionRemoved", at = @At("HEAD"), remap = false)

	private void chunkup$invalidateCache(int x, int y, int z, CallbackInfo ci) {

		SectionBuildCache.INSTANCE.invalidate(new SectionKey(x, y, z));

	}



	@Inject(method = "onSectionAdded", at = @At("TAIL"), remap = false)

	private void chunkup$queuePreRender(int x, int y, int z, CallbackInfo ci) {

		RenderSection section = this.sectionByPosition.get(SectionPos.asLong(x, y, z));

		if (section != null && section.getPendingUpdate() != null) {

			SectionLoadPreRenderer.onSectionAdded(x, y, z);

		}

	}



	/**

	 * 在 Sodium 可见性遍历与 rebuild 队列消费之前，按距离优先 flush 预渲染队列。

	 * budget 取 min(preRender.budget, builder.schedulingBudget)，避免 worker 枯竭。

	 */

	@Inject(method = "updateChunks", at = @At("HEAD"), remap = false)

	private void chunkup$flushPreRender(boolean updateImmediately, CallbackInfo ci) {

		if (!SectionLoadPreRenderer.getEnabled()) {

			return;

		}

		int budget = Math.min(

				cn.sanrolnet.chunkup.ChunkupConfig.getPreRenderBudgetPerFrame(),

				this.builder.getSchedulingBudget()

		);

		if (budget <= 0) {

			return;

		}

		ChunkJobCollector preRenderCollector = new ChunkJobCollector(budget, this.buildResults::add);

		SectionLoadPreRenderer.flush((RenderSectionManagerAccess) this, preRenderCollector);

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


