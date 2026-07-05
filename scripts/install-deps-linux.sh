#!/usr/bin/env bash
# Chunkup Linux dependency installer — FORGE24
# Installs build dependencies for your distro.
# Usage:  bash scripts/install-deps-linux.sh
set -uo pipefail

DISTRO_ID=""
DISTRO_LIKE=""
SUDO=""

# In containers (CI) we're root — skip sudo
if [[ "$(id -u)" -eq 0 ]]; then
    SUDO=""
else
    SUDO="sudo"
fi

if [[ -f /etc/os-release ]]; then
    # shellcheck source=/dev/null
    . /etc/os-release
    DISTRO_ID="${ID:-}"
    DISTRO_LIKE="${ID_LIKE:-}"
fi

echo "==> Detected: ID=$DISTRO_ID  ID_LIKE=$DISTRO_LIKE"

# ── RHEL / Fedora / CentOS / Rocky / AlmaLinux ─────────────────────
install_rhel() {
    echo "==> RHEL family: installing with dnf"
    $SUDO dnf install -y cmake gcc-c++ curl
    # Rust via rustup
    if ! command -v rustc &>/dev/null; then
        curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable
        . "$HOME/.cargo/env"
    fi
    # CUDA toolkit (from NVIDIA repo — user must have cuda repo configured)
    if ! command -v nvcc &>/dev/null; then
        echo "==> CUDA toolkit not found (expected in CI — no GPU)."
    fi
    # OpenCL dev headers
    $SUDO dnf install -y ocl-icd-devel 2>/dev/null || true
    echo "==> RHEL dependencies installed"
}

# ── Debian / Ubuntu / Mint ─────────────────────────────────────────
install_deb() {
    echo "==> Debian family: installing with apt"
    export DEBIAN_FRONTEND=noninteractive
    $SUDO apt update -qq
    $SUDO apt install -y -qq cmake g++ curl pkg-config
    # Rust via rustup (more reliable than distro packages in CI)
    if ! command -v rustc &>/dev/null; then
        curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable
        . "$HOME/.cargo/env"
    fi
    # OpenCL dev headers
    $SUDO apt install -y -qq ocl-icd-opencl-dev 2>/dev/null || \
    $SUDO apt install -y -qq opencl-headers ocl-icd-libopencl1
    # CUDA toolkit — user must install manually from NVIDIA
    if ! command -v nvcc &>/dev/null; then
        echo "==> CUDA toolkit not found (expected in CI — no GPU)."
    fi
    echo "==> Debian dependencies installed"
}

# ── Arch / Manjaro (pacman) ────────────────────────────────────────
install_arch() {
    echo "==> Arch family: installing with pacman"
    $SUDO pacman -Sy --needed --noconfirm bash cmake gcc make rustup
    $SUDO -u nobody rustup default stable 2>/dev/null || rustup default stable 2>/dev/null || true
    # OpenCL (CUDA NOT supported on Arch — use OpenCL)
    $SUDO pacman -S --needed --noconfirm opencl-headers ocl-icd
    echo "==> NOTE: Arch/Manjaro uses OpenCL only (no CUDA toolkit package)"
    echo "==> Arch dependencies installed"
}

# ── Alpine (apk) ───────────────────────────────────────────────────
install_alpine() {
    echo "==> Alpine: installing with apk"
    $SUDO apk add --no-cache bash cmake g++ curl 2>/dev/null || {
        echo "WARNING: apk add failed, trying individual packages..."
        for pkg in bash cmake g++; do
            $SUDO apk add --no-cache "$pkg" 2>/dev/null || echo "  skip $pkg"
        done
    }
    # Rust: try apk then rustup
    if ! command -v rustc &>/dev/null; then
        if $SUDO apk add --no-cache rust cargo 2>/dev/null; then
            echo "==> Rust installed via apk"
        else
            echo "==> Installing Rust via rustup..."
            curl -sSfL https://sh.rustup.rs | sh -s -- -y --default-toolchain stable 2>/dev/null || {
                echo "WARNING: rustup failed, skipping Rust (Alpine may lack glibc)"
                return 0
            }
            . "$HOME/.cargo/env"
        fi
    fi
    # OpenCL (CUDA NOT supported on Alpine — use OpenCL)
    $SUDO apk add --no-cache opencl-headers opencl-icd-loader-dev 2>/dev/null || {
        echo "WARNING: OpenCL headers not available on this Alpine version"
        echo "  Build will skip OpenCL backend"
    }
    echo "==> Alpine dependencies installed"
}

# ── Dispatch ───────────────────────────────────────────────────────
if [[ "$DISTRO_ID" =~ ^(fedora|centos|rhel|rocky|almalinux|ol|amzn)$ ]] ||
   [[ "$DISTRO_LIKE" =~ (rhel|fedora|centos) ]]; then
    install_rhel
elif [[ "$DISTRO_ID" =~ ^(debian|ubuntu|linuxmint|pop|elementary|kali|deepin)$ ]] ||
     [[ "$DISTRO_LIKE" =~ debian ]]; then
    install_deb
elif [[ "$DISTRO_ID" =~ ^(arch|manjaro|endeavouros|artix|garuda|arcolinux)$ ]] ||
     [[ "$DISTRO_LIKE" =~ arch ]]; then
    install_arch
elif [[ "$DISTRO_ID" == "alpine" ]] || [[ "$DISTRO_LIKE" =~ alpine ]]; then
    install_alpine
else
    echo "==> Unknown distro (ID=$DISTRO_ID). Install manually:"
    echo "    Build tools: cmake, gcc/g++, cargo/rust"
    echo "    OpenCL:      opencl-headers, ocl-icd (or ocl-icd-devel)"
    echo "    CUDA:        nvidia-cuda-toolkit (RHEL/Debian only)"
fi

echo ""
echo "══ Ready to build! Run: ./scripts/build-engine.sh ══"
