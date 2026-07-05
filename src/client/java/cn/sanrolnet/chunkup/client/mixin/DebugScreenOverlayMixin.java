package cn.sanrolnet.chunkup.client.mixin;

import cn.sanrolnet.chunkup.client.debug.ChunkupF3Debug;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayMixin {
	@Inject(method = "getGameInformation", at = @At("RETURN"))
	private void chunkup$appendGameInfo(CallbackInfoReturnable<List<String>> cir) {
		for (String line : ChunkupF3Debug.lines()) {
			cir.getReturnValue().add(line);
		}
	}
}
