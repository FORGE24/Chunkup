package cn.sanrolnet.chunkup.client.mixin.sodium;
import cn.sanrolnet.chunkup.client.infection.InfectionCoordinator;
import cn.sanrolnet.chunkup.client.bridge.ClientEngineBridge;
import cn.sanrolnet.chunkup.client.sodium.SectionBlockStateEncoder;
import cn.sanrolnet.chunkup.client.sodium.SectionMeshStats;
import cn.sanrolnet.chunkup.client.sodium.SodiumBuildFactory;
import cn.sanrolnet.chunkup.client.sodium.SodiumIntegration;
import cn.sanrolnet.chunkup.render.SectionBuildPayload;
import cn.sanrolnet.chunkup.render.SectionKind;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderCache;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;

import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Collections;
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public abstract class ChunkBuilderMeshingTaskMixin {
	@Inject(method = "execute", at = @At("HEAD"), cancellable = true)
	private void chunkup$buildSectionMesh(
			ChunkBuildContext buildContext,
			CancellationToken cancellationToken,
			CallbackInfoReturnable<ChunkBuildOutput> cir
	) {
		if (!SodiumIntegration.getUseGpuSectionMeshes()) {
			return;
		}
		if (cancellationToken.isCancelled()) {
			return;
		}
		if (!ClientEngineBridge.INSTANCE.isAvailable()) {
			SectionMeshStats.recordSodiumFallback();
			return;
		}
		ChunkBuilderTaskAccessor task = (ChunkBuilderTaskAccessor) this;
		RenderSection render = task.chunkup$getRender();
		int buildTime = task.chunkup$getBuildTime();
		if (!InfectionCoordinator.allowSodiumForSection(render.getOriginX(), render.getOriginZ())) {
			cir.setReturnValue(new ChunkBuildOutput(
					render, buildTime, null,
					BuiltSectionInfo.EMPTY, Collections.emptyMap()
			));
			cir.cancel();
			return;
		}
		BlockRenderCache cache = buildContext.cache;
		ChunkBuilderMeshingTaskAccess contextAccess = (ChunkBuilderMeshingTaskAccess) this;
		cache.init(contextAccess.chunkup$getRenderContext());
		LevelSlice slice = cache.getWorldSlice();
		int minX = render.getOriginX();
		int minY = render.getOriginY();
		int minZ = render.getOriginZ();
		SectionBlockStateEncoder.Route route = SectionBlockStateEncoder.classify(slice, minX, minY, minZ);
		if (route == SectionBlockStateEncoder.Route.SODIUM_NATIVE) {
			SectionMeshStats.recordSodiumFallback();
			return;
		}
		if (route == SectionBlockStateEncoder.Route.AIR_ONLY) {
			SectionMeshStats.recordAirOnly();
			cir.setReturnValue(new ChunkBuildOutput(
					render, buildTime, null,
					BuiltSectionInfo.EMPTY, Collections.emptyMap()
			));
			return;
		}
		byte[] blockStates = SectionBlockStateEncoder.encode(slice, minX, minY, minZ);
		int sectionX = minX >> 4;
		int sectionY = minY >> 4;
		int sectionZ = minZ >> 4;
		SectionBuildPayload payload = ClientEngineBridge.INSTANCE.onSectionBuild(
				sectionX,
				sectionY,
				sectionZ,
				blockStates
		);
		if (payload == null || !payload.getReady() || payload.getKind() == SectionKind.FLUID_HEAVY) {
			SectionMeshStats.recordRustMiss();
			return;
		}
		SectionMeshStats.recordRustFast();
		cir.setReturnValue(SodiumBuildFactory.build((ChunkBuilderMeshingTask) (Object) this, payload));
	}
}