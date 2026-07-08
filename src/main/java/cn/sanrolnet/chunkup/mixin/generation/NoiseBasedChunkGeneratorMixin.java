package cn.sanrolnet.chunkup.mixin.generation;

import cn.sanrolnet.chunkup.minecraft.generation.ChunkDensityGeneration;
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

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
	@Inject(method = "doFill", at = @At("HEAD"), cancellable = true)
	private void chunkup$replaceNoiseFill(
		Blender blender,
		StructureManager structureManager,
		RandomState randomState,
		ChunkAccess chunk,
		int minY,
		int height,
		CallbackInfoReturnable<ChunkAccess> cir
	) {
		if (ChunkDensityGeneration.tryReplaceNoiseFill(chunk, minY, height)) {
			cir.setReturnValue(chunk);
			cir.cancel();
		}
	}

	@Inject(method = "buildSurface", at = @At("HEAD"), cancellable = true)
	private void chunkup$replaceBuildSurface(
		WorldGenRegion region,
		StructureManager structureManager,
		RandomState randomState,
		ChunkAccess chunk,
		CallbackInfo ci
	) {
		if (cn.sanrolnet.chunkup.minecraft.generation.ChunkSurfaceGeneration.tryReplaceBuildSurface(
			chunk,
			region.getLevel()
		)) {
			ci.cancel();
		}
	}

	@Inject(method = "buildSurface", at = @At("TAIL"))
	private void chunkup$afterBuildSurface(
		WorldGenRegion region,
		StructureManager structureManager,
		RandomState randomState,
		ChunkAccess chunk,
		CallbackInfo ci
	) {
		cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationHooks.dispatch(
			region.getLevel(),
			chunk,
			cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationStage.SURFACE
		);
	}
}
