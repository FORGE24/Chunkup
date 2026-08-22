package cn.sanrolnet.chunkup.client.sodium;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderCache;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.FluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import java.util.Map;
public final class ChunkupSectionMesher {
	private ChunkupSectionMesher() {
	}
	public static ChunkBuildOutput build(
			RenderSection render,
			ChunkRenderContext renderContext,
			int buildTime,
			ChunkBuildContext buildContext,
			CancellationToken cancellationToken
	) {
		BuiltSectionInfo.Builder renderData = new BuiltSectionInfo.Builder();
		VisGraph occluder = new VisGraph();
		ChunkBuildBuffers buffers = buildContext.buffers;
		buffers.init(renderData, render.getSectionIndex());
		BlockRenderCache cache = buildContext.cache;
		cache.init(renderContext);
		LevelSlice slice = cache.getWorldSlice();
		int minX = render.getOriginX();
		int minY = render.getOriginY();
		int minZ = render.getOriginZ();
		int maxX = minX + 16;
		int maxY = minY + 16;
		int maxZ = minZ + 16;
		BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos(minX, minY, minZ);
		BlockPos.MutableBlockPos modelOffset = new BlockPos.MutableBlockPos();
		TranslucentGeometryCollector collector = new TranslucentGeometryCollector(render.getPosition(), SortBehavior.OFF);
		BlockRenderContext context = new BlockRenderContext(slice, collector);
		try {
			for (int y = minY; y < maxY; y++) {
				if (cancellationToken.isCancelled()) {
					return null;
				}
				for (int z = minZ; z < maxZ; z++) {
					for (int x = minX; x < maxX; x++) {
						BlockState blockState = slice.getBlockState(x, y, z);
						if (blockState.isAir() && !blockState.hasBlockEntity()) {
							continue;
						}
						blockPos.set(x, y, z);
						modelOffset.set(x & 15, y & 15, z & 15);
						if (blockState.getRenderShape() == RenderShape.MODEL) {
							BakedModel model = cache.getBlockModels().getBlockModel(blockState);
							long seed = blockState.getSeed(blockPos);
							context.update(blockPos, modelOffset, blockState, model, seed);
							cache.getBlockRenderer().renderModel(model, blockState, blockPos, modelOffset);
						}
						FluidState fluidState = blockState.getFluidState();
						if (!fluidState.isEmpty()) {
							cache.getFluidRenderer().render(slice, blockState, fluidState, blockPos, modelOffset, collector, buffers);
						}
						if (blockState.hasBlockEntity()) {
							BlockEntity entity = slice.getBlockEntity(blockPos);
							if (entity != null) {
								BlockEntityRenderer<BlockEntity> renderer =
										Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(entity);
								if (renderer != null) {
									renderData.addBlockEntity(entity, !renderer.shouldRenderOffScreen(entity));
								}
							}
						}
						if (blockState.isSolidRender(slice, blockPos)) {
							occluder.setOpaque(blockPos);
						}
					}
				}
			}
		} catch (ReportedException ex) {
			throw fillCrashInfo(ex.getReport(), slice, blockPos, render, renderContext);
		} catch (Exception ex) {
			throw fillCrashInfo(CrashReport.forThrowable(ex, "Encountered exception while building chunk meshes"),
					slice, blockPos, render, renderContext);
		}
		Map<TerrainRenderPass, BuiltSectionMeshParts> meshes = new Reference2ReferenceOpenHashMap<>();
		for (TerrainRenderPass pass : DefaultTerrainRenderPasses.ALL) {
			BuiltSectionMeshParts mesh = buffers.createMesh(pass, 0, false, false);
			if (mesh != null) {
				meshes.put(pass, mesh);
				renderData.addRenderPass(pass);
			}
		}
		renderData.setOcclusionData(occluder.resolve());
		return new ChunkBuildOutput(render, buildTime, null, renderData.build(), meshes);
	}
	private static ReportedException fillCrashInfo(
			CrashReport report,
			LevelSlice slice,
			BlockPos pos,
			RenderSection render,
			ChunkRenderContext renderContext
	) {
		CrashReportCategory section = report.addCategory("Block being rendered");
		BlockState state = null;
		try {
			state = slice.getBlockState(pos);
		} catch (Exception ignored) {
		}
		CrashReportCategory.populateBlockDetails(section, slice, pos, state);
		section.setDetail("Chunk section", render);
		if (renderContext != null) {
			section.setDetail("Render context volume", renderContext.getVolume());
		}
		return new ReportedException(report);
	}
}