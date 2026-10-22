param(
    [string]$IdePath,
    [switch]$Install,
    [switch]$NoClean
)

# Build script for the OpenCode UI plugin (Java, no Gradle).
# Compiles with javac against the jars of an installed JetBrains IDE and packages
# an "Install Plugin from Disk" zip (structure: OpenCode_UI_V2/lib/*.jar).
#
# Usage:
#   .\build.ps1                      # auto-detect the IDE, build
#   .\build.ps1 -IdePath "C:\...\PyCharm"
#   .\build.ps1 -Install             # also copy into the IDE's plugins dir

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$version = "2.0.5"
$name = "OpenCode_UI_V2"

function Find-Ide {
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA "Programs\PyCharm"),
        (Join-Path $env:LOCALAPPDATA "Programs\IntelliJ IDEA"),
        (Join-Path $env:LOCALAPPDATA "Programs\IntelliJ IDEA Community Edition"),
        (Join-Path $env:LOCALAPPDATA "JetBrains\Toolbox\apps")
    )
    foreach ($c in $candidates) {
        if ($c -and (Test-Path (Join-Path $c "lib"))) { return $c }
    }
    throw "Could not auto-detect an IntelliJ-based IDE. Pass -IdePath."
}

if (-not $IdePath) { $IdePath = Find-Ide }

$javac = Join-Path $IdePath "jbr\bin\javac.exe"
if (-not (Test-Path $javac)) { throw "javac not found at $javac" }

Write-Host "IDE:        $IdePath"
Write-Host "javac:      $javac"
& $javac -version

$out = Join-Path $root "build\classes"
$pluginDir = Join-Path $root "build\plugin\$name"
$libDir = Join-Path $pluginDir "lib"
$jarPath = Join-Path $libDir "$name-$version.jar"

if (-not $NoClean) {
    if (Test-Path (Join-Path $root "build")) { Remove-Item (Join-Path $root "build") -Recurse -Force }
}
New-Item -ItemType Directory -Path $out -Force | Out-Null
New-Item -ItemType Directory -Path $libDir -Force | Out-Null

# Classpath: the IDE itself + the terminal plugin it bundles.
$cp = @()
$cp += (Get-ChildItem (Join-Path $IdePath "lib\*.jar") | ForEach-Object { $_.FullName })
foreach ($p in @("plugins\terminal\lib", "plugins\platform-images\lib")) {
    $dir = Join-Path $IdePath $p
    if (Test-Path $dir) { $cp += (Get-ChildItem "$dir\*.jar" | ForEach-Object { $_.FullName }) }
}
$cpString = ($cp -join ";")

$sources = Get-ChildItem (Join-Path $root "src\main\java") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Write-Host "Compiling $($sources.Count) sources..."

$argFile = Join-Path $root "build\javac.args"
$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("-d `"$($out.Replace('\','/'))`"")
$lines.Add("-encoding UTF-8")
$lines.Add("-nowarn")
$lines.Add("-classpath `"$($cpString.Replace('\','/'))`"")
foreach ($s in $sources) { $lines.Add("`"$($s.Replace('\','/'))`"") }
[IO.File]::WriteAllLines($argFile, $lines, [System.Text.UTF8Encoding]::new($false))

& $javac "@$argFile"
if ($LASTEXITCODE -ne 0) { throw "Compilation failed ($LASTEXITCODE)" }
Write-Host "Compiled OK" -ForegroundColor Green

# Resources
Copy-Item (Join-Path $root "src\main\resources\*") $out -Recurse -Force

# Package the jar (JBR has no jar.exe: build it with .NET)
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
if (Test-Path $jarPath) { Remove-Item $jarPath -Force }
$zip = [System.IO.Compression.ZipFile]::Open($jarPath, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    Get-ChildItem $out -Recurse -File | ForEach-Object {
        $rel = $_.FullName.Substring($out.Length + 1).Replace('\', '/')
        [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $rel)
    }
} finally {
    $zip.Dispose()
}
Write-Host "Jar: $jarPath" -ForegroundColor Green

# Plugin zip (JetBrains layout: <name>/lib/*.jar)
$dist = Join-Path $root "build\distributions"
New-Item -ItemType Directory -Path $dist -Force | Out-Null
$zipPath = Join-Path $dist "$name-$version.zip"
if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
Compress-Archive -Path $pluginDir -DestinationPath $zipPath -Force
Write-Host "Zip: $zipPath" -ForegroundColor Green

if ($Install) {
    $productInfo = Get-Content (Join-Path $IdePath "product-info.json") -Raw | ConvertFrom-Json
    $pluginsRoot = Join-Path $env:APPDATA "JetBrains\$($productInfo.dataDirectoryName)\plugins"
    New-Item -ItemType Directory -Path $pluginsRoot -Force | Out-Null
    $target = Join-Path $pluginsRoot $name
    if (Test-Path $target) { Remove-Item $target -Recurse -Force }
    Copy-Item $pluginDir $target -Recurse -Force
    Write-Host "Installed to: $target" -ForegroundColor Cyan
    Write-Host "Restart the IDE to load the plugin." -ForegroundColor Yellow
}
