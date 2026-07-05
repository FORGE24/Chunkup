param(
    [string]$QtRoot = "",
    [ValidateSet("Auto", "MSVC", "MinGW")]
    [string]$Toolchain = "Auto",
    [string]$Configuration = "Release",
    [switch]$Deploy,
    [switch]$Run,
    [switch]$ListQt
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$SourceDir = Join-Path $RepoRoot "settings"
$BuildDir = Join-Path $RepoRoot "build\settings"
$NativeOut = Join-Path $RepoRoot "build\native"

function Test-QtKit {
    param([string]$KitPath)

    $result = [ordered]@{
        Path = $KitPath
        Toolchain = if ($KitPath -match "mingw") { "MinGW" } elseif ($KitPath -match "msvc") { "MSVC" } else { "Unknown" }
        HasQt6Config = $false
        HasQMake = $false
        HasWidgets = $false
        HasWinDeployQt = $false
        IsComplete = $false
    }

    if (-not (Test-Path $KitPath)) {
        return [pscustomobject]$result
    }

    $result.HasQt6Config = Test-Path (Join-Path $KitPath "lib\cmake\Qt6\Qt6Config.cmake")
    $result.HasQMake = Test-Path (Join-Path $KitPath "bin\qmake.exe")
    $result.HasWidgets = (Test-Path (Join-Path $KitPath "lib\cmake\Qt6Widgets\Qt6WidgetsConfig.cmake")) -and (
        (Test-Path (Join-Path $KitPath "lib\Qt6Widgets.lib")) -or
        (Test-Path (Join-Path $KitPath "lib\libQt6Widgets.a")) -or
        (Test-Path (Join-Path $KitPath "lib\Qt6Widgets.a"))
    )
    $result.HasWinDeployQt = Test-Path (Join-Path $KitPath "bin\windeployqt.exe")
    $result.IsComplete = $result.HasQt6Config -and $result.HasQMake -and $result.HasWidgets
    return [pscustomobject]$result
}

function Find-QtKits {
    $roots = @()
    if ($env:QT_ROOT) { $roots += $env:QT_ROOT }
    if ($QtRoot) { $roots += $QtRoot }
    $roots += @("D:\Qt", "C:\Qt", (Join-Path $env:USERPROFILE "Qt"))

    $kits = @()
    foreach ($root in ($roots | Select-Object -Unique)) {
        if (-not (Test-Path $root)) { continue }

        if (Test-Path (Join-Path $root "lib\cmake\Qt6\Qt6Config.cmake")) {
            $kits += Test-QtKit $root
            continue
        }

        Get-ChildItem $root -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            $versionDir = $_.FullName
            Get-ChildItem $versionDir -Directory -ErrorAction SilentlyContinue | ForEach-Object {
                $kits += Test-QtKit $_.FullName
            }
        }
    }

    return $kits | Sort-Object { $_.IsComplete } -Descending
}

function Resolve-MingwCompiler {
    param([string]$KitPath)

    $candidates = @(
        "D:\Qt\Tools\mingw1310_64\bin",
        "D:\Qt\Tools\llvm-mingw1706_64\bin",
        "D:\mingw64\bin"
    )

    foreach ($bin in $candidates) {
        if ((Test-Path (Join-Path $bin "g++.exe")) -and (Test-Path (Join-Path $bin "mingw32-make.exe"))) {
            return $bin
        }
    }

    $gppCmd = Get-Command g++ -ErrorAction SilentlyContinue
    if ($gppCmd) {
        return Split-Path $gppCmd.Source -Parent
    }

    throw "MinGW compiler not found. Install via Qt Maintenance Tool (Developer and Designer Tools > MinGW) or set PATH to g++/mingw32-make."
}

function Show-QtDiagnostics {
    param([string]$RequestedPath)

    Write-Host ""
    Write-Host "=== Qt kit scan ===" -ForegroundColor Cyan
    $kits = Find-QtKits
    if ($kits.Count -eq 0) {
        Write-Host "No Qt kits found under D:\Qt, C:\Qt, QT_ROOT." -ForegroundColor Yellow
    } else {
        $kits | Format-Table Path, Toolchain, HasQt6Config, HasQMake, HasWidgets, IsComplete -AutoSize
    }

    if ($RequestedPath) {
        Write-Host "Requested: $RequestedPath" -ForegroundColor Cyan
        Test-QtKit $RequestedPath | Format-List
    }

    Write-Host ""
    Write-Host "Your D:\Qt\6.11.1\msvc2022_64 appears to only have Qt WebEngine/PDF add-ons." -ForegroundColor Yellow
    Write-Host "Install the base Qt 6.11.1 + Qt Widgets for MSVC 2022 64-bit:" -ForegroundColor Yellow
    Write-Host "  1. Run D:\Qt\MaintenanceTool.exe" -ForegroundColor White
    Write-Host "  2. Add or remove components -> Qt 6.11.1 -> MSVC 2022 64-bit" -ForegroundColor White
    Write-Host "  3. Check: Qt Widgets, Qt GUI, Qt Core (and Qt 5 Compatibility Module optional)" -ForegroundColor White
    Write-Host ""
    Write-Host "Or build now with MinGW (already installed):" -ForegroundColor Green
    Write-Host "  .\scripts\build-settings.ps1 -Toolchain MinGW -QtRoot D:\Qt\6.11.1\mingw_64" -ForegroundColor White
    Write-Host ""
}

