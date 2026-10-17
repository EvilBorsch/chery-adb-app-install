param(
    [Parameter(Mandatory=$true)][string]$AndroidSdk,
    [string]$BuildToolsVersion = '36.0.0',
    [string]$Platform = 'android-30'
)
$ErrorActionPreference = 'Stop'
$buildDir = Join-Path $PSScriptRoot 'build'
$classesDir = Join-Path $buildDir 'classes'
$dexDir = Join-Path $buildDir 'dex'
$androidJar = Join-Path $AndroidSdk "platforms\$Platform\android.jar"
$buildTools = Join-Path $AndroidSdk "build-tools\$BuildToolsVersion"
New-Item -ItemType Directory -Force -Path $classesDir,$dexDir | Out-Null

$sources = @((Join-Path $PSScriptRoot 'LocalInstall.java')) + @(
    Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'stubs') -Recurse -Filter '*.java' |
        ForEach-Object { $_.FullName }
)
& javac --release 8 -encoding UTF-8 -classpath $androidJar -d $classesDir @sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

# Only LocalInstall classes go into DEX. Hidden-interface stubs are compile-only.
$classFiles = @(Get-ChildItem -LiteralPath $classesDir -Filter 'LocalInstall*.class' |
    ForEach-Object { $_.FullName })
& java -cp (Join-Path $buildTools 'lib\d8.jar') com.android.tools.r8.D8 --min-api 26 --lib $androidJar --output $dexDir @classFiles
if ($LASTEXITCODE -ne 0) { throw 'D8 failed' }

$temporaryApk = Join-Path $buildDir 'localinstall.apk'
& (Join-Path $buildTools 'aapt2.exe') link --manifest (Join-Path $PSScriptRoot 'AndroidManifest.xml') -I $androidJar -o $temporaryApk
if ($LASTEXITCODE -ne 0) { throw 'aapt2 failed' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::Open($temporaryApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, (Join-Path $dexDir 'classes.dex'), 'classes.dex') | Out-Null
} finally { $archive.Dispose() }
$destination = Join-Path $PSScriptRoot '..\src-tauri\resources\localinstall.apk'
Copy-Item -LiteralPath $temporaryApk -Destination $destination -Force
Get-FileHash -LiteralPath $destination -Algorithm SHA256
