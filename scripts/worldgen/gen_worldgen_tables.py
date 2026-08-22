#!/usr/bin/env python3
"""
从提取的 worldgen JSON 生成表驱动 C 代码（chunkup_worldgen_tables.h）。

- 递归展开 noise_router + density_function 引用树（引用名去重 = Java registry 对象共享）
- 嵌套 spline 扁平化
- 收集全部 noise 引用（minecraft:xxx → NoiseParameters）
- 生成 DF 节点表 / spline 表 / noise 参数表 / router 根索引

用法: python gen_worldgen_tables.py [noise_settings 名]  (默认 overworld)
"""

import json
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "build" / "extracted" / "data" / "minecraft" / "worldgen"
OUT = ROOT / "native" / "common" / "chunkup_worldgen_tables.h"

SETTINGS = sys.argv[1] if len(sys.argv) > 1 else "overworld"


def f32(x):
    """圆整到 float32（Java Codec.FLOAT 解析精度——样条 location/derivative/常量值）。"""
    return struct.unpack("f", struct.pack("f", float(x)))[0]

# ---------------------------------------------------------------- DF 节点类型
# 数值必须与 chunkup_worldgen.h 中 ChunkupDfType 枚举一致
TYPE_ORDER = [
    "CONSTANT", "NOISE", "SHIFTED_NOISE", "SHIFT_A", "SHIFT_B", "SHIFT",
    "ADD", "MUL", "MIN", "MAX", "ABS", "SQUARE", "CUBE", "HALF_NEG",
    "QUARTER_NEG", "SQUEEZE", "CLAMP", "RANGE_CHOICE", "SPLINE",
    "Y_CLAMPED_GRADIENT", "MARKER_INTERPOLATED", "MARKER_FLAT_CACHE",
    "MARKER_CACHE_2D", "MARKER_CACHE_ONCE", "MARKER_CACHE_ALL_IN_CELL",
    "BLEND_ALPHA", "BLEND_OFFSET", "BEARDIFIER", "BLEND_DENSITY",
    "WEIRD_SCALED", "OLD_BLENDED", "END_ISLANDS",
]
DF = {name: i for i, name in enumerate(TYPE_ORDER)}
DF_CONSTANT = DF["CONSTANT"]
DF_NOISE = DF["NOISE"]
DF_OLD_BLENDED = DF["OLD_BLENDED"]

MAPPED_TYPES = {
    "minecraft:abs": DF["ABS"],
    "minecraft:square": DF["SQUARE"],
    "minecraft:cube": DF["CUBE"],
    "minecraft:half_negative": DF["HALF_NEG"],
    "minecraft:quarter_negative": DF["QUARTER_NEG"],
    "minecraft:squeeze": DF["SQUEEZE"],
}
AP2_TYPES = {
    "minecraft:add": DF["ADD"],
    "minecraft:mul": DF["MUL"],
    "minecraft:min": DF["MIN"],
    "minecraft:max": DF["MAX"],
}
MARKER_TYPES = {
    "minecraft:interpolated": DF["MARKER_INTERPOLATED"],
    "minecraft:flat_cache": DF["MARKER_FLAT_CACHE"],
    "minecraft:cache_2d": DF["MARKER_CACHE_2D"],
    "minecraft:cache_once": DF["MARKER_CACHE_ONCE"],
    "minecraft:cache_all_in_cell": DF["MARKER_CACHE_ALL_IN_CELL"],
}
SHIFT_TYPES = {
    "minecraft:shift_a": DF["SHIFT_A"],
    "minecraft:shift_b": DF["SHIFT_B"],
    "minecraft:shift": DF["SHIFT"],
}


