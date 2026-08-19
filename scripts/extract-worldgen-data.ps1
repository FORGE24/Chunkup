# 从映射后的 minecraft jar 提取 worldgen 数据 JSON
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem

$jar = Get-ChildItem ".gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-common-*" -Recurse -Filter "*.jar" |
    Where-Object { $_.Name -notmatch "sources" } | Select-Object -First 1
if (-not $jar) { throw "minecraft jar not found" }
Write-Host "jar: $($jar.FullName)"

$out = "build\extracted"
New-Item -ItemType Directory -Force -Path $out | Out-Null

$zip = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
try {
    $prefixes = @("data/minecraft/worldgen/noise/", "data/minecraft/worldgen/noise_settings/", "data/minecraft/worldgen/density_function/")
    $count = 0
    foreach ($entry in $zip.Entries) {
        if ($entry.FullName.EndsWith("/")) { continue }
        foreach ($p in $prefixes) {
            if ($entry.FullName.StartsWith($p)) {
                $dest = Join-Path $out ($entry.FullName -replace "/", "\")
                $dir = Split-Path $dest -Parent
                New-Item -ItemType Directory -Force -Path $dir | Out-Null
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dest, $true)
                $count++
                break
            }
        }
    }
    Write-Host "extracted $count files to $out"
} finally {
    $zip.Dispose()
}
