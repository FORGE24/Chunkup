package net.minecraft.core;

import net.minecraft.resources.ResourceKey;

/** WgDump 辅助：跨包访问 Holder.Reference.bindValue（包私有）。 */
public final class ChunkupRefBind {
    private ChunkupRefBind() {}

    public static <T> Holder.Reference<T> bind(HolderOwner<T> owner, ResourceKey<T> key, T value) {
        Holder.Reference<T> ref = Holder.Reference.createStandAlone(owner, key);
        ref.bindValue(value);
        return ref;
    }
}
