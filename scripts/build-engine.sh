#!/usr/bin/env bash
# Chunkup Linux build — OpenCL only (CUDA is Windows / opt-in).
# Override: CHUNKUP_ALLOW_CUDA=1 to also build CUDA when nvcc is present.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE_DIR="$ROOT/engine"
OUT_DIR="$ROOT/build/native-gpu"
KERNEL_DIR="$ROOT/native/opencl/kernels"
mkdir -p "$OUT_DIR"

ALLOW_CUDA="${CHUNKUP_ALLOW_CUDA:-}"
BUILD_CONFIG="${CHUNKUP_BUILD_CONFIG:-Release}"
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

is_rhel_family() {
    [[ "$DISTRO_ID" =~ ^(fedora|centos|rhel|rocky|almalinux|ol|amzn)$ ]] ||
    [[ "$DISTRO_LIKE" =~ (rhel|fedora|centos) ]]
}

is_deb_family() {
    [[ "$DISTRO_ID" =~ ^(debian|ubuntu|linuxmint|pop|elementary|kali|deepin)$ ]] ||
    [[ "$DISTRO_LIKE" =~ debian ]]
}

is_arch_family() {
    [[ "$DISTRO_ID" =~ ^(arch|manjaro|endeavouros|artix|garuda|arcolinux)$ ]] ||
    [[ "$DISTRO_LIKE" =~ arch ]]
}

is_alpine_family() {
    [[ "$DISTRO_ID" == "alpine" ]] || [[ "$DISTRO_LIKE" =~ alpine ]]
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

find_cuda() {
    for candidate in /usr/local/cuda/bin/nvcc /opt/cuda/bin/nvcc /usr/bin/nvcc; do
        if [[ -x "$candidate" ]]; then
            echo "$candidate"
            return 0
        fi
    done
    if command -v nvcc &>/dev/null; then
        command -v nvcc
        return 0
    fi
    return 1
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

build_cuda() {
    if [[ "$ALLOW_CUDA" != "1" ]]; then
        echo "==> CUDA skipped (Linux default is OpenCL-only; set CHUNKUP_ALLOW_CUDA=1 to enable)"
        return 0
    fi

    local nvcc
    nvcc=$(find_cuda) || {
        echo "==> CUDA skipped (CHUNKUP_ALLOW_CUDA=1 but nvcc not found)"
        return 0
    }
    echo "==> Using CUDA compiler: $nvcc"

    local cuda_dir
    cuda_dir=$(dirname "$(dirname "$nvcc")")

    local generator_args=()
    if command -v ninja &>/dev/null; then
        generator_args=(-G Ninja)
    fi

    if cmake_build "CUDA" "$ROOT/native/cuda" "$ROOT/build/cuda" \
        "${generator_args[@]}" \
        -DCMAKE_BUILD_TYPE="$BUILD_CONFIG" \
        -DCMAKE_CUDA_COMPILER="$nvcc" \
        -DCUDAToolkit_ROOT="$cuda_dir" \
        -DCMAKE_CUDA_HOST_COMPILER="$(command -v gcc)"; then
        copy_if_exists "$ROOT/build/cuda/libchunkup_cuda.so"
        echo "==> CUDA backend built successfully"
    else
        echo "WARNING: CUDA build failed; continuing with OpenCL"
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
echo "==> Linux GPU policy: OpenCL only (CUDA opt-in via CHUNKUP_ALLOW_CUDA=1)"

check_prereqs || exit 1

build_rust
build_cuda
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
