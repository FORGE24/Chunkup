package cn.sanrolnet.chunkup.mixin.generation;
import cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationWorldContext;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
	@Shadow
	@Final
	ServerLevel level;
	@Inject(method = "scheduleGenerationTask", at = @At("HEAD"))
	private void chunkup$pushWorld(ChunkStatus status, ChunkPos pos, CallbackInfoReturnable<?> cir) {
		ChunkGenerationWorldContext.push(this.level);
	}
	@Inject(method = "scheduleGenerationTask", at = @At("RETURN"))
	private void chunkup$popWorld(ChunkStatus status, ChunkPos pos, CallbackInfoReturnable<?> cir) {
		ChunkGenerationWorldContext.pop();
	}
}