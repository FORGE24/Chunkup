package cn.sanrolnet.chunkup.client.sodium;

import cn.sanrolnet.chunkup.client.mixin.sodium.ChunkBuilderTaskAccessor;
import cn.sanrolnet.chunkup.render.SectionBuildPayload;
import cn.sanrolnet.chunkup.render.SectionKind;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;

import java.util.Collections;
import java.util.Map;

public final class SodiumBuildFactory {
	private SodiumBuildFactory() {
	}

	public static ChunkBuildOutput build(ChunkBuilderMeshingTask task, SectionBuildPayload payload) {
		RenderSection section = ((ChunkBuilderTaskAccessor) task).chunkup$getRender();
		int buildTime = ((ChunkBuilderTaskAccessor) task).chunkup$getSubmitTime();

		if (payload.getKind() == SectionKind.AIR_ONLY) {
			return new ChunkBuildOutput(section, buildTime, null, BuiltSectionInfo.EMPTY, Collections.emptyMap());
		}

		BuiltSectionInfo.Builder infoBuilder = new BuiltSectionInfo.Builder();
		infoBuilder.setOcclusionData(OcclusionBridge.toVisibilitySets(payload.getVisibilityData()));

		Map<TerrainRenderPass, BuiltSectionMeshParts> meshes = new Reference2ReferenceOpenHashMap<>();
		if (payload.getVertexData() != null && payload.getVertexData().remaining() > 0) {
			NativeBuffer buffer = SodiumNativeBuffers.copy(payload.getVertexData());
			BuiltSectionMeshParts mesh = new BuiltSectionMeshParts(buffer, payload.getVertexSegments());
			meshes.put(DefaultTerrainRenderPasses.SOLID, mesh);
			infoBuilder.addRenderPass(DefaultTerrainRenderPasses.SOLID);
		}

		return new ChunkBuildOutput(section, buildTime, null, infoBuilder.build(), meshes);
	}
}
