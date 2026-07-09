package cn.sanrolnet.chunkup.client.mixin.sodium;

import cn.sanrolnet.chunkup.client.infection.InfectionCoordinator;
import cn.sanrolnet.chunkup.client.bridge.ClientEngineBridge;
import cn.sanrolnet.chunkup.client.sodium.SectionBlockStateEncoder;
import cn.sanrolnet.chunkup.client.sodium.SectionMeshStats;
import cn.sanrolnet.chunkup.client.sodium.SodiumBuildFactory;
import cn.sanrolnet.chunkup.client.sodium.SodiumIntegration;
import cn.sanrolnet.chunkup.render.SectionBuildPayload;
import cn.sanrolnet.chunkup.render.SectionKind;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderCache;
import me.jellysquid.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import me.jellysquid.mods.sodium.client.util.task.CancellationToken;
import me.jellysquid.mods.sodium.client.world.WorldSlice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;

/**
 * 可选 Rust section 快路径；其余一律 fall-through 到 Sodium 原生 meshing。
 * 不再在 worker 上运行完整 Java mesher 副本，避免线程池枯竭与双重 meshing。
 */
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public abstract class ChunkBuilderMeshingTaskMixin {
	@Inject(method = "execute", at = @At("HEAD"), cancellable = true, remap = false)
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
					render,
					BuiltSectionInfo.EMPTY,
					Collections.emptyMap(),
					buildTime
			));
			cir.cancel();
			return;
		}

		BlockRenderCache cache = buildContext.cache;
		ChunkBuilderMeshingTaskAccess contextAccess = (ChunkBuilderMeshingTaskAccess) this;
		cache.init(contextAccess.chunkup$getRenderContext());
		WorldSlice slice = cache.getWorldSlice();

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
					render,
					BuiltSectionInfo.EMPTY,
					Collections.emptyMap(),
					buildTime
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
