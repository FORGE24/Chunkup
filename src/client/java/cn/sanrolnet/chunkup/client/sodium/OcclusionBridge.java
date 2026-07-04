package cn.sanrolnet.chunkup.client.sodium;

import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.minecraft.core.Direction;

public final class OcclusionBridge {
	private static final int DIRECTION_COUNT = 6;

	private OcclusionBridge() {
	}

	public static VisibilitySet[] toVisibilitySets(long[] visibilityData) {
		if (visibilityData == null || visibilityData.length == 0) {
			VisibilitySet fullyVisible = new VisibilitySet();
			fullyVisible.setAll(true);
			return new VisibilitySet[] { fullyVisible };
		}

		VisibilitySet[] sets = new VisibilitySet[visibilityData.length];
		for (int i = 0; i < visibilityData.length; i++) {
			sets[i] = decode(visibilityData[i]);
		}
		return sets;
	}

	private static VisibilitySet decode(long visibilityData) {
		VisibilitySet set = new VisibilitySet();
		for (int from = 0; from < DIRECTION_COUNT; from++) {
			Direction fromDir = Direction.from3DDataValue(from);
			for (int to = 0; to < DIRECTION_COUNT; to++) {
				int bit = from * 8 + to;
				if ((visibilityData & (1L << bit)) != 0L) {
					set.set(fromDir, Direction.from3DDataValue(to), true);
				}
			}
		}
		return set;
	}
}
