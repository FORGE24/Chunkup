package cn.sanrolnet.chunkup.client.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.GraphDirection;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.minecraft.core.Direction;

public final class OcclusionBridge {
	private OcclusionBridge() {
	}

	public static VisibilitySet toOcclusionData(long[] visibilityData) {
		if (visibilityData == null || visibilityData.length == 0) {
			VisibilitySet fullyVisible = new VisibilitySet();
			fullyVisible.setAll(true);
			return fullyVisible;
		}
		long merged = 0L;
		for (long word : visibilityData) {
			merged |= word;
		}
		if (merged == 0L) {
			VisibilitySet fullyVisible = new VisibilitySet();
			fullyVisible.setAll(true);
			return fullyVisible;
		}
		return decode(merged);
	}

	private static VisibilitySet decode(long visibilityData) {
		VisibilitySet set = new VisibilitySet();
		for (int from = 0; from < GraphDirection.COUNT; from++) {
			Direction fromDir = GraphDirection.toEnum(from);
			for (int to = 0; to < GraphDirection.COUNT; to++) {
				int bit = (from * 8) + to;
				if ((visibilityData & (1L << bit)) != 0L) {
					set.set(fromDir, GraphDirection.toEnum(to), true);
				}
			}
		}
		return set;
	}
}
