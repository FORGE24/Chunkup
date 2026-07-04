package cn.sanrolnet.chunkup.mixin.generation;

import cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationHooks;
import cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationStage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {
	@Inject(method = "buildSurface", at = @At("TAIL"))
	private void chunkup$afterBuildSurface(
		WorldGenRegion region,
		StructureManager structureManager,
		RandomState randomState,
		ChunkAccess chunk,
		CallbackInfo ci
	) {
		ChunkGenerationHooks.dispatch(region.getLevel(), chunk, ChunkGenerationStage.SURFACE);
	}

	@Inject(method = "applyBiomeDecoration", at = @At("TAIL"))
	private void chunkup$afterFeatures(
		net.minecraft.world.level.WorldGenLevel level,
		ChunkAccess chunk,
		StructureManager structureManager,
		CallbackInfo ci
	) {
		ServerLevel serverLevel = level instanceof ServerLevel server ? server : null;
		ChunkGenerationHooks.dispatch(serverLevel, chunk, ChunkGenerationStage.FEATURES);
	}
}
