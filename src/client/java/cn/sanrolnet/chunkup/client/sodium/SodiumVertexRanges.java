package cn.sanrolnet.chunkup.client.sodium;

import me.jellysquid.mods.sodium.client.gl.util.VertexRange;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;

final class SodiumVertexRanges {
	private SodiumVertexRanges() {
	}

	static VertexRange[] fromSegmentPairs(int[] segments) {
		VertexRange[] ranges = new VertexRange[ModelQuadFacing.COUNT];
		int vertexStart = 0;

		for (int i = 0; i + 1 < segments.length; i += 2) {
			int count = segments[i];
			if (count <= 0) {
				break;
			}
			int facing = segments[i + 1];
			if (facing < 0 || facing >= ModelQuadFacing.COUNT) {
				continue;
			}
			ranges[facing] = new VertexRange(vertexStart, count);
			vertexStart += count;
		}

		return ranges;
	}
}
