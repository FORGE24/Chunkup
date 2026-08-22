package cn.sanrolnet.chunkup.client.mixin.sodium;

import cn.sanrolnet.chunkup.client.pipeline.SectionLoadPreRenderer;
import cn.sanrolnet.chunkup.client.render.SectionBuildCache;
import cn.sanrolnet.chunkup.client.sodium.LayeredSectionPolicy;
import cn.sanrolnet.chunkup.render.SectionKey;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobResult;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.concurrent.ConcurrentLinkedDeque;

@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class RenderSectionManagerMixin {

	@Shadow
	@Final
	private Long2ReferenceMap<RenderSection> sectionByPosition;

	@Shadow
	@Final
	private ConcurrentLinkedDeque<ChunkJobResult<?>> buildResults;

	@Shadow
	@Final
	private ChunkBuilder builder;

	@Inject(method = "onSectionRemoved", at = @At("HEAD"))
	private void chunkup$invalidateCache(int x, int y, int z, CallbackInfo ci) {
		SectionBuildCache.INSTANCE.invalidate(new SectionKey(x, y, z));
	}

	@Inject(method = "onSectionAdded", at = @At("TAIL"))
	private void chunkup$queuePreRender(int x, int y, int z, CallbackInfo ci) {
		RenderSection section = this.sectionByPosition.get(SectionPos.asLong(x, y, z));
		if (section != null && section.getPendingUpdate() != 0) {
			SectionLoadPreRenderer.onSectionAdded(x, y, z);
		}
	}

	@Inject(method = "updateChunks", at = @At("HEAD"))
	private void chunkup$flushPreRender(boolean updateImmediately, CallbackInfo ci) {
		if (!SectionLoadPreRenderer.getEnabled()) {
			return;
		}
		int budget = cn.sanrolnet.chunkup.ChunkupConfig.getPreRenderBudgetPerFrame();
		if (budget <= 0) {
			return;
		}
		ChunkJobCollector preRenderCollector = new ChunkJobCollector(budget, this.buildResults::add);
		SectionLoadPreRenderer.flush((RenderSectionManagerAccess) this, preRenderCollector);
	}
}