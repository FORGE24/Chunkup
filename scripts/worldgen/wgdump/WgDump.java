import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.core.ChunkupRefBind;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * 黄金 dump：vanilla NoiseRouter（无 NoiseChunk 包装，marker 透传）。
 *
 * 自建 HolderLookup.Provider（DF 懒解析 + noise 全量）→ RegistryOps →
 * 解析 noise_settings/overworld.json 的 noise_router →
 * 复刻 RandomState.NoiseWiringHelper 的 mapAll（噪声实例化 / BlendedNoise 重派生）。
 *
 * 用法: WgDump <seed> <outfile>
 * 输出: "<fnName> <x> <y> <z> <double raw bits hex>"
 */
public class WgDump {
    static Path WG_ROOT = Paths.get("build/extracted/data/minecraft/worldgen");
    static RegistryOps<JsonElement> ops;
    static final Map<ResourceLocation, JsonElement> dfJson = new HashMap<>();
    static final Map<ResourceLocation, Holder.Reference<DensityFunction>> dfResolved = new HashMap<>();
    static final Set<ResourceLocation> resolving = new HashSet<>();
    static long seed;
    static PositionalRandomFactory random;

    /* ------------------------------------------------ HolderLookup 实现 */

    static class BaseLookup<T> implements HolderLookup.RegistryLookup<T>, HolderOwner<T> {
        /* HolderLookup.RegistryLookup<T> 实现 */
        final ResourceKey<? extends Registry<? extends T>> regKey;
        final Map<ResourceLocation, Holder.Reference<T>> map = new HashMap<>();

        BaseLookup(ResourceKey<? extends Registry<? extends T>> regKey) {
            this.regKey = regKey;
        }

        Holder.Reference<T> bind(ResourceLocation loc, T value) {
            ResourceKey<T> key = castKey(loc);
            Holder.Reference<T> ref = ChunkupRefBind.bind(this, key, value);
            map.put(loc, ref);
            return ref;
        }

        @SuppressWarnings("unchecked")
        ResourceKey<T> castKey(ResourceLocation loc) {
            return (ResourceKey<T>) ResourceKey.create((ResourceKey<? extends Registry<T>>) regKey, loc);
        }

        @Override
        public Optional<Holder.Reference<T>> get(ResourceKey<T> key) {
            Holder.Reference<T> r = map.get(key.location());
            return r == null ? Optional.empty() : Optional.of(r);
        }

        @Override
        public Optional<HolderSet.Named<T>> get(TagKey<T> tag) {
            return Optional.empty();
        }

        @Override
        public Stream<Holder.Reference<T>> listElements() {
            return map.values().stream();
        }

        @Override
        public Stream<HolderSet.Named<T>> listTags() {
            return Stream.empty();
        }

        @Override
        public ResourceKey<? extends Registry<? extends T>> key() {
            return regKey;
        }

        @Override
        public Lifecycle registryLifecycle() {
            return Lifecycle.stable();
        }
    }

    /* DF 懒解析视图：get(key) 触发递归解析 */
    static class DfLookup extends BaseLookup<DensityFunction> {
        DfLookup() {
            super(Registries.DENSITY_FUNCTION);
        }

        @Override
        public Optional<Holder.Reference<DensityFunction>> get(ResourceKey<DensityFunction> key) {
            ResourceLocation loc = key.location();
            if (!dfResolved.containsKey(loc)) {
                resolveDf(loc, this);
            }
            Holder.Reference<DensityFunction> r = dfResolved.get(loc);
            return r == null ? Optional.empty() : Optional.of(r);
        }

        @Override
        public Stream<Holder.Reference<DensityFunction>> listElements() {
            return dfResolved.values().stream();
        }
    }

    static DfLookup DF_LOOKUP;  /* Bootstrap 后初始化（Registries 静态块依赖 bootstrap） */

    static void resolveDf(ResourceLocation loc, HolderOwner<DensityFunction> owner) {
        if (dfResolved.containsKey(loc)) {
            return;
        }
        if (!resolving.add(loc)) {
            throw new IllegalStateException("cycle: " + loc);
        }
        try {
            JsonElement el = dfJson.get(loc);
            if (el == null) {
                throw new IllegalStateException("missing density_function json: " + loc);
            }
            DataResult<DensityFunction> r = DensityFunction.DIRECT_CODEC.parse(ops, el);
            if (r.error().isPresent()) {
                throw new IllegalStateException("df parse error " + loc + ": " + r.error().get().message());
            }
            DensityFunction df = r.result().orElseThrow();
            Holder.Reference<DensityFunction> ref =
                    ChunkupRefBind.bind(owner, ResourceKey.create(Registries.DENSITY_FUNCTION, loc), df);
            dfResolved.put(loc, ref);
        } finally {
            resolving.remove(loc);
        }
    }

    /* ------------------------------------------------ main */

