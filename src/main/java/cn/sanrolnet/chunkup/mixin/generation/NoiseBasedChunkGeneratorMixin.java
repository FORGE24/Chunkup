package cn.sanrolnet.chunkup.mixin.generation;
import cn.sanrolnet.chunkup.minecraft.generation.ChunkDensityGeneration;
import cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationHooks;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.concurrent.CompletableFuture;
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
	@Inject(method = "fillFromNoise", at = @At("HEAD"), cancellable = true)
	private void chunkup$replaceNoiseFill(
			Blender blender,
			RandomState randomState,
			StructureManager structureManager,
			ChunkAccess chunk,
			CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
	) {
		/* 同步路径：噪声阶段必须在 worker 线程完成 sections 写入，
		 * 之后 vanilla 才能推进到 SURFACE/FEATURES，保证阶段时序正确。
		 * （异步回退：之前的 submit/future.complete 在 chunkup-density-batcher 线程写 sections
		 *  会破坏 ChunkStatus 阶段顺序，导致 spawn 计算提前到 density apply 之前，
		 *  玩家出生在空气或树冠内部，相机进入叶块出现"大头"视觉 bug。） */
		if (ChunkDensityGeneration.tryReplaceNoiseFill(blender, chunk, 0, 0)) {
			cir.setReturnValue(CompletableFuture.completedFuture(chunk));
			cir.cancel();
		}
	}
	@Inject(
		method = "buildSurface(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void chunkup$replaceBuildSurface(
			WorldGenRegion region,
			StructureManager structureManager,
			RandomState randomState,
			ChunkAccess chunk,
			CallbackInfo ci
	) {
		if (cn.sanrolnet.chunkup.minecraft.generation.ChunkSurfaceGeneration.tryReplaceBuildSurface(
				region,
				chunk
		)) {
			ci.cancel();
		}
	}
	@Inject(
		method = "buildSurface(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;)V",
		at = @At("TAIL")
	)
	private void chunkup$afterBuildSurface(
			WorldGenRegion region,
			StructureManager structureManager,
			RandomState randomState,
			ChunkAccess chunk,
			CallbackInfo ci
	) {
		ChunkGenerationHooks.dispatch(
				region.getLevel(),
				chunk,
				cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationStage.SURFACE
		);
	}
}