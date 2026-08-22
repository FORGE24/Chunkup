package cn.sanrolnet.chunkup.client.sodium;

import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public final class SectionBlockStateEncoder {
	public enum Route {
		AIR_ONLY,
		RUST_FAST,
		SODIUM_NATIVE,
	}

	private SectionBlockStateEncoder() {
	}

	public static Route classify(LevelSlice slice, int minX, int minY, int minZ) {
		Integer uniformBlockId = null;
		int fluidCount = 0;
		int opaqueCount = 0;
		boolean sawNonUniform = false;

		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					int wx = minX + x;
					int wy = minY + y;
					int wz = minZ + z;
					BlockState state = slice.getBlockState(wx, wy, wz);

					if (state.isAir() && !state.hasBlockEntity()) {
						continue;
					}

					FluidState fluid = state.getFluidState();
					if (!fluid.isEmpty()) {
						fluidCount++;
						continue;
					}

					if (state.getRenderShape() != RenderShape.MODEL) {
						sawNonUniform = true;
						continue;
					}

					pos.set(wx, wy, wz);
					if (!state.isSolidRender(slice, pos)) {
						sawNonUniform = true;
						continue;
					}

					opaqueCount++;
					int blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(state.getBlock());
					if (uniformBlockId == null) {
						uniformBlockId = blockId;
					} else if (uniformBlockId != blockId) {
						sawNonUniform = true;
					}
				}
			}
		}

		if (opaqueCount == 0 && fluidCount == 0) {
			return Route.AIR_ONLY;
		}
		if (fluidCount > opaqueCount || sawNonUniform) {
			return Route.SODIUM_NATIVE;
		}
		if (opaqueCount > 0 && uniformBlockId != null) {
			return Route.RUST_FAST;
		}
		return Route.SODIUM_NATIVE;
	}

	public static byte[] encode(LevelSlice slice, int minX, int minY, int minZ) {
		byte[] states = new byte[4096];
		int index = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					int wx = minX + x;
					int wy = minY + y;
					int wz = minZ + z;
					BlockState state = slice.getBlockState(wx, wy, wz);
					states[index++] = encodeBlock(state, slice, wx, wy, wz, pos);
				}
			}
		}
		return states;
	}

	public static byte encodeBlockState(BlockState state) {
		if (state.isAir() && !state.hasBlockEntity()) {
			return 0;
		}
		if (!state.getFluidState().isEmpty()) {
			return 2;
		}
		if (state.getRenderShape() != RenderShape.MODEL) {
			return 0;
		}
		return state.canOcclude() ? (byte) 1 : 0;
	}

	private static byte encodeBlock(
			BlockState state,
		LevelSlice slice,
			int wx,
			int wy,
			int wz,
			BlockPos.MutableBlockPos pos
	) {
		if (state.isAir() && !state.hasBlockEntity()) {
			return 0;
		}
		if (!state.getFluidState().isEmpty()) {
			return 2;
		}
		if (state.getRenderShape() != RenderShape.MODEL) {
			return 0;
		}
		pos.set(wx, wy, wz);
		return state.isSolidRender(slice, pos) ? (byte) 1 : 0;
	}
}
