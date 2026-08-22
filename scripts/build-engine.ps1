param(
    [ValidateSet("Release", "Debug")]
    [string]$Configuration = "Release",
    [switch]$VerboseBuild,
    [switch]$ForceCuda
)

$ErrorActionPreference = "Continue"

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$EngineDir = Join-Path $Root "engine"
$GpuOutDir = Join-Path $Root "build\native-gpu"

$VerboseBuild = $VerboseBuild -or ($env:CHUNKUP_BUILD_VERBOSE -eq "1")

function Write-BuildLog($Message) { Write-Host $Message }
function Write-BuildCmd($Message) { if ($VerboseBuild) { Write-Host "    $Message" -ForegroundColor DarkGray } }

function Copy-IfExists($Path, $DestDir) {
    if (Test-Path $Path) { Copy-Item $Path $DestDir -Force; Write-BuildLog "==> Copied $(Split-Path $Path -Leaf) -> $DestDir"; return $true }
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
    foreach ($p in $candidates) { if ($p -and (Test-Path $p)) { return $p } }
    return $null
}

function Find-VcVars64 {
    $candidates = @(
        "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat",
        "C:\Program Files (x86)\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat",
        "C:\Program Files (x86)\Microsoft Visual Studio\2022\Professional\VC\Auxiliary\Build\vcvars64.bat"
    )
    foreach ($p in $candidates) { if (Test-Path $p) { return @{ Path = $p; Toolset = $null } } }
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
    return $null
}

function Get-CudaVersion($NvccPath) {
    $raw = & $NvccPath --version 2>&1 | Out-String
    if ($raw -match "release (\d+\.\d+)") { return [version]$Matches[1] }
    return [version]"0.0"
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
    if ($VerboseBuild) { $configureArgs = @("--log-level=VERBOSE") + $configureArgs }
    Write-BuildLog "==> ${Name}: cmake configure"
    Write-BuildCmd ("cmake " + ($configureArgs -join " "))
    & cmake @configureArgs
    if (-not $?) { Write-BuildLog "==> $Name configure failed; skipping."; return $false }
    $buildArgs = @("--build", $BuildDir, "--config", $Configuration)
    if ($VerboseBuild) { $buildArgs += @("--verbose") }
    Write-BuildLog "==> ${Name}: cmake --build"
    Write-BuildCmd ("cmake " + ($buildArgs -join " "))
    & cmake @buildArgs
    if (-not $?) { Write-BuildLog "==> $Name build failed; skipping."; return $false }
    return $true
}

function Build-Cuda($Root, $OutDir) {
    $nvcc = Find-Nvcc
    if (-not $nvcc) { Write-BuildLog "==> CUDA skipped (nvcc not found)"; return $false }
    $cudaVersion = Get-CudaVersion $nvcc
    Write-BuildLog "==> CUDA: nvcc $cudaVersion at $nvcc"
    $vcvars = Find-VcVars64
    if (-not $vcvars) { Write-BuildLog "==> CUDA skipped: no Visual Studio Build Tools found"; return $false }
    Write-BuildLog "==> CUDA: using $($vcvars.Path) $($vcvars.Toolset)"
    $generatorArgs = @("-DCMAKE_BUILD_TYPE=$Configuration", "-DCMAKE_CUDA_COMPILER=$nvcc")
    if (Get-Command ninja -ErrorAction SilentlyContinue) { $generatorArgs = @("-G", "Ninja") + $generatorArgs }
    if (-not (Invoke-CMakeBuild "CUDA" (Join-Path $Root "native\cuda") (Join-Path $Root "build\cuda") $generatorArgs)) { return $false }
    $copied = $false
    $copied = (Copy-IfExists (Join-Path $Root "build\cuda\Release\chunkup_cuda.dll") $OutDir) -or $copied
    $copied = (Copy-IfExists (Join-Path $Root "build\cuda\chunkup_cuda.dll") $OutDir) -or $copied
    return $copied
}

if ($VerboseBuild) { Write-BuildLog "==> Verbose build enabled" }

Write-BuildLog "==> Building Rust core ($Configuration)"
Push-Location $EngineDir
try {
    if ($Configuration -eq "Debug") { cargo build } else { cargo build --release }
    if (-not $?) { Write-Error "Rust build failed"; exit 1 }
} finally { Pop-Location }

New-Item -ItemType Directory -Force -Path $GpuOutDir | Out-Null

# CUDA (Windows only)
$cudaBuilt = $false
if ($ForceCuda -or (Find-Nvcc)) {
    $cudaBuilt = Build-Cuda $Root $GpuOutDir
}

# OpenCL (always try)
if (Get-Command cmake -ErrorAction SilentlyContinue) {
    $codegenScript = Join-Path $Root "scripts\codegen-opencl-router.py"
    if (Test-Path $codegenScript) {
        $python = if (Get-Command python -ErrorAction SilentlyContinue) { "python" } else { "python3" }
        Write-BuildLog "==> Generating OpenCL router sources"
        & $python $codegenScript
        if (-not $?) { Write-BuildLog "==> OpenCL codegen failed; skipping OpenCL build" }
    }
    $openclArgs = @("-DCMAKE_BUILD_TYPE=$Configuration")
    $cudaRoot = $env:CUDA_PATH
    if (-not $cudaRoot) { $cudaRoot = "C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA\v11.8" }
    $openclInc = Join-Path $cudaRoot "include"
    $openclLib = Join-Path $cudaRoot "lib\x64\OpenCL.lib"
    if ((Test-Path $openclInc) -and (Test-Path $openclLib)) {
        $openclArgs += @("-DOpenCL_INCLUDE_DIR=$openclInc", "-DOpenCL_LIBRARY=$openclLib")
    }
    if (Get-Command ninja -ErrorAction SilentlyContinue) { $openclArgs = @("-G", "Ninja") + $openclArgs }
    if (Invoke-CMakeBuild "OpenCL" (Join-Path $Root "native\opencl") (Join-Path $Root "build\opencl") $openclArgs) {
        Copy-IfExists (Join-Path $Root "build\opencl\Release\chunkup_opencl.dll") $GpuOutDir | Out-Null
        Copy-IfExists (Join-Path $Root "build\opencl\chunkup_opencl.dll") $GpuOutDir | Out-Null
    }
}

Write-BuildLog "==> Done. GPU native artifacts in $GpuOutDir"
Get-ChildItem $GpuOutDir -ErrorAction SilentlyContinue | ForEach-Object { Write-BuildLog "    $($_.Name) ($($_.Length) bytes)" }