$allKits = Find-QtKits
if ($ListQt) {
    Show-QtDiagnostics $QtRoot
    exit 0
}

$selectedKit = $null
if ($QtRoot) {
    $selectedKit = Test-QtKit $QtRoot
    if (-not $selectedKit.IsComplete) {
        Show-QtDiagnostics $QtRoot
        throw "Qt kit incomplete at '$QtRoot'. See instructions above."
    }
} else {
    $preferred = switch ($Toolchain) {
        "MSVC" { $allKits | Where-Object { $_.Toolchain -eq "MSVC" -and $_.IsComplete } | Select-Object -First 1 }
        "MinGW" { $allKits | Where-Object { $_.Toolchain -eq "MinGW" -and $_.IsComplete } | Select-Object -First 1 }
        default {
            ($allKits | Where-Object { $_.Toolchain -eq "MSVC" -and $_.IsComplete } | Select-Object -First 1)
        }
    }

    if (-not $preferred -and $Toolchain -eq "Auto") {
        $preferred = $allKits | Where-Object { $_.IsComplete } | Select-Object -First 1
    }

    if (-not $preferred) {
        Show-QtDiagnostics "D:\Qt\6.11.1\msvc2022_64"
        throw "No complete Qt Widgets kit found. Install MSVC 2022 64-bit Qt Widgets or use -Toolchain MinGW."
    }

    $selectedKit = $preferred
    $QtRoot = $selectedKit.Path
}

Write-Host "Chunkup settings: using Qt kit at $QtRoot ($($selectedKit.Toolchain))" -ForegroundColor Green

if (-not $env:JAVA_HOME) {
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCmd) {
        $javaBin = Split-Path $javaCmd.Source -Parent
        $candidate = Split-Path $javaBin -Parent
        if (Test-Path (Join-Path $candidate "include\jni.h")) {
            $env:JAVA_HOME = $candidate
        }
    }
}
if (-not $env:JAVA_HOME) {
    Write-Warning "JAVA_HOME is not set; CMake may fail to find JNI headers."
} else {
    Write-Host "Chunkup settings: JAVA_HOME=$($env:JAVA_HOME)"
}

if ($selectedKit.Toolchain -eq "MSVC") {
    Write-Host "Chunkup settings: configuring (Visual Studio 2022)"
    cmake -S $SourceDir -B $BuildDir `
        -G "Visual Studio 17 2022" -A x64 `
        -DCMAKE_PREFIX_PATH="$QtRoot" `
        -DQT_ROOT="$QtRoot" `
        -DJAVA_HOME="$env:JAVA_HOME"

    Write-Host "Chunkup settings: building ($Configuration)"
    cmake --build $BuildDir --config $Configuration
    $DllPath = Join-Path $BuildDir "$Configuration\chunkup_settings.dll"
} else {
    $mingwBin = Resolve-MingwCompiler -KitPath $QtRoot
    $env:PATH = "$mingwBin;$QtRoot\bin;$env:PATH"

    Write-Host "Chunkup settings: configuring (MinGW, compiler at $mingwBin)"
    cmake -S $SourceDir -B $BuildDir `
        -G "MinGW Makefiles" `
        -DCMAKE_PREFIX_PATH="$QtRoot" `
        -DQT_ROOT="$QtRoot" `
        -DCMAKE_BUILD_TYPE=$Configuration `
        -DJAVA_HOME="$env:JAVA_HOME"

    Write-Host "Chunkup settings: building ($Configuration)"
    cmake --build $BuildDir
    $DllPath = Join-Path $BuildDir "chunkup_settings.dll"
}

if (-not (Test-Path $DllPath)) {
    throw "Build finished but chunkup_settings.dll not found: $DllPath"
}

Write-Host "Built: $DllPath" -ForegroundColor Green

New-Item -ItemType Directory -Force -Path $NativeOut | Out-Null
Copy-Item $DllPath (Join-Path $NativeOut "chunkup_settings.dll") -Force

$WinDeployQt = Join-Path $QtRoot "bin\windeployqt.exe"
if (-not (Test-Path $WinDeployQt)) {
    throw "windeployqt not found: $WinDeployQt"
}

& $WinDeployQt --no-translations --no-compiler-runtime --dir $NativeOut $DllPath
Write-Host "Deployed Qt runtime to $NativeOut" -ForegroundColor Green

if ($selectedKit.Toolchain -eq "MinGW") {
    $compilerDlls = @(
        (Join-Path $mingwBin "libgcc_s_seh-1.dll"),
        (Join-Path $mingwBin "libstdc++-6.dll"),
        (Join-Path $mingwBin "libwinpthread-1.dll")
    )
    foreach ($dll in $compilerDlls) {
        if (Test-Path $dll) {
            Copy-Item $dll $NativeOut -Force
        }
    }
}

if ($Run) {
    Write-Host "chunkup_settings is a JNI DLL; open settings in-game with the ',' key." -ForegroundColor Yellow
}
