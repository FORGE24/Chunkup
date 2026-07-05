package cn.sanrolnet.chunkup.client.sodium;

import me.jellysquid.mods.sodium.client.world.WorldSlice;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * 将 Sodium {@link WorldSlice} 压缩为 Rust section mesher 使用的 4096 字节语义：
 * 0 = air, 1 = uniform opaque cube, 2 = fluid.
 */
public final class SectionBlockStateEncoder {
	public enum Route {
		/** 全空气：可直接返回空 mesh，无需 JNI。 */
		AIR_ONLY,
		/** 单一 opaque、无流体、无复杂模型：可走 Rust 快路径。 */
		RUST_FAST,
		/** 流体为主、混合方块或模型：必须用 Sodium 原生 meshing。 */
		SODIUM_NATIVE,
	}

	private SectionBlockStateEncoder() {
	}

	public static Route classify(WorldSlice slice, int minX, int minY, int minZ) {
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

	public static byte[] encode(WorldSlice slice, int minX, int minY, int minZ) {
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

	private static byte encodeBlock(
			BlockState state,
			WorldSlice slice,
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
