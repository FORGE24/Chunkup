package cn.sanrolnet.chunkup.client.mixin.sodium;

import cn.sanrolnet.chunkup.client.infection.InfectionCoordinator;
import cn.sanrolnet.chunkup.client.sodium.LayeredSectionPolicy;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.lists.VisibleChunkCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VisibleChunkCollector.class, remap = false)
public abstract class VisibleChunkCollectorMixin {
	@Inject(method = "addToRebuildLists", at = @At("HEAD"), cancellable = true, remap = false)
	private void chunkup$deferDeepSections(RenderSection section, CallbackInfo ci) {
		if (!InfectionCoordinator.allowSodiumForSection(section.getOriginX(), section.getOriginZ())) {
			ci.cancel();
			return;
		}
		if (!LayeredSectionPolicy.allowSectionMesh(section.getChunkY())) {
			ci.cancel();
		}
	}
}
