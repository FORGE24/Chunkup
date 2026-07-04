package cn.sanrolnet.chunkup.client.mixin.sodium;

import cn.sanrolnet.chunkup.client.mixin.sodium.ChunkBuilderTaskAccessor;
import cn.sanrolnet.chunkup.client.render.SectionBuildCache;
import cn.sanrolnet.chunkup.client.sodium.SodiumBuildFactory;
import cn.sanrolnet.chunkup.render.SectionBuildPayload;
import cn.sanrolnet.chunkup.render.SectionKey;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public abstract class ChunkBuilderMeshingTaskMixin {
	@Inject(method = "execute", at = @At("HEAD"), cancellable = true, remap = false)
	private void chunkup$useGpuCache(
			ChunkBuildContext buildContext,
			CancellationToken cancellationToken,
			CallbackInfoReturnable<ChunkBuildOutput> cir
	) {
		if (cancellationToken.isCancelled()) {
			return;
		}

		RenderSection section = ((ChunkBuilderTaskAccessor) this).chunkup$getRender();
		SectionKey key = new SectionKey(section.getOriginX(), section.getOriginY(), section.getOriginZ());
		SectionBuildPayload payload = SectionBuildCache.INSTANCE.get(key);
		if (payload == null || !payload.getReady()) {
			return;
		}

		cir.setReturnValue(SodiumBuildFactory.build((ChunkBuilderMeshingTask) (Object) this, payload));
	}
}
