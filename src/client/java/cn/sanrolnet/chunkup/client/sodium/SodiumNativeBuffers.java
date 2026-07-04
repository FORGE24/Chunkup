package cn.sanrolnet.chunkup.client.sodium;

import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public final class SodiumNativeBuffers {
	private SodiumNativeBuffers() {
	}

	public static NativeBuffer copy(ByteBuffer src) {
		int remaining = src.remaining();
		NativeBuffer dst = new NativeBuffer(remaining);
		long srcAddress = MemoryUtil.memAddress(src);
		MemoryUtil.memCopy(srcAddress, dst.getDirectPointer(), remaining);
		return dst;
	}
}
