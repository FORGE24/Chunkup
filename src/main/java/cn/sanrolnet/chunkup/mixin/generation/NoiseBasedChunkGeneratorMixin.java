package cn.sanrolnet.chunkup.mixin.generation;

import cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationHooks;
import cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationStage;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureManager;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
	@Inject(method = "doFill", at = @At("TAIL"))
	private void chunkup$afterNoiseFill(
		Blender blender,
		StructureManager structureManager,
		RandomState randomState,
		ChunkAccess chunk,
		int minY,
		int height,
		CallbackInfoReturnable<ChunkAccess> cir
	) {
		ChunkGenerationHooks.dispatch(null, chunk, ChunkGenerationStage.NOISE_FILL);
	}
}