    public static void main(String[] args) throws Exception {
        seed = Long.parseLong(args[0]);
        String outPath = args[1];
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        DF_LOOKUP = new DfLookup();
        random = new XoroshiroRandomSource(seed).forkPositional();

        // 1. 全量读 density_function JSON
        try (Stream<Path> s = Files.walk(WG_ROOT.resolve("density_function"))) {
            s.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                Path rel = WG_ROOT.resolve("density_function").relativize(p);
                String name = rel.toString().replace('\\', '/').replace(".json", "");
                ResourceLocation loc = new ResourceLocation("minecraft", name);
                try {
                    dfJson.put(loc, JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        // 2. noise 参数全量解析
        BaseLookup<NormalNoise.NoiseParameters> noiseLookup =
                new BaseLookup<>(Registries.NOISE);
        try (Stream<Path> s = Files.walk(WG_ROOT.resolve("noise"))) {
            for (Path p : (Iterable<Path>) s.filter(q -> q.toString().endsWith(".json"))::iterator) {
                Path rel = WG_ROOT.resolve("noise").relativize(p);
                String name = rel.toString().replace('\\', '/').replace(".json", "");
                ResourceLocation loc = new ResourceLocation("minecraft", name);
                JsonElement el = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8));
                DataResult<NormalNoise.NoiseParameters> r = noiseParamsCodec().parse(noiseDirectOps(), el);
                if (r.error().isPresent()) {
                    throw new IllegalStateException("noise parse error " + loc + ": " + r.error().get().message());
                }
                noiseLookup.bind(loc, r.result().orElseThrow());
            }
        }

        // 3. RegistryOps（DF 懒 + noise 全量）
        HolderLookup.Provider provider = new HolderLookup.Provider() {
            @SuppressWarnings({"unchecked", "rawtypes"})
            @Override
            public <T> Optional<HolderLookup.RegistryLookup<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
                if (Registries.DENSITY_FUNCTION.equals(key)) {
                    return Optional.of((HolderLookup.RegistryLookup) DF_LOOKUP);
                }
                if (Registries.NOISE.equals(key)) {
                    return Optional.of((HolderLookup.RegistryLookup) noiseLookup);
                }
                return Optional.empty();
            }
        };
        ops = RegistryOps.create(JsonOps.INSTANCE, provider);

        // 4. 解析 noise_router
        JsonElement settings = JsonParser.parseString(
                Files.readString(WG_ROOT.resolve("noise_settings/overworld.json"), StandardCharsets.UTF_8));
        JsonElement routerJson = settings.getAsJsonObject().get("noise_router");
        DataResult<NoiseRouter> rr = NoiseRouter.CODEC.parse(ops, routerJson);
        if (rr.error().isPresent()) {
            throw new IllegalStateException("router parse error: " + rr.error().get().message());
        }
        NoiseRouter rawRouter = rr.result().orElseThrow();

        // 5. NoiseWiringHelper 等价 mapAll（RandomState 构造逻辑）
        DensityFunction.Visitor wiring = new DensityFunction.Visitor() {
            final Map<DensityFunction, DensityFunction> wrapped = new HashMap<>();

            @Override
            public DensityFunction apply(DensityFunction df) {
                DensityFunction memo = wrapped.get(df);
                if (memo != null) {
                    return memo;
                }
                DensityFunction out;
                if (df instanceof BlendedNoise bn) {
                    out = bn.withNewRandom(random.fromHashOf(new ResourceLocation("terrain")));
                } else {
                    out = df;  /* EndIslandDensityFunction 为 protected，overworld 不可达，跳过 */
                }
                wrapped.put(df, out);
                return out;
            }

            @Override
            public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder nh) {
                ResourceKey<NormalNoise.NoiseParameters> key = nh.noiseData().unwrapKey().orElseThrow();
                NormalNoise nn = NormalNoise.create(random.fromHashOf(key.location()), nh.noiseData().value());
                return new DensityFunction.NoiseHolder(nh.noiseData(), nn);
            }
        };
        NoiseRouter router = rawRouter.mapAll(wiring);

        // 6. dump
        record Fn(String name, DensityFunction df) {}
        Fn[] fns = {
            new Fn("barrier", router.barrierNoise()),
            new Fn("fluid_level_floodedness", router.fluidLevelFloodednessNoise()),
            new Fn("fluid_level_spread", router.fluidLevelSpreadNoise()),
            new Fn("lava", router.lavaNoise()),
            new Fn("temperature", router.temperature()),
            new Fn("vegetation", router.vegetation()),
            new Fn("continents", router.continents()),
            new Fn("erosion", router.erosion()),
            new Fn("depth", router.depth()),
            new Fn("ridges", router.ridges()),
            new Fn("initial_density_without_jaggedness", router.initialDensityWithoutJaggedness()),
            new Fn("final_density", router.finalDensity()),
        };

        int[][] chunks = {{0, 0}, {3, -2}, {-5, 7}, {11, 13}};
        int[] ys = {-60, -40, -16, 0, 16, 40, 64, 96, 128, 160, 200, 250, 300};

        PrintWriter out = new PrintWriter(outPath, StandardCharsets.UTF_8);
        for (Fn fn : fns) {
            for (int[] ch : chunks) {
                for (int lx = 0; lx < 16; lx += 4) {
                    for (int lz = 0; lz < 16; lz += 4) {
                        for (int y : ys) {
                            int x = ch[0] * 16 + lx;
                            int z = ch[1] * 16 + lz;
                            DensityFunction.FunctionContext ctx =
                                    new DensityFunction.SinglePointContext(x, y, z);
                            double v = fn.df().compute(ctx);
                            out.println(fn.name() + " " + x + " " + y + " " + z + " "
                                    + Long.toHexString(Double.doubleToRawLongBits(v)));
                        }
                    }
                }
            }
        }
        out.close();
        System.out.println("dump done");
    }

    /* noise 参数 codec：NormalNoise.NoiseParameters.CODEC 是 Holder 包装，需要用直接 codec。
     * 1.20.1: NoiseParameters.CODEC = RecordCodecBuilder（firstOctave + amplitudes），
     * 引用解析由 RegistryFileCodec 在 DensityFunction.NoiseHolder 层完成。
     * 这里直接用同一 codec + 无注册表 ops 解析裸 JSON。 */
    static com.mojang.serialization.Codec<NormalNoise.NoiseParameters> noiseParamsCodec() {
        return NormalNoise.NoiseParameters.DIRECT_CODEC;
    }

    static JsonOps noiseDirectOps() {
        return JsonOps.INSTANCE;
    }
}
