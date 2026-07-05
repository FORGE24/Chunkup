package cn.sanrolnet.chunkup.client.mixin.sodium;

import cn.sanrolnet.chunkup.client.sodium.ChunkupSectionMesher;
import cn.sanrolnet.chunkup.client.sodium.SodiumIntegration;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import me.jellysquid.mods.sodium.client.util.task.CancellationToken;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

		ChunkBuilderTaskAccessor task = (ChunkBuilderTaskAccessor) this;
		ChunkBuilderMeshingTaskAccess contextAccess = (ChunkBuilderMeshingTaskAccess) this;

		ChunkBuildOutput output = ChunkupSectionMesher.build(
				task.chunkup$getRender(),
				contextAccess.chunkup$getRenderContext(),
				task.chunkup$getBuildTime(),
				buildContext,
				cancellationToken
		);

		if (output != null) {
			cir.setReturnValue(output);
		}
	}
}
