param([string]$ApkPath)
$ErrorActionPreference = 'Stop'
# Only writes generated files inside this project's .local/vpn-sdk and app/libs.
# Does not install an APK, change SDK tools, run third-party scripts, or change a VPN.
$project = Split-Path -Parent $PSScriptRoot
$staging = Join-Path $project '.local/vpn-sdk'
$libraryDirectory = Join-Path $project 'app/libs'
New-Item -ItemType Directory -Path $staging,$libraryDirectory -Force | Out-Null
$expectedHash = '270b3e6e54c99103ed98fa967de7bbf6053e1066250ea35d2b9f450947fd0a78'
if (-not $ApkPath) {
    $ApkPath = Join-Path $staging 'tailscale-android-universal-1.104.2.apk'
    if (-not (Test-Path -LiteralPath $ApkPath)) {
        Write-Output 'Downloading pinned official Tailscale 1.104.2 Android package...'
        Invoke-WebRequest -Uri 'https://pkgs.tailscale.com/stable/tailscale-android-universal-1.104.2.apk' -OutFile $ApkPath
    }
}
if ((Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) {
    throw 'Tailscale package checksum mismatch; no native code was extracted.'
}
if (-not $env:JAVA_HOME -or -not $env:ANDROID_HOME) { throw 'Set JAVA_HOME and ANDROID_HOME first.' }
$androidJar = Join-Path $env:ANDROID_HOME 'platforms/android-36/android.jar'
$classDirectory = Join-Path $staging 'classes'
New-Item -ItemType Directory -Path $classDirectory -Force | Out-Null
$sourceFiles = @(Get-ChildItem -LiteralPath (Join-Path $project 'third_party/tailscale-bindings') -Recurse -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $env:JAVA_HOME 'bin/javac.exe') -encoding UTF-8 -source 8 -target 8 -Xlint:-options -classpath $androidJar -d $classDirectory @sourceFiles
if ($LASTEXITCODE -ne 0) { throw 'JNI bridge compilation failed.' }
$jar = Join-Path $staging 'classes.jar'
& (Join-Path $env:JAVA_HOME 'bin/jar.exe') --create --file $jar -C $classDirectory .
if ($LASTEXITCODE -ne 0) { throw 'JNI bridge packaging failed.' }
Add-Type -AssemblyName System.IO.Compression
$apk = [IO.Compression.ZipFile]::OpenRead($ApkPath)
$destination = Join-Path $libraryDirectory 'tailscale-1.104.2.aar'
$output = [IO.File]::Open($destination,[IO.FileMode]::Create,[IO.FileAccess]::Write)
$archive = [IO.Compression.ZipArchive]::new($output,[IO.Compression.ZipArchiveMode]::Create,$false)
try {
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$jar,'classes.jar') | Out-Null
    foreach ($abi in @('arm64-v8a','armeabi-v7a','x86_64','x86')) {
        $entry = $apk.GetEntry("lib/$abi/libgojni.so")
        if (-not $entry) { continue }
        $source = $entry.Open(); $target = $archive.CreateEntry("jni/$abi/libgojni.so").Open()
        try { $source.CopyTo($target) } finally { $source.Dispose(); $target.Dispose() }
    }
    $manifest = $archive.CreateEntry('AndroidManifest.xml').Open()
    $writer = [IO.StreamWriter]::new($manifest,[Text.UTF8Encoding]::new($false))
    $writer.Write('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.dsh.tailscale.sdk"><uses-sdk android:minSdkVersion="26" /></manifest>')
    $writer.Dispose()
} finally { $archive.Dispose(); $output.Dispose(); $apk.Dispose() }
Write-Output 'Prepared pinned Tailscale JNI library from official signed APK. No app was installed.'
