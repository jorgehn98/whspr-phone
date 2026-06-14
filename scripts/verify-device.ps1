param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [switch]$RequireMicrophone
)

$ErrorActionPreference = "Stop"

Push-Location (Join-Path $PSScriptRoot "..")
try {
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
    if (-not $sdk -and (Test-Path ".\local.properties")) {
        $sdkLine = Get-Content ".\local.properties" | Where-Object { $_ -like "sdk.dir=*" } | Select-Object -First 1
        if ($sdkLine) {
            $sdk = $sdkLine.Substring("sdk.dir=".Length).Replace("\:", ":").Replace("\\", "\")
        }
    }
    if (-not $sdk -and $env:LOCALAPPDATA) {
        $defaultSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
        if (Test-Path $defaultSdk) {
            $sdk = $defaultSdk
        }
    }

    if (-not $sdk) {
        Write-Host "Android SDK no encontrado."
        exit 1
    }

    $adb = Join-Path $sdk "platform-tools\adb.exe"
    if (-not (Test-Path $adb)) {
        Write-Host "adb no encontrado en $adb"
        exit 1
    }

    if (-not $Serial) {
        $devices = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" } | ForEach-Object { ($_ -split "\s+")[0] })
        if ($devices.Count -eq 1) {
            $Serial = $devices[0]
        } elseif ($devices.Count -gt 1) {
            Write-Host "Hay varios Android conectados. Usa -Serial o ANDROID_SERIAL."
            exit 1
        }
    }
    $adbTarget = if ($Serial) { @("-s", $Serial) } else { @() }

    & $adb @adbTarget get-state *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "No hay ningún Android conectado/listo por adb."
        exit 1
    }
    if ($Serial) {
        Write-Host "OK   Android device -> $Serial"
    }

    $apiLevel = (& $adb @adbTarget shell getprop ro.build.version.sdk).Trim()
    $abis = (& $adb @adbTarget shell getprop ro.product.cpu.abilist).Trim()
    if (-not $abis) {
        $abi = (& $adb @adbTarget shell getprop ro.product.cpu.abi).Trim()
        $abi2 = (& $adb @adbTarget shell getprop ro.product.cpu.abi2).Trim()
        $abis = "$abi,$abi2"
    }
    Write-Host "OK   Android API -> $apiLevel"
    Write-Host "OK   Android ABIs -> $abis"
    $apiNumber = 0
    if (-not [int]::TryParse($apiLevel, [ref]$apiNumber)) {
        Write-Host "MISS Android API -> no he podido leer ro.build.version.sdk"
        exit 1
    }
    if ($apiNumber -lt 28) {
        Write-Host "MISS Android API -> Whspr minSdk is 28"
        exit 1
    }
    if ($abis -notmatch "arm64-v8a") {
        Write-Host "MISS device ABI -> Whspr APK is arm64-v8a only"
        exit 1
    }

    $package = "dev.jorgex.whspr"
    $packagePattern = [regex]::Escape($package)
    $installed = & $adb @adbTarget shell pm path $package
    if ($LASTEXITCODE -ne 0 -or -not $installed) {
        Write-Host "Whspr no está instalado en el dispositivo."
        exit 1
    }
    Write-Host "OK   package installed"

    $micPermission = (& $adb @adbTarget shell pm check-permission android.permission.RECORD_AUDIO $package).Trim()
    if ($micPermission -eq "granted") {
        Write-Host "OK   microphone permission granted"
    } elseif ($RequireMicrophone) {
        Write-Host "MISS microphone permission -> abre Whspr y permite el micrófono"
        exit 1
    } else {
        Write-Host "WARN microphone permission -> abre Whspr y permite el micrófono"
    }

    $imeList = & $adb @adbTarget shell ime list -a
    if (
        $imeList -match "$packagePattern/\.WhsprInputMethodService" -or
        $imeList -match "$packagePattern/$packagePattern\.WhsprInputMethodService"
    ) {
        Write-Host "OK   IME registered"
    } else {
        Write-Host "MISS IME registered"
        exit 1
    }

    $recognizers = & $adb @adbTarget shell cmd package query-intent-services -a android.speech.RecognitionService 2>$null
    if (
        $recognizers -match "$packagePattern/\.WhsprRecognitionService" -or
        $recognizers -match "$packagePattern/$packagePattern\.WhsprRecognitionService"
    ) {
        Write-Host "OK   RecognitionService registered"
    } else {
        Write-Host "MISS RecognitionService registered"
        exit 1
    }
} finally {
    Pop-Location
}
