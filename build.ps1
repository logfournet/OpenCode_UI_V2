# Build script for the OpenCode UI plugin (Java, no Gradle).
# Compiles with javac against the installed IntelliJ platform jars and packages
# a "Install Plugin from Disk" zip.

$ErrorActionPreference = "Stop"

$root = "C:\Users\sfclog\AppData\Local\Temp\opencode\newplugin"
$jdk = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin"
$idea = "C:\Users\sfclog\AppData\Local\Programs\IntelliJ IDEA"
$tmp  = "C:\Users\sfclog\AppData\Local\Temp\opencode"

$src      = Join-Path $root "src\main\java"
$out      = Join-Path $root "build"
$classes  = Join-Path $out "classes"
$dist     = Join-Path $out "dist"
$jarName  = "OpenCode_UI-2.0.0.jar"
$zipName  = "OpenCode_UI-2.0.0.zip"

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Path $classes -Force | Out-Null
New-Item -ItemType Directory -Path $dist -Force | Out-Null

# --- classpath ----------------------------------------------------------
$cp = @()
foreach ($list in @((Join-Path $tmp "ide-lib-jars.txt"), (Join-Path $tmp "ide-terminal-jars.txt"))) {
    if (Test-Path $list) {
        $cp += Get-Content $list | Where-Object { $_ -and (Test-Path $_) }
    }
}

# --- javac (response file avoids command-line length limits) -----------
$files = Get-ChildItem $src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$argsFile = Join-Path $out "javac.args"
$lines = New-Object System.Collections.Generic.List[string]
$lines.Add('-encoding UTF-8')
$lines.Add('-proc:none')
$lines.Add('--release 17')
$lines.Add('-d')
$lines.Add(($classes -replace '\\', '/'))
$lines.Add('-cp')
$lines.Add('"' + (($cp | ForEach-Object { $_ -replace '\\', '/' }) -join ';') + '"')
foreach ($f in $files) { $lines.Add('"' + ($f -replace '\\', '/') + '"') }
[IO.File]::WriteAllLines($argsFile, $lines, [System.Text.UTF8Encoding]::new($false))

Write-Host "Compiling $(@($files).Count) sources with javac..."
& (Join-Path $jdk "javac.exe") "@$argsFile"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# --- resources -----------------------------------------------------------
$resources = Join-Path $src "..\resources"
New-Item -ItemType Directory -Path (Join-Path $classes "META-INF") -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $classes "icons") -Force | Out-Null
Copy-Item (Join-Path $resources "META-INF\plugin.xml") (Join-Path $classes "META-INF\plugin.xml") -Force
Copy-Item (Join-Path $resources "icons\opencode.svg")  (Join-Path $classes "icons\opencode.svg") -Force

# --- jar ------------------------------------------------------------------
Push-Location $classes
& (Join-Path $jdk "jar.exe") cf (Join-Path $dist $jarName) .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
Pop-Location

# --- zip ------------------------------------------------------------------
$zip = Join-Path $out $zipName
Compress-Archive -Path (Join-Path $dist "*") -DestinationPath $zip -Force

Write-Host ""
Write-Host "BUILD OK"
Write-Host "  plugin jar: $dist\$jarName"
Write-Host "  install zip: $zip"
Write-Host ""
Write-Host "Install via File > Settings > Plugins > Gear > Install Plugin from Disk,"
Write-Host "then restart. Ctrl+` opens the OpenCode panel."
