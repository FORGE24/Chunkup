#!/usr/bin/env bash
# Chunkup Linux build script — FORGE24
# Distro policy:
#   RHEL/Fedora/CentOS/derivatives → CUDA + OpenCL (CUDA preferred)
#   Debian/Ubuntu/derivatives      → CUDA + OpenCL (CUDA preferred)
#   Arch/Manjaro/pacman-based      → OpenCL ONLY (force skip CUDA)
#   Alpine/apk-based               → OpenCL ONLY (force skip CUDA)
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE_DIR="$ROOT/engine"
OUT_DIR="$ROOT/build/native-gpu"
mkdir -p "$OUT_DIR"

C_FORCE_OPENCL="${CHUNKUP_FORCE_OPENCL:-}"
DISTRO_ID=""
DISTRO_LIKE=""

# ── distro detection ───────────────────────────────────────────────
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

allow_cuda() {
    # Respect explicit override
    if [[ "$C_FORCE_OPENCL" == "1" ]]; then
        return 1
    fi
    if is_arch_family || is_alpine_family; then
        return 1
    fi
    return 0
}

# ── helpers ────────────────────────────────────────────────────────
copy_if_exists() {
    if [[ -f "$1" ]]; then
        cp "$1" "$OUT_DIR/"
        echo "==> Copied $(basename "$1") -> $OUT_DIR/"
    fi
}

find_cuda() {
    # Try common CUDA install locations on Linux
    for candidate in /usr/local/cuda/bin/nvcc /opt/cuda/bin/nvcc /usr/bin/nvcc; do
        if [[ -x "$candidate" ]]; then
            echo "$candidate"
            return 0
        fi
    done
    # Try PATH
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
        echo "WARNING: ${name} configure failed; skipping."
        return 1
    fi
    if ! cmake --build "$build" --config Release -j"$(nproc)"; then
        echo "WARNING: ${name} build failed; skipping."
        return 1
    fi
    return 0
}

# ── check prerequisites ────────────────────────────────────────────
check_prereqs() {
    local missing=()
    # Ensure cargo/rustc are in PATH (rustup may have been installed in a prior step)
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
        echo "Install with your package manager:"
        if is_rhel_family; then
            echo "  dnf install cmake gcc-c++ cargo rust"
        elif is_deb_family; then
            echo "  apt install cmake g++ cargo rustc"
        elif is_arch_family; then
            echo "  pacman -S cmake gcc rust"
        elif is_alpine_family; then
            echo "  apk add cmake g++ rust cargo"
        fi
        return 1
    fi
}

# ── Rust core ──────────────────────────────────────────────────────
build_rust() {
    echo "==> Building Rust core (release)"
    cd "$ENGINE_DIR"
    cargo build --release
    copy_if_exists "$ENGINE_DIR/target/release/libchunkup_core.so"
}

# ── CUDA backend ───────────────────────────────────────────────────
build_cuda() {
    if ! allow_cuda; then
        echo "==> CUDA skipped (distro=$DISTRO_ID: CUDA not supported, use OpenCL)"
        return 0
    fi

    if ! command -v cmake &>/dev/null; then
        echo "==> CUDA skipped (cmake not found)"
        return 0
    fi

    local nvcc
    nvcc=$(find_cuda) || {
        echo "==> CUDA skipped (nvcc not found — install cuda-toolkit)"
        return 0
    }
    echo "==> Using CUDA compiler: $nvcc"

    local cuda_dir
    cuda_dir=$(dirname "$(dirname "$nvcc")")  # /usr/local/cuda/bin/nvcc → /usr/local/cuda

    local generator_args=()
    if command -v ninja &>/dev/null; then
        generator_args=(-G Ninja)
    fi

    if cmake_build "CUDA" "$ROOT/native/cuda" "$ROOT/build/cuda" \
        "${generator_args[@]}" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_CUDA_COMPILER="$nvcc" \
        -DCUDAToolkit_ROOT="$cuda_dir"; then
        copy_if_exists "$ROOT/build/cuda/libchunkup_cuda.so"
        echo "==> CUDA backend built successfully"
    fi
}

# ── OpenCL backend ─────────────────────────────────────────────────
build_opencl() {
    if ! command -v cmake &>/dev/null; then
        echo "==> OpenCL skipped (cmake not found)"
        return 0
    fi

    local generator_args=()
    if command -v ninja &>/dev/null; then
        generator_args=(-G Ninja)
    fi

    if cmake_build "OpenCL" "$ROOT/native/opencl" "$ROOT/build/opencl" \
        "${generator_args[@]}" \
        -DCMAKE_BUILD_TYPE=Release; then
        copy_if_exists "$ROOT/build/opencl/libchunkup_opencl.so"
        echo "==> OpenCL backend built successfully"
    fi
}

# ── main ───────────────────────────────────────────────────────────
detect_distro
echo "==> Detected distro: ID=$DISTRO_ID  ID_LIKE=$DISTRO_LIKE"

if [[ "$C_FORCE_OPENCL" == "1" ]]; then
    echo "==> CHUNKUP_FORCE_OPENCL=1 — CUDA disabled, OpenCL only"
elif allow_cuda; then
    echo "==> Distro policy: CUDA + OpenCL (CUDA preferred at runtime)"
else
    echo "==> Distro policy: OpenCL only (distro does not support CUDA toolkit)"
fi

check_prereqs || exit 1

build_rust
build_cuda
build_opencl

echo ""
echo "══════════════════════════════════════════════════════"
echo "  Chunkup Linux build complete (FORGE24)"
echo "  Output: $OUT_DIR"
echo "  Distro: $DISTRO_ID"
echo "  CUDA available: $(allow_cuda && echo yes || echo no)"
echo "══════════════════════════════════════════════════════"
ls -la "$OUT_DIR/" 2>/dev/null || true
