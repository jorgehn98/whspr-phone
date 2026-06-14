$ErrorActionPreference = "SilentlyContinue"

$RequiredNdk = "28.2.13676358"
$RequiredSdk = "36"
$missing = 0

function Find-Java {
    if ($env:JAVA_HOME) {
        $javaHomeExe = Join-Path $env:JAVA_HOME "bin\java.exe"
        if (Test-Path $javaHomeExe) { return $javaHomeExe }
    }

    $command = Get-Command java
    if ($command) { return $command.Source }

    $candidates = @(
        "$env:ProgramFiles\Android\Android Studio\jbr\bin\java.exe",
        "$env:ProgramFiles\Android\Android Studio\jre\bin\java.exe",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr\bin\java.exe"
    )
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) { return $candidate }
    }

    return $null
}

function Read-Java-Major($JavaExe) {
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo.FileName = $JavaExe
    $process.StartInfo.Arguments = "-version"
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.RedirectStandardError = $true
    $process.StartInfo.RedirectStandardOutput = $true
    [void]$process.Start()
    $versionText = $process.StandardError.ReadToEnd() + "`n" + $process.StandardOutput.ReadToEnd()
    $process.WaitForExit()
    if ($versionText -match 'version\s+"(1\.)?([0-9]+)') {
        return [int]$Matches[2]
    }
    return 0
}

Write-Host "Whspr Android build environment"
Write-Host ""

$java = Find-Java
if ($java) {
    Write-Host "OK   java -> $java"
    $major = Read-Java-Major $java
    if ($major -ge 17) {
        Write-Host "OK   Java version -> $major"
    } else {
        Write-Host "MISS Java 17+"
        $missing += 1
    }
} else {
    Write-Host "MISS java"
    $missing += 1
}

if (Test-Path ".\gradlew.bat") {
    Write-Host "OK   gradlew.bat"
} else {
    Write-Host "MISS gradlew.bat"
    $missing += 1
}

Write-Host ""
Write-Host "ANDROID_HOME=$env:ANDROID_HOME"
Write-Host "ANDROID_SDK_ROOT=$env:ANDROID_SDK_ROOT"

Write-Host ""
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
if (-not $sdk -and (Test-Path ".\local.properties")) {
    $sdkLine = Get-Content ".\local.properties" | Where-Object { $_ -like "sdk.dir=*" } | Select-Object -First 1
    if ($sdkLine) {
        $sdk = $sdkLine.Substring("sdk.dir=".Length).Replace("\:", ":").Replace("\\", "\")
        Write-Host "ANDROID SDK from local.properties=$sdk"
    }
}
if (-not $sdk -and $env:LOCALAPPDATA) {
    $defaultSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path $defaultSdk) {
        $sdk = $defaultSdk
        Write-Host "ANDROID SDK from default Android Studio path=$sdk"
    }
}

if ($sdk) {
    $platform = Join-Path $sdk "platforms\android-$RequiredSdk"
    $buildToolsDir = Join-Path $sdk "build-tools"
    $ndk = Join-Path $sdk "ndk\$RequiredNdk"
    $cmakeDir = Join-Path $sdk "cmake"
    if (Test-Path $platform) {
        Write-Host "OK   Android SDK platform $RequiredSdk -> $platform"
    } else {
        Write-Host "MISS Android SDK platform $RequiredSdk"
        $missing += 1
    }
    if ((Test-Path $buildToolsDir) -and (Get-ChildItem $buildToolsDir -Directory | Where-Object { $_.Name -like "$RequiredSdk.*" })) {
        Write-Host "OK   Android SDK Build Tools $RequiredSdk.x -> $buildToolsDir"
    } else {
        Write-Host "MISS Android SDK Build Tools $RequiredSdk.x"
        $missing += 1
    }
    if (Test-Path $ndk) {
        Write-Host "OK   NDK $RequiredNdk -> $ndk"
    } else {
        Write-Host "MISS NDK $RequiredNdk"
        $missing += 1
    }
    if (Test-Path $cmakeDir) {
        Write-Host "OK   Android SDK CMake -> $cmakeDir"
    } else {
        Write-Host "MISS Android SDK CMake"
        $missing += 1
    }
    $adb = Join-Path $sdk "platform-tools\adb.exe"
    if (Test-Path $adb) {
        Write-Host "OK   adb -> $adb"
    } else {
        Write-Host "MISS adb"
        $missing += 1
    }
} else {
    Write-Host "MISS Android SDK env var"
    Write-Host "Hint: set ANDROID_HOME or create local.properties with sdk.dir=C:\Users\<you>\AppData\Local\Android\Sdk"
    Write-Host "Hint: Android Studio default SDK path is usually $env:LOCALAPPDATA\Android\Sdk"
    $missing += 1
}

if ($missing -gt 0) {
    Write-Host ""
    Write-Host "Para dejar el entorno listo:"
    Write-Host "1. Instala Android Studio."
    Write-Host "2. En SDK Manager instala Android SDK Platform $RequiredSdk, Build Tools $RequiredSdk.x, NDK $RequiredNdk y CMake."
    Write-Host "3. Vuelve a ejecutar .\scripts\write-local-properties.ps1 si ANDROID_HOME no está configurado."
    exit 1
}
