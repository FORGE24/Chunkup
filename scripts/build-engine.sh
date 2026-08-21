#!/usr/bin/env bash
# Chunkup Linux build — OpenCL only (CUDA not supported on Linux).
# Usage: ./scripts/build-engine.sh [Release|Debug]
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE_DIR="$ROOT/engine"
OUT_DIR="$ROOT/build/native-gpu"
KERNEL_DIR="$ROOT/native/opencl/kernels"
mkdir -p "$OUT_DIR"

BUILD_CONFIG="${1:-${CHUNKUP_BUILD_CONFIG:-Release}}"
DISTRO_ID=""
DISTRO_LIKE=""

detect_distro() {
    if [[ -f /etc/os-release ]]; then
        # shellcheck source=/dev/null
        . /etc/os-release
        DISTRO_ID="${ID:-}"
        DISTRO_LIKE="${ID_LIKE:-}"
    fi
}

copy_if_exists() {
    if [[ -f "$1" ]]; then
        cp "$1" "$OUT_DIR/"
        echo "==> Copied $(basename "$1") -> $OUT_DIR/"
    fi
}

copy_opencl_kernels() {
    copy_if_exists "$KERNEL_DIR/chunkup_kernel.cl"
    copy_if_exists "$KERNEL_DIR/chunkup_router_codegen.clh"
}

cmake_build() {
    local name="$1"
    local src="$2"
    local build="$3"
    shift 3
    echo "==> Building ${name} backend"
    mkdir -p "$build"
    if ! cmake -S "$src" -B "$build" "$@"; then
        echo "ERROR: ${name} configure failed."
        return 1
    fi
    if ! cmake --build "$build" --config "$BUILD_CONFIG" -j"$(nproc)"; then
        echo "ERROR: ${name} build failed."
        return 1
    fi
    return 0
}

check_prereqs() {
    local missing=()
    if [[ -f "$HOME/.cargo/env" ]]; then
        . "$HOME/.cargo/env"
    fi
    if ! command -v cmake &>/dev/null; then
        missing+=("cmake")
    fi
    if ! command -v cargo &>/dev/null; then
        missing+=("cargo/rust")
    fi
    if ! command -v gcc &>/dev/null && ! command -v clang &>/dev/null; then
        missing+=("gcc or clang")
    fi
    if [[ ${#missing[@]} -gt 0 ]]; then
        echo "ERROR: missing prerequisites: ${missing[*]}"
        echo "Install with: bash scripts/install-deps-linux.sh"
        return 1
    fi
}

build_rust() {
    echo "==> Building Rust core ($BUILD_CONFIG)"
    cd "$ENGINE_DIR"
    if [[ "$BUILD_CONFIG" == "Debug" ]]; then
        cargo build
        copy_if_exists "$ENGINE_DIR/target/debug/libchunkup_core.so"
    else
        cargo build --release
        copy_if_exists "$ENGINE_DIR/target/release/libchunkup_core.so"
    fi
}

build_opencl() {
    local generator_args=()
    if command -v ninja &>/dev/null; then
        generator_args=(-G Ninja)
    fi

    if cmake_build "OpenCL" "$ROOT/native/opencl" "$ROOT/build/opencl" \
        "${generator_args[@]}" \
        -DCMAKE_BUILD_TYPE="$BUILD_CONFIG"; then
        copy_if_exists "$ROOT/build/opencl/libchunkup_opencl.so"
        copy_opencl_kernels
        echo "==> OpenCL backend built successfully"
        return 0
    fi
    return 1
}

detect_distro
echo "==> Detected distro: ID=$DISTRO_ID  ID_LIKE=$DISTRO_LIKE"
echo "==> Linux GPU policy: OpenCL only"

check_prereqs || exit 1

build_rust
if ! build_opencl; then
    echo "ERROR: OpenCL backend is required on Linux."
    echo "Install headers with: bash scripts/install-deps-linux.sh"
    exit 1
fi

echo ""
echo "══════════════════════════════════════════════════════"
echo "  Chunkup Linux build complete"
echo "  Output: $OUT_DIR"
echo "  Distro: $DISTRO_ID"
echo "  GPU backend: OpenCL"
echo "══════════════════════════════════════════════════════"
ls -la "$OUT_DIR/" 2>/dev/null || true