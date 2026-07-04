package cn.sanrolnet.chunkup.mixin.generation;

import cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationHooks;
import cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationStage;
import cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationWorldContext;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkStatus;
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

	@Inject(method = "scheduleChunkGeneration", at = @At("HEAD"))
	private void chunkup$pushWorld(ChunkHolder holder, ChunkStatus status, CallbackInfoReturnable<?> cir) {
		ChunkGenerationWorldContext.push(this.level);
	}

	@Inject(method = "scheduleChunkGeneration", at = @At("RETURN"))
	private void chunkup$popWorld(ChunkHolder holder, ChunkStatus status, CallbackInfoReturnable<?> cir) {
		ChunkGenerationWorldContext.pop();
	}

	@Inject(method = "method_17227", at = @At("RETURN"))
	private void chunkup$afterGenerated(
		ChunkHolder holder,
		ChunkAccess protoChunk,
		CallbackInfoReturnable<ChunkAccess> cir
	) {
		ChunkAccess result = cir.getReturnValue();
		if (!(result instanceof LevelChunk levelChunk)) {
			return;
		}

		// ImposterProtoChunk 表示从磁盘加载的包装块，非本次管线新生成。
		boolean newlyGenerated = !(protoChunk instanceof ImposterProtoChunk);
		if (newlyGenerated) {
			ChunkGenerationHooks.dispatch(this.level, levelChunk, ChunkGenerationStage.GENERATED, true);
		}
	}
}
