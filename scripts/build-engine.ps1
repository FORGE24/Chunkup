$ErrorActionPreference = "Continue"

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$EngineDir = Join-Path $Root "engine"
$GpuOutDir = Join-Path $Root "build\native-gpu"

function Copy-IfExists($Path, $DestDir) {
    if (Test-Path $Path) {
        Copy-Item $Path $DestDir -Force
        Write-Host "==> Copied $(Split-Path $Path -Leaf) -> $DestDir"
        return $true
    }
    return $false
}

function Find-Nvcc {
    $cmd = Get-Command nvcc -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    $candidates = @(
        "${env:CUDA_PATH}\bin\nvcc.exe",
        "C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA\v13.0\bin\nvcc.exe",
        "C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA\v12.8\bin\nvcc.exe",
        "C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA\v12.6\bin\nvcc.exe",
        "C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA\v11.8\bin\nvcc.exe"
    )
    foreach ($p in $candidates) {
        if ($p -and (Test-Path $p)) { return $p }
    }
    return $null
}

function Find-VcVars64 {
    $candidates2022 = @(
        "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat",
        "C:\Program Files (x86)\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat",
        "C:\Program Files (x86)\Microsoft Visual Studio\2022\Professional\VC\Auxiliary\Build\vcvars64.bat"
    )
    foreach ($p in $candidates2022) {
        if (Test-Path $p) { return @{ Path = $p; Toolset = $null } }
    }

    $vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
    if (Test-Path $vswhere) {
        $installs = & $vswhere -all -products * -property installationPath 2>$null
        foreach ($install in $installs) {
            if ($install -match "\\2022\\") {
                $p = Join-Path $install "VC\Auxiliary\Build\vcvars64.bat"
                if (Test-Path $p) { return @{ Path = $p; Toolset = $null } }
            }
        }
    }

    # VS 18/26（Build Tools）+ CUDA 11.8：强制使用 MSVC 14.29 工具集。
    $vsNewRoots = @(
        "D:\ProgramData\Microsoft\VisualStudio\26",
        "C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools"
    )
    $toolsets = @("14.29.30133", "14.16.27023")
    foreach ($root in $vsNewRoots) {
        $vcvars = Join-Path $root "VC\Auxiliary\Build\vcvars64.bat"
        if (-not (Test-Path $vcvars)) { continue }
        $msvcRoot = Join-Path $root "VC\Tools\MSVC"
        foreach ($ver in $toolsets) {
            if (Test-Path (Join-Path $msvcRoot $ver)) {
                $short = ($ver -split '\.')[0..1] -join '.'
                return @{ Path = $vcvars; Toolset = $short }
            }
        }
        return @{ Path = $vcvars; Toolset = "14.29" }
    }

    return $null
}

function Get-CudaVersion($NvccPath) {
    $raw = & $NvccPath --version 2>&1 | Out-String
    if ($raw -match "release (\d+\.\d+)") { return [version]$Matches[1] }
    return [version]"0.0"
}

