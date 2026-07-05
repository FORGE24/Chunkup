package cn.sanrolnet.chunkup.mixin.generation;

import cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationHooks;
import cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationStage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {
	@Inject(method = "applyBiomeDecoration", at = @At("TAIL"))
	private void chunkup$afterFeatures(
		WorldGenLevel level,
		ChunkAccess chunk,
		StructureManager structureManager,
		CallbackInfo ci
	) {
		ServerLevel serverLevel = level instanceof ServerLevel server ? server : null;
		ChunkGenerationHooks.dispatch(serverLevel, chunk, ChunkGenerationStage.FEATURES);
	}
}
