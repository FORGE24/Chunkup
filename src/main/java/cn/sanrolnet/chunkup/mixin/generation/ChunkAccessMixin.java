package cn.sanrolnet.chunkup.mixin.generation;

import cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationHooks;
import cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationStage;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkAccess.class)
public abstract class ChunkAccessMixin {
	@Inject(method = "fillBiomesFromNoise", at = @At("TAIL"))
	private void chunkup$afterBiomes(BiomeResolver biomeResolver, Climate.Sampler sampler, CallbackInfo ci) {
		ChunkGenerationHooks.dispatch(null, (ChunkAccess) (Object) this, ChunkGenerationStage.BIOMES);
	}
}
