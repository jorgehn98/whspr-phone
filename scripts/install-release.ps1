param(
    [string]$Serial = $env:ANDROID_SERIAL
)

$ErrorActionPreference = "Stop"

Push-Location (Join-Path $PSScriptRoot "..")
try {
    .\scripts\build-release.ps1
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

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

    $apk = ".\app\build\outputs\apk\release\app-release.apk"
    if (-not (Test-Path $apk)) {
        Write-Host "APK no encontrado en $apk"
        exit 1
    }

    & $adb @adbTarget get-state *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "No hay ningún Android conectado/listo por adb."
        Write-Host "Conecta un dispositivo con depuración USB o arranca un emulador."
        exit 1
    }
    if ($Serial) {
        Write-Host "OK   Android device -> $Serial"
    }

    Write-Host ""
    Write-Host "Installing Whspr release APK..."
    & $adb @adbTarget install -r $apk
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    Write-Host ""
    Write-Host "Granting microphone permission..."
    & $adb @adbTarget shell pm grant dev.jorgex.whspr android.permission.RECORD_AUDIO
    $micGrantedByAdb = $LASTEXITCODE -eq 0
    if (-not $micGrantedByAdb) {
        Write-Host "No he podido conceder el micrófono por adb. Se podrá permitir manualmente al abrir Whspr."
    } else {
        Write-Host "OK   microphone permission granted"
    }

    Write-Host ""
    .\scripts\verify-device.ps1 -Serial $Serial -RequireMicrophone:$micGrantedByAdb
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    Write-Host ""
    Write-Host "Opening Whspr..."
    $launch = & $adb @adbTarget shell am start -W -n dev.jorgex.whspr/.MainActivity
    $launch | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
    if ($launch -match "Error|Exception|Status: timeout") {
        exit 1
    }
} finally {
    Pop-Location
}
