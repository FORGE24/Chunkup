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

# Common build tools needed on all distros
COMMON="cmake gcc-c++ cargo rust"

# ── RHEL / Fedora / CentOS / Rocky / AlmaLinux ─────────────────────
install_rhel() {
    echo "==> RHEL family: installing with dnf"
    $SUDO dnf install -y cmake gcc-c++ cargo rust
    # CUDA toolkit (from NVIDIA repo — user must have cuda repo configured)
    if ! command -v nvcc &>/dev/null; then
        echo "==> CUDA toolkit not found."
        echo "    Install from NVIDIA repo: https://developer.nvidia.com/cuda-downloads"
        echo "    Or skip: pacman/apk distros use OpenCL only."
    fi
    # OpenCL dev headers
    if ! rpm -q ocl-icd-devel &>/dev/null; then
        $SUDO dnf install -y ocl-icd-devel
    fi
    echo "==> RHEL dependencies installed"
}

# ── Debian / Ubuntu / Mint ─────────────────────────────────────────
install_deb() {
    echo "==> Debian family: installing with apt"
    $SUDO apt update
    $SUDO apt install -y cmake g++ cargo rustc
    # OpenCL dev headers
    $SUDO apt install -y ocl-icd-opencl-dev
    # CUDA toolkit — user must install manually from NVIDIA
    if ! command -v nvcc &>/dev/null; then
        echo "==> CUDA toolkit not found."
        echo "    Install: sudo apt install nvidia-cuda-toolkit"
        echo "    Or from NVIDIA: https://developer.nvidia.com/cuda-downloads"
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
    $SUDO apk add bash cmake g++ rust cargo
    # OpenCL (CUDA NOT supported on Alpine — use OpenCL)
    $SUDO apk add opencl-headers opencl-icd-loader-dev
    echo "==> NOTE: Alpine uses OpenCL only (no CUDA support)"
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