function Build-CudaWithNvcc($Root, $OutDir) {
    $nvcc = Find-Nvcc
    if (-not $nvcc) {
        Write-Host "==> CUDA skipped (nvcc not found)"
        return $false
    }

    $cudaVersion = Get-CudaVersion $nvcc
    Write-Host "==> CUDA: nvcc $cudaVersion at $nvcc"

    $vcvars = Find-VcVars64
    if (-not $vcvars) {
        Write-Host "==> CUDA skipped: no Visual Studio Build Tools found"
        return $false
    }

    if ($vcvars.Toolset) {
        Write-Host "==> CUDA: using $($vcvars.Path) with -vcvars_ver=$($vcvars.Toolset)"
    } else {
        Write-Host "==> CUDA: using $($vcvars.Path)"
    }

    $cu = Join-Path $Root "native\cuda\src\chunkup_cuda.cu"
    $hostc = Join-Path $Root "native\common\chunkup_kernel_host.c"
    $incCuda = Join-Path $Root "native\cuda\include"
    $incCommon = Join-Path $Root "native\common"
    $out = Join-Path $GpuOutDir "chunkup_cuda.dll"

    $noiseState = Join-Path $Root "native\common\chunkup_noise_state.c"
    $nvccFlags = @(
        "-shared", "-o", "`"$out`"",
        "`"$cu`"", "`"$hostc`"", "`"$noiseState`"",
        "-I`"$incCuda`"", "-I`"$incCommon`"",
        "--compiler-options", "/utf-8"
    )
    if ($cudaVersion.Major -lt 12) {
        $nvccFlags = @("-allow-unsupported-compiler") + $nvccFlags
    }

    $vcvarsCall = if ($vcvars.Toolset) {
        "call `"$($vcvars.Path)`" -vcvars_ver=$($vcvars.Toolset) >nul"
    } else {
        "call `"$($vcvars.Path)`" >nul"
    }

    $cmd = "$vcvarsCall && `"$nvcc`" $($nvccFlags -join ' ')"
    Write-Host "==> CUDA: compiling chunkup_cuda.dll ..."

    cmd /c $cmd
    if (-not $?) {
        Write-Host "==> CUDA build failed"
        Write-Host "    CUDA $cudaVersion requires MSVC 14.29 or VS 2022 (not 14.51)."
        Write-Host "    Current vcvars: $($vcvars.Path) toolset=$($vcvars.Toolset)"
        Write-Host "    Fix: use -vcvars_ver=14.29, install VS 2022 Build Tools, or upgrade CUDA to 12.8+."
        return $false
    }

    if (-not (Test-Path $out)) {
        Write-Host "==> CUDA build failed (output missing)"
        return $false
    }

    Write-Host "==> Built chunkup_cuda.dll -> $GpuOutDir"
    return $true
}

function Invoke-CMakeBuild($Name, $SourceDir, $BuildDir, [string[]]$ExtraArgs) {
    Write-Host "==> Building $Name backend"
    New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null

    $configureArgs = @("-S", $SourceDir, "-B", $BuildDir) + $ExtraArgs
    & cmake @configureArgs 2>&1 | Out-Null
    if (-not $?) {
        Write-Host "==> $Name configure failed; skipping."
        return $false
    }

    & cmake --build $BuildDir --config Release
    if (-not $?) {
        Write-Host "==> $Name build failed; skipping."
        return $false
    }
    return $true
}

Write-Host "==> Building Rust core (release)"
Push-Location $EngineDir
try {
    cargo build --release
    if (-not $?) {
        Write-Error "Rust build failed"
        exit 1
    }
} finally {
    Pop-Location
}

New-Item -ItemType Directory -Force -Path $GpuOutDir | Out-Null

# Rust 核心由 Gradle copyNativeLibraries 从 engine/target/release 复制
if ($IsWindows -or ($env:OS -match "Windows")) {
    Build-CudaWithNvcc $Root $GpuOutDir | Out-Null
} elseif (Find-Nvcc) {
    $nvcc = Find-Nvcc
    $useNinja = [bool](Get-Command ninja -ErrorAction SilentlyContinue)
    $cudaArgs = @("-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_CUDA_COMPILER=$nvcc")
    if ($useNinja) { $cudaArgs = @("-G", "Ninja") + $cudaArgs }
    if (Invoke-CMakeBuild "CUDA" (Join-Path $Root "native\cuda") (Join-Path $Root "build\cuda") $cudaArgs) {
        Copy-IfExists (Join-Path $Root "build\cuda\Release\chunkup_cuda.dll") $GpuOutDir | Out-Null
        Copy-IfExists (Join-Path $Root "build\cuda\chunkup_cuda.dll") $GpuOutDir | Out-Null
    }
}

if (Get-Command cmake -ErrorAction SilentlyContinue) {
    $openclArgs = @("-DCMAKE_BUILD_TYPE=Release")
    if ([bool](Get-Command ninja -ErrorAction SilentlyContinue)) {
        $openclArgs = @("-G", "Ninja") + $openclArgs
    }
    if (Invoke-CMakeBuild "OpenCL" (Join-Path $Root "native\opencl") (Join-Path $Root "build\opencl") $openclArgs) {
        Copy-IfExists (Join-Path $Root "build\opencl\Release\chunkup_opencl.dll") $GpuOutDir | Out-Null
        Copy-IfExists (Join-Path $Root "build\opencl\chunkup_opencl.dll") $GpuOutDir | Out-Null
    }
}

Write-Host "==> Done. GPU native artifacts in $GpuOutDir"
Get-ChildItem $GpuOutDir -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Host "    $($_.Name) ($($_.Length) bytes)"
}
