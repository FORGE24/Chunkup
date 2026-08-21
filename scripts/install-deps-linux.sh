#!/usr/bin/env bash
# Chunkup Linux dependency installer — OpenCL only.
# Usage:  bash scripts/install-deps-linux.sh
set -uo pipefail

DISTRO_ID=""
DISTRO_LIKE=""
SUDO=""

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
echo "==> GPU policy: OpenCL only"

install_rhel() {
    echo "==> RHEL family: installing with dnf"
    $SUDO dnf install -y cmake gcc-c++ curl python3 pkgconf-pkg-config
    if ! command -v rustc &>/dev/null; then
        curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable
        . "$HOME/.cargo/env"
    fi
    $SUDO dnf install -y ocl-icd-devel opencl-headers 2>/dev/null || \
        $SUDO dnf install -y ocl-icd-devel
    echo "==> RHEL dependencies installed"
}

install_deb() {
    echo "==> Debian family: installing with apt"
    export DEBIAN_FRONTEND=noninteractive
    $SUDO apt update -qq
    $SUDO apt install -y -qq cmake g++ curl pkg-config python3
    if ! command -v rustc &>/dev/null; then
        curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable
        . "$HOME/.cargo/env"
    fi
    $SUDO apt install -y -qq ocl-icd-opencl-dev 2>/dev/null || \
        $SUDO apt install -y -qq opencl-headers ocl-icd-libopencl1
    echo "==> Debian dependencies installed"
}

install_arch() {
    echo "==> Arch family: installing with pacman"
    $SUDO pacman -Sy --needed --noconfirm bash cmake gcc make rustup python
    $SUDO -u nobody rustup default stable 2>/dev/null || rustup default stable 2>/dev/null || true
    $SUDO pacman -S --needed --noconfirm opencl-headers ocl-icd
    echo "==> Arch dependencies installed"
}

install_alpine() {
    echo "==> Alpine: installing with apk"
    $SUDO apk add --no-cache bash cmake g++ curl python3 2>/dev/null || {
        echo "WARNING: apk add failed, trying individual packages..."
        for pkg in bash cmake g++ python3; do
            $SUDO apk add --no-cache "$pkg" 2>/dev/null || echo "  skip $pkg"
        done
    }
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
    $SUDO apk add --no-cache opencl-headers opencl-icd-loader-dev 2>/dev/null || {
        echo "WARNING: OpenCL headers not available on this Alpine version"
    }
    echo "==> Alpine dependencies installed"
}

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
    echo "    Build tools: cmake, gcc/g++, cargo/rust, python3"
    echo "    OpenCL:      opencl-headers, ocl-icd (or ocl-icd-devel)"
fi

echo ""
echo "══ Ready to build! Run: ./scripts/build-engine.sh ══"
