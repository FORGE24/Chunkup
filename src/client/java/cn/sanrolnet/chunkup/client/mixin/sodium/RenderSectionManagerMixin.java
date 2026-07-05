package cn.sanrolnet.chunkup.client.mixin.sodium;

import cn.sanrolnet.chunkup.client.render.SectionBuildCache;
import cn.sanrolnet.chunkup.render.SectionKey;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderSectionManager.class, remap = false)
public class RenderSectionManagerMixin {
	@Inject(method = "onSectionRemoved", at = @At("HEAD"), remap = false)
	private void chunkup$invalidateCache(int x, int y, int z, CallbackInfo ci) {
		SectionBuildCache.INSTANCE.invalidate(new SectionKey(x, y, z));
	}
}
