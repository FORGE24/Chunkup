import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/** 分层调试：随机源状态 + 单噪声实例对拍 */
public class WgDbg {
    public static void main(String[] args) {
        long seed = 12345L;
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        XoroshiroRandomSource root = new XoroshiroRandomSource(seed);
        PositionalRandomFactory pf = root.forkPositional();

        StringBuilder sb = new StringBuilder();
        pf.parityConfigString(sb);
        System.out.println("PF " + sb);

        // fromHashOf 后的随机源消耗序列
        String[] keys = {"minecraft:continentalness", "minecraft:erosion", "minecraft:terrain"};
        for (String key : keys) {
            RandomSupport.Seed128bit h = RandomSupport.seedFromHashOf(key);
            System.out.println("HASH " + key + " lo=" + h.seedLo() + " hi=" + h.seedHi());
            RandomSource rs = pf.fromHashOf(key);
            // ImprovedNoise 构造消耗：3×nextDouble + 256×next Int bound
            double d0 = rs.nextDouble(), d1 = rs.nextDouble(), d2 = rs.nextDouble();
            System.out.println("RS " + key + " d3 " + d0 + " " + d1 + " " + d2);
            for (int i = 0; i < 5; i++) {
                System.out.println("RS " + key + " ib " + i + " " + rs.nextInt(256 - i));
            }
        }

        // NormalNoise 实例化（continentalness: firstOctave -9, amps 1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.5,1.5,1.0,1.0,1.0,1.0,0.5,0.5,0.5,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0）
        RandomSource nrs = pf.fromHashOf("minecraft:continentalness");
        NormalNoise nn = NormalNoise.create(nrs, new NormalNoise.NoiseParameters(-9,
                java.util.List.of(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.5, 1.5, 1.0, 1.0, 1.0, 1.0, 0.5, 0.5, 0.5, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)));
        System.out.println("NN continentalness(0.5,0.5,0.5) " + nn.getValue(0.5, 0.5, 0.5));
        System.out.println("NN continentalness(1.5,2.5,3.5) " + nn.getValue(1.5, 2.5, 3.5));
        System.out.println("NN continentalness(-100.25,64.0,200.75) " + nn.getValue(-100.25, 64.0, 200.75));

        // ImprovedNoise 层对拍：octave_-9 与 octave_-8
        RandomSource nrs2 = pf.fromHashOf("minecraft:continentalness");
        PositionalRandomFactory fork = nrs2.forkPositional();
        StringBuilder fsb = new StringBuilder();
        fork.parityConfigString(fsb);
        System.out.println("FORK " + fsb);
        for (String key : new String[]{"octave_-9", "octave_-8", "octave_0"}) {
            RandomSupport.Seed128bit oh = RandomSupport.seedFromHashOf(key);
            System.out.println("OHASH " + key + " lo=" + oh.seedLo() + " hi=" + oh.seedHi());
        }
        for (String key : new String[]{"octave_-9", "octave_-8", "octave_0"}) {
            ImprovedNoise in = new ImprovedNoise(fork.fromHashOf(key));
            System.out.println("IN " + key + " xo=" + in.xo + " yo=" + in.yo + " zo=" + in.zo);
            System.out.println("IN " + key + " noise(0.5,0.5,0.5) " + in.noise(0.5, 0.5, 0.5));
            System.out.println("IN " + key + " noise(1.5,2.5,3.5) " + in.noise(1.5, 2.5, 3.5));
        }
    }
}
