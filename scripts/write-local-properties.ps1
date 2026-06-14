$ErrorActionPreference = "Stop"

Push-Location (Join-Path $PSScriptRoot "..")
try {
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
    if (-not $sdk -and $env:LOCALAPPDATA) {
        $defaultSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
        if (Test-Path $defaultSdk) {
            $sdk = $defaultSdk
        }
    }

    if (-not $sdk) {
        Write-Host "Android SDK no encontrado."
        Write-Host "Instala Android Studio o configura ANDROID_HOME."
        exit 1
    }

    $escapedSdk = $sdk.Replace("\", "\\").Replace(":", "\:")
    Set-Content -Path ".\local.properties" -Value "sdk.dir=$escapedSdk"
    Write-Host "OK   local.properties -> sdk.dir=$escapedSdk"
} finally {
    Pop-Location
}
