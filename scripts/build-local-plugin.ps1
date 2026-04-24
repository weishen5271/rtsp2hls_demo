param(
    [string]$Version = "0.0.1",
    [switch]$SkipMaven
)

$ErrorActionPreference = "Stop"

$workspace = Split-Path -Parent $PSScriptRoot
$backendDir = Join-Path $workspace "backend"
$distRoot = Join-Path $workspace "plugin-dist"
$inputDir = Join-Path $distRoot "jpackage-input"
$jarName = "rtsp2hls-backend-0.0.1-SNAPSHOT.jar"
$jarPath = Join-Path $backendDir "target\$jarName"
$appName = "RtspHlsLocalPlugin"
$appImageDir = Join-Path $distRoot $appName
$archivePath = Join-Path $distRoot "$appName-app-image.zip"
$installerTemplateDir = Join-Path $workspace "scripts\installer-template"
$installerSrcDir = Join-Path $distRoot "installer-src"
$installerSource = Join-Path $installerSrcDir "Program.cs"
$installerExe = Join-Path $distRoot "$appName-Installer.exe"
$frameworkCsc = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
$downloadDir = Join-Path $workspace "public\downloads"

if (-not $SkipMaven) {
    Push-Location $backendDir
    try {
        mvn -DskipTests package
    }
    finally {
        Pop-Location
    }
}

if (-not (Test-Path -LiteralPath $jarPath)) {
    throw "未找到可打包的 Jar：$jarPath"
}

if (Test-Path -LiteralPath $distRoot) {
    Remove-Item -LiteralPath $distRoot -Recurse -Force
}

New-Item -ItemType Directory -Path $inputDir | Out-Null
Copy-Item -LiteralPath $jarPath -Destination (Join-Path $inputDir $jarName) -Force

$commonArgs = @(
    "--name", $appName,
    "--app-version", $Version,
    "--dest", $distRoot,
    "--input", $inputDir,
    "--main-jar", $jarName,
    "--main-class", "org.springframework.boot.loader.launch.JarLauncher",
    "--java-options", "-Dspring.profiles.active=local-plugin",
    "--java-options", "-Dfile.encoding=UTF-8",
    "--java-options", "-Dsun.stdout.encoding=UTF-8",
    "--java-options", "-Dsun.stderr.encoding=UTF-8",
    "--vendor", "rtsp2hls-demo",
    "--description", "RTSP to HLS local plugin service",
    "--win-console"
)

jpackage @commonArgs --type app-image

if (Test-Path -LiteralPath $archivePath) {
    Remove-Item -LiteralPath $archivePath -Force
}

Compress-Archive -LiteralPath $appImageDir -DestinationPath $archivePath -Force

New-Item -ItemType Directory -Path $installerSrcDir | Out-Null
Copy-Item -LiteralPath (Join-Path $installerTemplateDir "Program.cs") -Destination $installerSource -Force

& $frameworkCsc `
    /target:winexe `
    /platform:x64 `
    /out:$installerExe `
    /resource:$archivePath,payload.zip `
    /reference:System.Windows.Forms.dll `
    /reference:System.IO.Compression.FileSystem.dll `
    /reference:System.IO.Compression.dll `
    $installerSource

New-Item -ItemType Directory -Path $downloadDir -Force | Out-Null
Copy-Item -LiteralPath $installerExe -Destination (Join-Path $downloadDir "$appName-Installer.exe") -Force