class Gen:
    def __init__(self):
        self.df_nodes = []          # list of dict
        self.df_dedup = {}          # canonical json str -> node idx
        self.noises = []            # list of (key, firstOctave, amps)
        self.noise_idx = {}         # key -> idx
        self.spline_nodes = []      # dict: coord_df, point_start, point_count
        self.spline_points = []     # dict: location, derivative, value_spline
        self.type_counter = {}
        self.blended_params = None  # (xz_scale, y_scale, xz_factor, y_factor, smear)

    # ------------------------------------------------ noise params
    def get_noise_idx(self, key):
        if key in self.noise_idx:
            return self.noise_idx[key]
        rel = key.split(":", 1)[1] if ":" in key else key
        p = DATA / "noise" / (rel + ".json")
        d = json.loads(p.read_text(encoding="utf-8"))
        idx = len(self.noises)
        self.noises.append((key, int(d["firstOctave"]), [float(a) for a in d["amplitudes"]]))
        self.noise_idx[key] = idx
        return idx

    # ------------------------------------------------ df nodes
    def add_node(self, type_, a=-1, b=-1, c=-1, d=-1, v0=0.0, v1=0.0, v2=0.0, v3=0.0, dedup_key=None):
        if dedup_key is not None:
            if dedup_key in self.df_dedup:
                return self.df_dedup[dedup_key]
        node = {"type": type_, "a": a, "b": b, "c": c, "d": d, "v0": v0, "v1": v1, "v2": v2, "v3": v3}
        idx = len(self.df_nodes)
        self.df_nodes.append(node)
        self.type_counter[type_] = self.type_counter.get(type_, 0) + 1
        if dedup_key is not None:
            self.df_dedup[dedup_key] = idx
        return idx

    def build_df(self, node):
        """node: number | str(ref) | dict。返回节点索引。dedup_key 模拟 Java 对象身份共享。"""
        # 字符串引用：registry 共享 → 引用名去重
        if isinstance(node, str):
            if node in self.df_dedup:
                return self.df_dedup[node]
            ref = node
            node = self.load_df(ref)
            idx = self.build_df(node)
            # 引用名标记为同一对象（后续相同引用复用）
            self.df_dedup[ref] = idx
            return idx
        if isinstance(node, (int, float)):
            return self.add_node(DF_CONSTANT, v0=float(node), dedup_key=f"c:{float(node)!r}")
        if not isinstance(node, dict):
            raise ValueError(f"bad df node: {node!r}")

        t = node.get("type")
        # {"argument": "name"} 单引用包装
        if t is None and "argument" in node and isinstance(node["argument"], str) and len(node) == 1:
            return self.build_df(node["argument"])

        if t is None:
            raise ValueError(f"df node without type: {node!r}")

        if t == "minecraft:noise":
            nidx = self.get_noise_idx(node["noise"])
            return self.add_node(
                DF_NOISE, d=nidx,
                v0=float(node.get("xz_scale", 1.0)), v1=float(node.get("y_scale", 1.0)),
                dedup_key=f"noise:{node['noise']}:{node.get('xz_scale', 1.0)}:{node.get('y_scale', 1.0)}",
            )
        if t == "minecraft:shifted_noise":
            nidx = self.get_noise_idx(node["noise"])
            sx = self.build_df(node["shift_x"])
            sy = self.build_df(node["shift_y"])
            sz = self.build_df(node["shift_z"])
            return self.add_node(
                DF["SHIFTED_NOISE"], a=sx, b=sy, c=sz, d=nidx,
                v0=float(node.get("xz_scale", 1.0)), v1=float(node.get("y_scale", 1.0)),
            )
        if t in SHIFT_TYPES:
            nidx = self.get_noise_idx(node["argument"] if "argument" in node else node["noise"])
            return self.add_node(SHIFT_TYPES[t], d=nidx)
        if t in MAPPED_TYPES:
            a = self.build_df(node["argument"])
            return self.add_node(MAPPED_TYPES[t], a=a)
        if t in AP2_TYPES:
            a = self.build_df(node["argument1"])
            b = self.build_df(node["argument2"])
            return self.add_node(AP2_TYPES[t], a=a, b=b)
        if t == "minecraft:clamp":
            a = self.build_df(node["input"])
            return self.add_node(DF["CLAMP"], a=a, v0=float(node["min"]), v1=float(node["max"]))
        if t == "minecraft:range_choice":
            a = self.build_df(node["input"])
            b = self.build_df(node["when_in_range"])
            c = self.build_df(node["when_out_of_range"])
            return self.add_node(DF["RANGE_CHOICE"], a=a, b=b, c=c, v0=float(node["min_inclusive"]), v1=float(node["max_exclusive"]))
        if t == "minecraft:y_clamped_gradient":
            return self.add_node(
                DF["Y_CLAMPED_GRADIENT"],
                v0=float(node["from_y"]), v1=float(node["to_y"]),
                v2=float(node["from_value"]), v3=float(node["to_value"]),
                dedup_key=f"ycg:{node['from_y']}:{node['to_y']}:{node['from_value']}:{node['to_value']}",
            )
        if t in MARKER_TYPES:
            a = self.build_df(node["argument"])
            return self.add_node(MARKER_TYPES[t], a=a)
        if t == "minecraft:blend_alpha":
            return self.add_node(DF["BLEND_ALPHA"])
        if t == "minecraft:blend_offset":
            return self.add_node(DF["BLEND_OFFSET"])
        if t == "minecraft:beardifier":
            return self.add_node(DF["BEARDIFIER"])
        if t == "minecraft:blend_density":
            a = self.build_df(node["argument"])
            return self.add_node(DF["BLEND_DENSITY"], a=a)
        if t == "minecraft:weird_scaled_sampler":
            a = self.build_df(node["input"])
            nidx = self.get_noise_idx(node["noise"])
            rarity = 0 if node["rarity_value_mapper"] == "type_1" else 1
            return self.add_node(DF["WEIRD_SCALED"], a=a, d=nidx, v0=float(rarity))
        if t == "minecraft:spline":
            sidx = self.build_spline(node["spline"])
            return self.add_node(DF["SPLINE"], a=sidx)
        if t == "minecraft:old_blended_noise":
            # v0=xz_scale, v1=y_scale, v2=xz_factor, v3=y_factor, d=smear_scale_multiplier(1..8)
            params = (
                float(node["xz_scale"]), float(node["y_scale"]),
                float(node["xz_factor"]), float(node["y_factor"]),
                float(node["smear_scale_multiplier"]),
            )
            if self.blended_params is None:
                self.blended_params = params
            return self.add_node(
                DF_OLD_BLENDED,
                d=int(float(node["smear_scale_multiplier"])),
                v0=params[0], v1=params[1],
                v2=params[2], v3=params[3],
                dedup_key=f"obn:{node['xz_scale']}:{node['y_scale']}:{node['xz_factor']}:{node['y_factor']}:{node['smear_scale_multiplier']}",
            )
        if t == "minecraft:end_islands":
            return self.add_node(DF["END_ISLANDS"])
        if t == "minecraft:constant":
            return self.add_node(DF_CONSTANT, v0=float(node["argument"]))
        raise NotImplementedError(f"unhandled df type: {t}")

    def build_spline(self, sp):
        """sp: number | dict{coordinate, points}。返回 spline 节点索引。

        Java 侧 CubicSpline 全部为 float 精度（Codec.FLOAT）：
        location/derivative/常量 value 都必须圆整到 float32 存储。
        """
        if isinstance(sp, (int, float)):
            # Constant spline → DF CONSTANT（apply = float 值）
            v = f32(sp)
            return self.add_node(DF_CONSTANT, v0=v, dedup_key=f"c:{v!r}")
        coord_df = self.build_df(sp["coordinate"])
        point_start = len(self.spline_points)
        for pt in sp["points"]:
            vs = self.build_spline(pt["value"])
            self.spline_points.append({
                "location": f32(pt["location"]),
                "derivative": f32(pt.get("derivative", 0.0)),
                "value_spline": vs,
            })
        sidx = len(self.spline_nodes)
        self.spline_nodes.append({
            "coord_df": coord_df,
            "point_start": point_start,
            "point_count": len(sp["points"]),
        })
        return -sidx - 2  # spline 节点用负数编码（-2-idx），区别于 DF 节点/常量

    def load_df(self, ref):
        rel = ref.split(":", 1)[1] if ":" in ref else ref
        p = DATA / "density_function" / (rel + ".json")
        return json.loads(p.read_text(encoding="utf-8"))


