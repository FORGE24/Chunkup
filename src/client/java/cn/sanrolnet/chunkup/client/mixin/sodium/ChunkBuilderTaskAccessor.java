package cn.sanrolnet.chunkup.client.mixin.sodium;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value = ChunkBuilderTask.class, remap = false)
public interface ChunkBuilderTaskAccessor {
	@Accessor("render")
	RenderSection chunkup$getRender();
	@Accessor("submitTime")
	int chunkup$getBuildTime();
}