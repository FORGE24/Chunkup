param(
    [ValidateSet("Release", "Debug")]
    [string]$Configuration = "Release",
    [switch]$VerboseBuild,
    [switch]$CudaViaCmake
)

$ErrorActionPreference = "Continue"

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$EngineDir = Join-Path $Root "engine"
$GpuOutDir = Join-Path $Root "build\native-gpu"

$VerboseBuild = $VerboseBuild -or ($env:CHUNKUP_BUILD_VERBOSE -eq "1")

function Write-BuildLog($Message) {
    Write-Host $Message
}

function Write-BuildCmd($Message) {
    if ($VerboseBuild) {
        Write-Host "    $Message" -ForegroundColor DarkGray
    }
}

function Copy-IfExists($Path, $DestDir) {
    if (Test-Path $Path) {
        Copy-Item $Path $DestDir -Force
        Write-BuildLog "==> Copied $(Split-Path $Path -Leaf) -> $DestDir"
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

function Invoke-ExternalCommand {
    param(
        [string]$Label,
        [string]$Command
    )

    Write-BuildLog "==> $Label"
    Write-BuildCmd $Command

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    cmd /c $Command
    $sw.Stop()

    if (-not $?) {
        Write-BuildLog "==> $Label failed (exit code $LASTEXITCODE, elapsed $($sw.Elapsed.TotalSeconds.ToString('F1'))s)"
        return $false
    }

    Write-BuildLog "==> $Label done ($($sw.Elapsed.TotalSeconds.ToString('F1'))s)"
    return $true
}

function Build-CudaWithNvcc($Root, $OutDir) {
    $nvcc = Find-Nvcc
    if (-not $nvcc) {
        Write-BuildLog "==> CUDA skipped (nvcc not found)"
        return $false
    }

    $cudaVersion = Get-CudaVersion $nvcc
    Write-BuildLog "==> CUDA: nvcc $cudaVersion at $nvcc"

    $vcvars = Find-VcVars64
    if (-not $vcvars) {
        Write-BuildLog "==> CUDA skipped: no Visual Studio Build Tools found"
        return $false
    }

    if ($vcvars.Toolset) {
        Write-BuildLog "==> CUDA: using $($vcvars.Path) with -vcvars_ver=$($vcvars.Toolset)"
    } else {
        Write-BuildLog "==> CUDA: using $($vcvars.Path)"
    }

    $cu = Join-Path $Root "native\cuda\src\chunkup_cuda.cu"
    $cudaHost = Join-Path $Root "native\cuda\src\chunkup_cuda_host.c"
    $hostc = Join-Path $Root "native\common\chunkup_kernel_host.c"
    $slLog = Join-Path $Root "native\common\chunkup_sl_log.c"
    $incCuda = Join-Path $Root "native\cuda\include"
    $incCommon = Join-Path $Root "native\common"
    $out = Join-Path $GpuOutDir "chunkup_cuda.dll"

    $noiseState = Join-Path $Root "native\common\chunkup_noise_state.c"
    $nvccFlags = @(
        "-shared", "-o", "`"$out`"",
        "-DCHUNKUP_EXPORT_BUILD",
        "`"$cu`"", "`"$cudaHost`"", "`"$hostc`"", "`"$slLog`"", "`"$noiseState`"",
        "-I`"$incCuda`"", "-I`"$incCommon`"",
        "--compiler-options", "/utf-8"
    )
    if ($Configuration -eq "Debug") {
        $nvccFlags += @("-G", "-device-debug", "-lineinfo", "--compiler-options", "/Zi /Od")
    }
    if ($cudaVersion.Major -lt 12) {
        $nvccFlags = @("-allow-unsupported-compiler") + $nvccFlags
    }
    if ($VerboseBuild) {
        $nvccFlags = @("-v") + $nvccFlags
    }

    $vcvarsRedirect = if ($VerboseBuild) { "" } else { " >nul" }
    $vcvarsCall = if ($vcvars.Toolset) {
        "call `"$($vcvars.Path)`" -vcvars_ver=$($vcvars.Toolset)$vcvarsRedirect"
    } else {
        "call `"$($vcvars.Path)`"$vcvarsRedirect"
    }

    if (-not $VerboseBuild) {
        Write-BuildLog "==> CUDA: initializing MSVC environment (vcvars64, ~20-30s, no output) ..."
    }

    $cmd = "$vcvarsCall && `"$nvcc`" $($nvccFlags -join ' ')"
    if (-not (Invoke-ExternalCommand "CUDA: compiling chunkup_cuda.dll" $cmd)) {
        Write-BuildLog "    CUDA $cudaVersion requires MSVC 14.29 or VS 2022 (not 14.51)."
        Write-BuildLog "    Current vcvars: $($vcvars.Path) toolset=$($vcvars.Toolset)"
        Write-BuildLog "    Fix: use -vcvars_ver=14.29, install VS 2022 Build Tools, or upgrade CUDA to 12.8+."
        Write-BuildLog "    Tip: re-run with -VerboseBuild to see nvcc/cl/cmake details."
        return $false
    }

    if (-not (Test-Path $out)) {
        Write-BuildLog "==> CUDA build failed (output missing)"
        return $false
    }

    Write-BuildLog "==> Built chunkup_cuda.dll -> $GpuOutDir"
    return $true
}

function Invoke-CMakeBuild($Name, $SourceDir, $BuildDir, [string[]]$ExtraArgs) {
    Write-BuildLog "==> Building $Name backend"
    $cacheFile = Join-Path $BuildDir "CMakeCache.txt"
    if (Test-Path $cacheFile) {
        $cacheText = Get-Content $cacheFile -Raw -ErrorAction SilentlyContinue
        if ($cacheText -match '/run/media/' -or $cacheText -notmatch [regex]::Escape($SourceDir) -or $cacheText -match 'CMake Error') {
            Write-BuildLog "==> ${Name}: stale CMake cache detected, cleaning $BuildDir"
            Remove-Item $BuildDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
    New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null

    $configureArgs = @("-S", $SourceDir, "-B", $BuildDir) + $ExtraArgs
    if ($VerboseBuild) {
        $configureArgs = @("--log-level=VERBOSE") + $configureArgs
    }

    Write-BuildLog "==> ${Name}: cmake configure"
    Write-BuildCmd ("cmake " + ($configureArgs -join " "))
    & cmake @configureArgs
    if (-not $?) {
        Write-BuildLog "==> $Name configure failed; skipping."
        return $false
    }

    $buildArgs = @("--build", $BuildDir, "--config", $Configuration)
    if ($VerboseBuild) {
        $buildArgs += @("--verbose")
    }

    Write-BuildLog "==> ${Name}: cmake --build"
    Write-BuildCmd ("cmake " + ($buildArgs -join " "))
    & cmake @buildArgs
    if (-not $?) {
        Write-BuildLog "==> $Name build failed; skipping."
        return $false
    }
    return $true
}

function Build-CudaWithCmake($Root, $OutDir) {
    $nvcc = Find-Nvcc
    if (-not $nvcc) {
        Write-BuildLog "==> CUDA (cmake) skipped (nvcc not found)"
        return $false
    }

    $cudaArgs = @(
        "-DCMAKE_BUILD_TYPE=$Configuration",
        "-DCMAKE_CUDA_COMPILER=$nvcc"
    )
    if ([bool](Get-Command ninja -ErrorAction SilentlyContinue)) {
        $cudaArgs = @("-G", "Ninja") + $cudaArgs
    }

    if (-not (Invoke-CMakeBuild "CUDA" (Join-Path $Root "native\cuda") (Join-Path $Root "build\cuda") $cudaArgs)) {
        return $false
    }

    $copied = $false
    $copied = (Copy-IfExists (Join-Path $Root "build\cuda\Release\chunkup_cuda.dll") $OutDir) -or $copied
    $copied = (Copy-IfExists (Join-Path $Root "build\cuda\chunkup_cuda.dll") $OutDir) -or $copied
    return $copied
}

if ($VerboseBuild) {
    Write-BuildLog "==> Verbose build enabled (-VerboseBuild / CHUNKUP_BUILD_VERBOSE=1)"
}

Write-BuildLog "==> Building Rust core ($Configuration)"
Push-Location $EngineDir
try {
    if ($Configuration -eq "Debug") {
        cargo build
    } else {
        cargo build --release
    }
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
    if ($CudaViaCmake) {
        Build-CudaWithCmake $Root $GpuOutDir | Out-Null
    } else {
        Build-CudaWithNvcc $Root $GpuOutDir | Out-Null
    }
} elseif (Find-Nvcc) {
    Build-CudaWithCmake $Root $GpuOutDir | Out-Null
}

if (Get-Command cmake -ErrorAction SilentlyContinue) {
    $codegenScript = Join-Path $Root "scripts\codegen-opencl-router.py"
    if (Test-Path $codegenScript) {
        $python = if (Get-Command python -ErrorAction SilentlyContinue) { "python" } else { "python3" }
        Write-BuildLog "==> Generating OpenCL router sources"
        & $python $codegenScript
        if (-not $?) {
            Write-BuildLog "==> OpenCL codegen failed; skipping OpenCL build"
        }
    }
    $openclArgs = @("-DCMAKE_BUILD_TYPE=$Configuration")
    if ($IsWindows -or ($env:OS -match "Windows")) {
        $cudaRoot = $env:CUDA_PATH
        if (-not $cudaRoot) {
            $cudaRoot = "C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA\v11.8"
        }
        $openclInc = Join-Path $cudaRoot "include"
        $openclLib = Join-Path $cudaRoot "lib\x64\OpenCL.lib"
        if ((Test-Path $openclInc) -and (Test-Path $openclLib)) {
            $openclArgs += @(
                "-DOpenCL_INCLUDE_DIR=$openclInc",
                "-DOpenCL_LIBRARY=$openclLib"
            )
        }
    }
    if ([bool](Get-Command ninja -ErrorAction SilentlyContinue)) {
        $openclArgs = @("-G", "Ninja") + $openclArgs
    }
    if (Invoke-CMakeBuild "OpenCL" (Join-Path $Root "native\opencl") (Join-Path $Root "build\opencl") $openclArgs) {
        Copy-IfExists (Join-Path $Root "build\opencl\Release\chunkup_opencl.dll") $GpuOutDir | Out-Null
        Copy-IfExists (Join-Path $Root "build\opencl\chunkup_opencl.dll") $GpuOutDir | Out-Null
    }
}

Write-BuildLog "==> Done. GPU native artifacts in $GpuOutDir"
Get-ChildItem $GpuOutDir -ErrorAction SilentlyContinue | ForEach-Object {
    Write-BuildLog "    $($_.Name) ($($_.Length) bytes)"
}