def c_double(x):
    """输出 C double 字面量（保证 double 精度）。"""
    if x == int(x) and abs(x) < 1e15:
        return f"{int(x)}.0"
    return repr(float(x))


def main():
    settings = json.loads((DATA / "noise_settings" / f"{SETTINGS}.json").read_text(encoding="utf-8"))
    router = settings["noise_router"]
    noise_cfg = settings["noise"]

    g = Gen()
    roots = {}
    for key in router:
        roots[key] = g.build_df(router[key])

    lines = []
    w = lines.append
    w("/**")
    w(f" * 自动生成：{SETTINGS} noise_router 表驱动数据。请勿手改。")
    w(" * 生成器: build/gen_worldgen_tables.py")
    w(" */")
    w("#pragma once")
    w("")
    w('#include "chunkup_perlin.h"')
    w('#include "chunkup_worldgen.h"')
    w("")
    w("#ifdef __cplusplus")
    w('extern "C" {')
    w("#endif")
    w("")

    # noise 参数表
    n = len(g.noises)
    w(f"#define CHUNKUP_WG_NOISE_COUNT {n}")
    w("")
    w(f"static const char* const CHUNKUP_WG_NOISE_KEYS[{n}] = {{")
    for key, fo, amps in g.noises:
        w(f'    "{key}",')
    w("};")
    w("")
    w(f"static const int32_t CHUNKUP_WG_NOISE_FIRST_OCTAVE[{n}] = {{")
    for _, fo, _ in g.noises:
        w(f"    {fo},")
    w("};")
    w("")
    w(f"static const int32_t CHUNKUP_WG_NOISE_AMP_LEN[{n}] = {{")
    for _, _, amps in g.noises:
        w(f"    {len(amps)},")
    w("};")
    w("")
    w(f"static const double CHUNKUP_WG_NOISE_AMPS[{n}][CHUNKUP_PERLIN_MAX_OCTAVES] = {{")
    for _, _, amps in g.noises:
        vals = [c_double(a) for a in amps] + ["0.0"] * (16 - len(amps))
        w("    { " + ", ".join(vals) + " },")
    w("};")
    w("")

    # DF 节点表（X-macro 双份：host static const + CUDA __constant__ 副本）
    m = len(g.df_nodes)
    w(f"#define CHUNKUP_WG_DF_NODE_COUNT {m}")
    w("")
    w("#define CHUNKUP_WG_DF_NODES_DATA \\")
    w("{ \\")
    for nd in g.df_nodes:
        tname = "CHUNKUP_DF_" + TYPE_ORDER[nd["type"]]
        parts = [tname, str(nd["a"]), str(nd["b"]), str(nd["c"]), str(nd["d"])]
        parts += [c_double(nd["v0"]), c_double(nd["v1"]), c_double(nd["v2"]), c_double(nd["v3"])]
        w("    { " + ", ".join(parts) + " }, \\")
    w("}")
    w(f"static const ChunkupDfNode CHUNKUP_WG_DF_NODES[{m}] = CHUNKUP_WG_DF_NODES_DATA;")
    w("#ifdef __CUDACC__")
    w(f"__device__ __constant__ ChunkupDfNode CHUNKUP_WG_DF_NODES_DEV[{m}] = CHUNKUP_WG_DF_NODES_DATA;")
    w("#endif")
    w("#undef CHUNKUP_WG_DF_NODES_DATA")
    w("")

    # spline 表（X-macro 双份）
    sn = len(g.spline_nodes)
    sp = len(g.spline_points)
    w(f"#define CHUNKUP_WG_SPLINE_NODE_COUNT {sn}")
    w(f"#define CHUNKUP_WG_SPLINE_POINT_COUNT {sp}")
    w("")
    if sn:
        w("#define CHUNKUP_WG_SPLINE_NODES_DATA \\")
        w("{ \\")
        for nd in g.spline_nodes:
            w(f"    {{ {nd['coord_df']}, {nd['point_start']}, {nd['point_count']} }}, \\")
        w("}")
        w(f"static const ChunkupSplineNode CHUNKUP_WG_SPLINE_NODES[{sn}] = CHUNKUP_WG_SPLINE_NODES_DATA;")
        w("#ifdef __CUDACC__")
        w(f"__device__ __constant__ ChunkupSplineNode CHUNKUP_WG_SPLINE_NODES_DEV[{sn}] = CHUNKUP_WG_SPLINE_NODES_DATA;")
        w("#endif")
        w("#undef CHUNKUP_WG_SPLINE_NODES_DATA")
        w("")
        w("#define CHUNKUP_WG_SPLINE_POINTS_DATA \\")
        w("{ \\")
        for pt in g.spline_points:
            w(f"    {{ {c_double(pt['location'])}, {c_double(pt['derivative'])}, {pt['value_spline']} }}, \\")
        w("}")
        w(f"static const ChunkupSplinePoint CHUNKUP_WG_SPLINE_POINTS[{sp}] = CHUNKUP_WG_SPLINE_POINTS_DATA;")
        w("#ifdef __CUDACC__")
        w(f"__device__ __constant__ ChunkupSplinePoint CHUNKUP_WG_SPLINE_POINTS_DEV[{sp}] = CHUNKUP_WG_SPLINE_POINTS_DATA;")
        w("#endif")
        w("#undef CHUNKUP_WG_SPLINE_POINTS_DATA")
    else:
        w("static const ChunkupSplineNode CHUNKUP_WG_SPLINE_NODES[1] = { { -1, 0, 0 } };")
        w("static const ChunkupSplinePoint CHUNKUP_WG_SPLINE_POINTS[1] = { { 0.0, 0.0, -1 } };")
    w("")

    # router 根索引
    w("/* noise_router 根节点索引 */")
    for key, idx in roots.items():
        macro = "CHUNKUP_WG_DF_" + key.upper()
        w(f"#define {macro} {idx}")
    w("")

    # noise settings 常量
    w("/* noise_settings */")
    w(f"#define CHUNKUP_WG_SEA_LEVEL {settings['sea_level']}")
    w(f"#define CHUNKUP_WG_MIN_Y {noise_cfg['min_y']}")
    w(f"#define CHUNKUP_WG_HEIGHT {noise_cfg['height']}")
    w(f"#define CHUNKUP_WG_CELL_WIDTH {noise_cfg['size_horizontal'] * 4}")
    w(f"#define CHUNKUP_WG_CELL_HEIGHT {noise_cfg['size_vertical'] * 4}")
    w(f"#define CHUNKUP_WG_AQUIFERS_ENABLED {1 if settings.get('aquifers_enabled', True) else 0}")
    w(f"#define CHUNKUP_WG_ORE_VEINS_ENABLED {1 if settings.get('ore_veins_enabled', True) else 0}")
    w("")
    if g.blended_params is not None:
        xz_s, y_s, xz_f, y_f, smear = g.blended_params
        w("/* old_blended_noise 参数（RandomState.fromHashOf(\"minecraft:terrain\") 派生） */")
        w(f"#define CHUNKUP_WG_BLENDED_XZ_SCALE {c_double(xz_s)}")
        w(f"#define CHUNKUP_WG_BLENDED_Y_SCALE {c_double(y_s)}")
        w(f"#define CHUNKUP_WG_BLENDED_XZ_FACTOR {c_double(xz_f)}")
        w(f"#define CHUNKUP_WG_BLENDED_Y_FACTOR {c_double(y_f)}")
        w(f"#define CHUNKUP_WG_BLENDED_SMEAR {c_double(smear)}")
        w("")
    w("#ifdef __cplusplus")
    w("}")
    w("#endif")

    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"DF nodes: {m}, splines: {sn}, points: {sp}, noises: {n}")
    print("node types:", dict(sorted(g.type_counter.items(), key=lambda x: -x[1])))
    print(f"roots: {roots}")


if __name__ == "__main__":
    main()
