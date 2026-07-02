param(
    [string]$Apk = ".\app\build\outputs\apk\release\app-release.apk"
)

$ErrorActionPreference = "Stop"

Push-Location (Join-Path $PSScriptRoot "..")
try {
    if (-not (Test-Path $Apk)) {
        Write-Host "APK no encontrado en $Apk"
        exit 1
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

    $aapt2 = Get-ChildItem -Path (Join-Path $sdk "build-tools") -Recurse -Filter "aapt2.exe" |
        Sort-Object FullName -Descending |
        Select-Object -First 1
    if (-not $aapt2) {
        Write-Host "aapt2 no encontrado en build-tools."
        exit 1
    }

    $apkPath = (Resolve-Path $Apk).Path
    $badging = & $aapt2.FullName dump badging $apkPath
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    $badgingText = $badging -join "`n"
    $expectedBadging = @(
        "package: name='dev.jorgex.whspr'",
        "uses-permission: name='android.permission.INTERNET'",
        "uses-permission: name='android.permission.RECORD_AUDIO'",
        "application-label:'Whspr'",
        "native-code: 'arm64-v8a'"
    )
    foreach ($expected in $expectedBadging) {
        if ($badgingText -notmatch [regex]::Escape($expected)) {
            Write-Host "MISS APK badging -> $expected"
            exit 1
        }
    }

    function Assert-Text($Text, $Label, $Patterns) {
        foreach ($pattern in $Patterns) {
            if ($Text -notmatch $pattern) {
                Write-Host "MISS $Label -> $pattern"
                exit 1
            }
        }
    }

    $manifestTree = & $aapt2.FullName dump xmltree --file AndroidManifest.xml $apkPath
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
    $manifestTreeText = $manifestTree -join "`n"
    Assert-Text $manifestTreeText "APK manifest" @(
        'minSdkVersion.*=28',
        'targetSdkVersion.*=36',
        'allowBackup.*=false',
        'fullBackupContent.*=false',
        'extractNativeLibs.*=false',
        'dataExtractionRules',
        '"dev\.jorgex\.whspr\.WhsprInputMethodService"',
        '"android\.permission\.BIND_INPUT_METHOD"',
        '"android\.view\.InputMethod"',
        '"android\.view\.im"',
        '"dev\.jorgex\.whspr\.WhsprRecognitionService"',
        '"android\.speech\.RecognitionService"',
        '"android\.speech"'
    )
    if ($manifestTreeText -match 'debuggable\(0x0101000f\)=true') {
        Write-Host "MISS APK manifest -> release APK must not be debuggable"
        exit 1
    }

    $resources = (& $aapt2.FullName dump resources $apkPath) -join "`n"
    Assert-Text $resources "APK resources" @(
        'string/recognition_service_name',
        '"Whspr dictado"'
    )

    function Find-CompiledXml($Name) {
        $pattern = "resource\s+0x[0-9a-fA-F]+\s+xml/$([regex]::Escape($Name))\s*\n\s*\(\)\s*\(file\)\s*(res/[^ ]+\.xml)\s*type=XML"
        if ($resources -match $pattern) {
            return $Matches[1]
        }
        Write-Host "MISS APK resource -> xml/$Name"
        exit 1
    }

    $inputMethodXml = (& $aapt2.FullName dump xmltree --file (Find-CompiledXml "input_method") $apkPath) -join "`n"
    Assert-Text $inputMethodXml "APK input method XML" @(
        'E: input-method',
        'settingsActivity.*"dev\.jorgex\.whspr\.MainActivity"',
        'supportsSwitchingToNextInputMethod.*=true',
        'E: subtype',
        'imeSubtypeLocale.*"es_ES"',
        'imeSubtypeMode.*"keyboard"',
        'languageTag.*"es-ES"'
    )

    $recognitionXml = (& $aapt2.FullName dump xmltree --file (Find-CompiledXml "recognition_service") $apkPath) -join "`n"
    Assert-Text $recognitionXml "APK recognition service XML" @(
        'E: recognition-service',
        'settingsActivity.*"dev\.jorgex\.whspr\.MainActivity"',
        'selectableAsDefault.*=true'
    )

    $dataRulesXml = (& $aapt2.FullName dump xmltree --file (Find-CompiledXml "data_extraction_rules") $apkPath) -join "`n"
    Assert-Text $dataRulesXml "APK data extraction XML" @(
        'E: cloud-backup',
        'E: device-transfer',
        'A: domain="root"',
        'A: domain="file"',
        'A: domain="database"',
        'A: domain="sharedpref"',
        'A: domain="external"'
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($apkPath)
    try {
        $entries = @($zip.Entries | ForEach-Object { $_.FullName })
        $badEntries = $entries | Where-Object {
            $_ -match '^assets/' -or
            $_ -match '^lib/(?!arm64-v8a/)' -or
            $_ -match '(^|/)ggml-.*\.bin$' -or
            $_ -match '(^|/)models?/' -or
            $_ -match '\.(pt|onnx|tflite)$'
        }
        if ($badEntries) {
            $badEntries | ForEach-Object { Write-Host "MISS APK content -> $_" }
            exit 1
        }

        if ($entries -notcontains "lib/arm64-v8a/libwhspr.so") {
            Write-Host "MISS APK native lib -> lib/arm64-v8a/libwhspr.so"
            exit 1
        }

        $size = (Get-Item $apkPath).Length
        if ($size -gt 4MB) {
            Write-Host "MISS APK size -> $size bytes, expected <= 4 MB"
            exit 1
        }
        Write-Host "OK   APK package/perms/native ABI"
        Write-Host "OK   APK manifest services/metadata/privacy"
        Write-Host "OK   APK XML resources"
        Write-Host "OK   APK has no embedded models/assets"
        Write-Host "OK   APK size -> $([math]::Round($size / 1MB, 2)) MB"
    } finally {
        $zip.Dispose()
    }
} finally {
    Pop-Location
}
