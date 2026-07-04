#!/usr/bin/env bash
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE_DIR="$ROOT/engine"
OUT_DIR="$ROOT/build/native"

copy_if_exists() {
    if [[ -f "$1" ]]; then
        cp "$1" "$OUT_DIR/"
        echo "==> Copied $(basename "$1") -> $OUT_DIR/"
    fi
}

cmake_build() {
    local name="$1"
    local src="$2"
    local build="$3"
    shift 3
    echo "==> Building ${name} backend"
    mkdir -p "$build"
    if ! cmake -S "$src" -B "$build" "$@"; then
        echo "WARNING: ${name} configure failed; skipping."
        return 1
    fi
    if ! cmake --build "$build" --config Release; then
        echo "WARNING: ${name} build failed; skipping."
        return 1
    fi
    return 0
}

echo "==> Building Rust core (release)"
cd "$ENGINE_DIR"
cargo build --release

mkdir -p "$OUT_DIR"
case "$(uname -s)" in
    Darwin) copy_if_exists "$ENGINE_DIR/target/release/libchunkup_core.dylib" ;;
    MINGW*|MSYS*|CYGWIN*|Windows*) copy_if_exists "$ENGINE_DIR/target/release/chunkup_core.dll" ;;
    *) copy_if_exists "$ENGINE_DIR/target/release/libchunkup_core.so" ;;
esac

GENERATOR=()
if command -v ninja &>/dev/null; then
    GENERATOR=(-G Ninja)
fi

if command -v nvcc &>/dev/null; then
    NVCC="$(command -v nvcc)"
    if cmake_build "CUDA" "$ROOT/native/cuda" "$ROOT/build/cuda" \
        "${GENERATOR[@]}" -DCMAKE_BUILD_TYPE=Release -DCMAKE_CUDA_COMPILER="$NVCC"; then
        copy_if_exists "$ROOT/build/cuda/chunkup_cuda.dll"
        copy_if_exists "$ROOT/build/cuda/libchunkup_cuda.so"
        copy_if_exists "$ROOT/build/cuda/libchunkup_cuda.dylib"
    fi
else
    echo "==> CUDA skipped (nvcc not found)"
fi

if command -v cmake &>/dev/null; then
    if cmake_build "OpenCL" "$ROOT/native/opencl" "$ROOT/build/opencl" \
        "${GENERATOR[@]}" -DCMAKE_BUILD_TYPE=Release; then
        copy_if_exists "$ROOT/build/opencl/chunkup_opencl.dll"
        copy_if_exists "$ROOT/build/opencl/libchunkup_opencl.so"
        copy_if_exists "$ROOT/build/opencl/libchunkup_opencl.dylib"
    fi
fi

echo "==> Done. Native artifacts in $OUT_DIR"
