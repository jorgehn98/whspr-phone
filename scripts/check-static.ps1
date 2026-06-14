$ErrorActionPreference = "Stop"

Push-Location (Join-Path $PSScriptRoot "..")
try {
    $failed = 0

    Write-Host "Whspr static checks"
    Write-Host ""

    $kotlinFiles = Get-ChildItem -Path ".\app\src\main\java" -Recurse -Filter "*.kt"
    foreach ($file in $kotlinFiles) {
        $text = Get-Content -Raw -Path $file.FullName
        $opens = ([regex]::Matches($text, "\{")).Count
        $closes = ([regex]::Matches($text, "\}")).Count
        if ($opens -ne $closes) {
            Write-Host "MISS Kotlin braces -> $($file.FullName): $opens vs $closes"
            $failed += 1
        }
    }
    if ($failed -eq 0) {
        Write-Host "OK   Kotlin brace balance"
    }

    $cppFile = ".\app\src\main\cpp\native_whisper.cpp"
    $cpp = Get-Content -Raw -Path $cppFile
    $cppOpens = ([regex]::Matches($cpp, "\{")).Count
    $cppCloses = ([regex]::Matches($cpp, "\}")).Count
    if ($cppOpens -ne $cppCloses) {
        Write-Host "MISS C++ braces -> ${cppFile}: $cppOpens vs $cppCloses"
        $failed += 1
    } else {
        Write-Host "OK   C++ brace balance"
    }

    $xmlFiles = @(".\app\src\main\AndroidManifest.xml") +
        (Get-ChildItem -Path ".\app\src\main\res" -Recurse -Filter "*.xml" | ForEach-Object { $_.FullName })
    $xmlFailed = 0
    foreach ($file in $xmlFiles) {
        try {
            [xml](Get-Content -Raw -Path $file) | Out-Null
        } catch {
            Write-Host "MISS XML parse -> ${file}: $($_.Exception.Message)"
            $xmlFailed += 1
        }
    }
    if ($xmlFailed -gt 0) {
        $failed += $xmlFailed
    } else {
        Write-Host "OK   XML parse"
    }

    $powershellFailed = 0
    Get-ChildItem -Path ".\scripts" -Filter "*.ps1" | ForEach-Object {
        $tokens = $null
        $errors = $null
        [System.Management.Automation.Language.Parser]::ParseFile($_.FullName, [ref]$tokens, [ref]$errors) | Out-Null
        if ($errors.Count -gt 0) {
            $errors | ForEach-Object { Write-Host "MISS PowerShell parse -> $($_.Message)" }
            $powershellFailed += 1
        }
    }
    if ($powershellFailed -gt 0) {
        $failed += $powershellFailed
    } else {
        Write-Host "OK   PowerShell parse"
    }

    $installScriptText = Get-Content -Raw -Path ".\scripts\install-debug.ps1"
    $installReleaseScriptText = Get-Content -Raw -Path ".\scripts\install-release.ps1"
    $buildReleaseScriptText = Get-Content -Raw -Path ".\scripts\build-release.ps1"
    $verifyApkScriptText = Get-Content -Raw -Path ".\scripts\verify-apk.ps1"
    $verifyScriptText = Get-Content -Raw -Path ".\scripts\verify-device.ps1"
    if (
        $installScriptText -notmatch '\[string\]\$Serial' -or
        $installReleaseScriptText -notmatch '\[string\]\$Serial' -or
        $verifyScriptText -notmatch '\[string\]\$Serial' -or
        $installScriptText -notmatch '\$devices = @\(& \$adb devices' -or
        $installReleaseScriptText -notmatch '\$devices = @\(& \$adb devices' -or
        $verifyScriptText -notmatch '\$devices = @\(& \$adb devices' -or
        $verifyScriptText -notmatch '\[regex\]::Escape\(\$package\)' -or
        $verifyScriptText -match 'dumpsys package' -or
        $verifyScriptText -notmatch '& \$adb @adbTarget get-state' -or
        $verifyScriptText -notmatch '& \$adb @adbTarget shell getprop ro\.build\.version\.sdk' -or
        $verifyScriptText -notmatch '& \$adb @adbTarget shell pm path \$package' -or
        $verifyScriptText -notmatch '& \$adb @adbTarget shell ime list -a' -or
        $verifyScriptText -notmatch '& \$adb @adbTarget shell pm dump \$package' -or
        $installScriptText -notmatch '& \$adb @adbTarget install -r \$apk' -or
        $installReleaseScriptText -notmatch '& \$adb @adbTarget install -r \$apk' -or
        $installScriptText -notmatch 'pm grant dev\.jorgex\.whspr android\.permission\.RECORD_AUDIO' -or
        $installReleaseScriptText -notmatch 'pm grant dev\.jorgex\.whspr android\.permission\.RECORD_AUDIO' -or
        $installScriptText -notmatch '\$micGrantedByAdb = \$LASTEXITCODE -eq 0' -or
        $installReleaseScriptText -notmatch '\$micGrantedByAdb = \$LASTEXITCODE -eq 0' -or
        $installScriptText -notmatch '-RequireMicrophone:\$micGrantedByAdb' -or
        $installReleaseScriptText -notmatch '-RequireMicrophone:\$micGrantedByAdb' -or
        $verifyScriptText -notmatch '\[switch\]\$RequireMicrophone' -or
        $verifyScriptText -notmatch 'appops get \$package RECORD_AUDIO' -or
        $verifyScriptText -notmatch 'WARN microphone permission' -or
        $installScriptText -notmatch 'am start -W -n dev\.jorgex\.whspr/\.MainActivity' -or
        $installReleaseScriptText -notmatch 'am start -W -n dev\.jorgex\.whspr/\.MainActivity' -or
        $buildReleaseScriptText -notmatch 'verify-apk\.ps1' -or
        $verifyApkScriptText -notmatch 'aapt2\.exe' -or
        $verifyApkScriptText -notmatch 'lib/arm64-v8a/libwhspr\.so' -or
        $verifyApkScriptText -notmatch 'Find-CompiledXml' -or
        $verifyApkScriptText -notmatch 'input_method' -or
        $verifyApkScriptText -notmatch 'recognition_service' -or
        $verifyApkScriptText -notmatch 'data_extraction_rules' -or
        $verifyApkScriptText -notmatch 'imeSubtypeMode' -or
        $verifyApkScriptText -notmatch 'selectableAsDefault' -or
        $verifyApkScriptText -notmatch 'WhsprRecognitionService' -or
        $verifyApkScriptText -notmatch 'WhsprInputMethodService'
    ) {
        Write-Host "MISS adb scripts -> expected serial targeting and explicit launch"
        $failed += 1
    } else {
        Write-Host "OK   adb scripts"
    }

    $stringsXml = Get-Content -Raw -Path ".\app\src\main\res\values\strings.xml"
    $stringNames = @{}
    [regex]::Matches($stringsXml, '<string\s+name="([A-Za-z0-9_]+)"') | ForEach-Object {
        $stringNames[$_.Groups[1].Value] = $true
    }
    $missingStrings = 0
    $stringRefPattern = '@string/([A-Za-z0-9_]+)|R\.string\.([A-Za-z0-9_]+)'
    Get-ChildItem -Path ".\app\src\main" -Recurse -File -Include "*.xml", "*.kt" | ForEach-Object {
        $file = $_
        $text = Get-Content -Raw -Path $file.FullName
        [regex]::Matches($text, $stringRefPattern) | ForEach-Object {
            $name = if ($_.Groups[1].Success) { $_.Groups[1].Value } else { $_.Groups[2].Value }
            if (-not $stringNames.ContainsKey($name)) {
                Write-Host "MISS string ref -> $($file.FullName): $name"
                $missingStrings += 1
            }
        }
    }
    if ($missingStrings -gt 0) {
        $failed += $missingStrings
    } else {
        Write-Host "OK   string refs"
    }

    $resourceFailed = 0
    $knownResources = @{}
    Get-ChildItem -Path ".\app\src\main\res" -Recurse -File | ForEach-Object {
        $type = Split-Path (Split-Path $_.FullName -Parent) -Leaf
        $baseType = ($type -split "-")[0]
        $name = [System.IO.Path]::GetFileNameWithoutExtension($_.Name)
        $knownResources["${baseType}/${name}"] = $true
    }
    $knownResources["style/AppTheme"] = $true
    $resourceRefPattern = '@(xml|mipmap|drawable|style)/([A-Za-z0-9_]+)'
    Get-ChildItem -Path ".\app\src\main" -Recurse -File -Include "*.xml" | ForEach-Object {
        $file = $_
        $text = Get-Content -Raw -Path $file.FullName
        [regex]::Matches($text, $resourceRefPattern) | ForEach-Object {
            $key = "$($_.Groups[1].Value)/$($_.Groups[2].Value)"
            if (-not $knownResources.ContainsKey($key)) {
                Write-Host "MISS resource ref -> $($file.FullName): $key"
                $resourceFailed += 1
            }
        }
    }
    if ($resourceFailed -gt 0) {
        $failed += $resourceFailed
    } else {
        Write-Host "OK   resource refs"
    }

    $manifestText = Get-Content -Raw -Path ".\app\src\main\AndroidManifest.xml"
    $missingComponents = 0
    [regex]::Matches($manifestText, 'android:name="(\.[A-Za-z0-9_]+|dev\.jorgex\.whspr\.[A-Za-z0-9_]+)"') | ForEach-Object {
        $component = $_.Groups[1].Value
        $className = if ($component.StartsWith(".")) { $component.Substring(1) } else { $component.Replace("dev.jorgex.whspr.", "") }
        $path = ".\app\src\main\java\dev\jorgex\whspr\$className.kt"
        if (-not (Test-Path $path)) {
            Write-Host "MISS manifest component -> $component"
            $missingComponents += 1
        }
    }
    if ($missingComponents -gt 0) {
        $failed += $missingComponents
    } else {
        Write-Host "OK   manifest components"
    }

    $activityNames = [regex]::Matches($manifestText, '<activity[\s\S]*?android:name="([^"]+)"') | ForEach-Object {
        $_.Groups[1].Value
    }
    $serviceNames = [regex]::Matches($manifestText, '<service[\s\S]*?android:name="([^"]+)"') | ForEach-Object {
        $_.Groups[1].Value
    }
    $expectedActivities = @(".MainActivity")
    $expectedServices = @(".WhsprInputMethodService", ".WhsprRecognitionService")
    $componentSurfaceFailed = 0
    foreach ($activity in $activityNames) {
        if ($expectedActivities -notcontains $activity) {
            Write-Host "MISS manifest activity -> unexpected $activity"
            $componentSurfaceFailed += 1
        }
    }
    foreach ($activity in $expectedActivities) {
        if ($activityNames -notcontains $activity) {
            Write-Host "MISS manifest activity -> missing $activity"
            $componentSurfaceFailed += 1
        }
    }
    foreach ($service in $serviceNames) {
        if ($expectedServices -notcontains $service) {
            Write-Host "MISS manifest service -> unexpected $service"
            $componentSurfaceFailed += 1
        }
    }
    foreach ($service in $expectedServices) {
        if ($serviceNames -notcontains $service) {
            Write-Host "MISS manifest service -> missing $service"
            $componentSurfaceFailed += 1
        }
    }
    if ($componentSurfaceFailed -gt 0) {
        $failed += $componentSurfaceFailed
    } else {
        Write-Host "OK   minimal manifest components"
    }

    $allowedPermissions = @(
        "android.permission.INTERNET",
        "android.permission.RECORD_AUDIO"
    )
    $permissions = [regex]::Matches($manifestText, '<uses-permission\s+android:name="([^"]+)"') | ForEach-Object {
        $_.Groups[1].Value
    }
    $permissionFailed = 0
    foreach ($permission in $permissions) {
        if ($allowedPermissions -notcontains $permission) {
            Write-Host "MISS manifest permission -> unexpected $permission"
            $permissionFailed += 1
        }
    }
    foreach ($permission in $allowedPermissions) {
        if ($permissions -notcontains $permission) {
            Write-Host "MISS manifest permission -> missing $permission"
            $permissionFailed += 1
        }
    }
    if ($permissionFailed -gt 0) {
        $failed += $permissionFailed
    } else {
        Write-Host "OK   minimal manifest permissions"
    }

    $backupRulesPath = ".\app\src\main\res\xml\data_extraction_rules.xml"
    if (
        $manifestText -notmatch 'android:allowBackup="false"' -or
        $manifestText -notmatch 'android:fullBackupContent="false"' -or
        $manifestText -notmatch 'android:dataExtractionRules="@xml/data_extraction_rules"' -or
        -not (Test-Path $backupRulesPath)
    ) {
        Write-Host "MISS manifest privacy -> allowBackup must stay false"
        $failed += 1
    } else {
        Write-Host "OK   manifest backup disabled"
    }

    if ($manifestText -match "BIND_RECOGNITION_SERVICE") {
        Write-Host "MISS manifest permission -> BIND_RECOGNITION_SERVICE is not an Android RecognitionService requirement"
        $failed += 1
    } else {
        Write-Host "OK   recognition service permission"
    }

    $recognitionOk =
        $manifestText -match 'android:name="\.WhsprRecognitionService"' -and
        $manifestText -match 'android:name="android\.speech"' -and
        $manifestText -match 'android:resource="@xml/recognition_service"' -and
        $manifestText -match 'android:name="android\.speech\.RecognitionService"'
    if (-not $recognitionOk) {
        Write-Host "MISS recognition service wiring"
        $failed += 1
    } else {
        Write-Host "OK   recognition service wiring"
    }

    $inputMethodText = Get-Content -Raw -Path ".\app\src\main\res\xml\input_method.xml"
    if ($inputMethodText -notmatch 'android:imeSubtypeMode="voice"') {
        Write-Host "MISS input method subtype -> expected voice"
        $failed += 1
    } elseif ($inputMethodText -match 'android:isAuxiliary="true"') {
        Write-Host "MISS input method subtype -> should be selectable, not auxiliary"
        $failed += 1
    } elseif ($inputMethodText -notmatch 'android:languageTag="es-ES"') {
        Write-Host "MISS input method subtype -> expected languageTag es-ES"
        $failed += 1
    } else {
        Write-Host "OK   input method voice subtype"
    }

    $recognitionServiceText = Get-Content -Raw -Path ".\app\src\main\java\dev\jorgex\whspr\WhsprRecognitionService.kt"
    if (
        $recognitionServiceText -notmatch 'ContextParams\.Builder' -or
        $recognitionServiceText -notmatch 'setNextAttributionSource' -or
        $recognitionServiceText -notmatch 'callingAttributionSource'
    ) {
        Write-Host "MISS recognition service attribution context"
        $failed += 1
    } else {
        Write-Host "OK   recognition service attribution context"
    }
    $audioRecorderText = Get-Content -Raw -Path ".\app\src\main\java\dev\jorgex\whspr\AudioRecorder.kt"
    $imeText = Get-Content -Raw -Path ".\app\src\main\java\dev\jorgex\whspr\WhsprInputMethodService.kt"
    if (
        $audioRecorderText -notmatch 'AudioRecord\.Builder' -or
        $audioRecorderText -notmatch 'setContext\(context\)' -or
        $audioRecorderText -notmatch 'Build\.VERSION_CODES\.S'
    ) {
        Write-Host "MISS audio recorder attribution context"
        $failed += 1
    } else {
        Write-Host "OK   audio recorder attribution context"
    }
    if (
        $audioRecorderText -notmatch '@Synchronized\s+fun start\(\): Boolean' -or
        $audioRecorderText -notmatch '@Synchronized\s+fun stop\(\): File\?' -or
        $audioRecorderText -notmatch 'worker = runCatching'
    ) {
        Write-Host "MISS audio recorder lifecycle guard"
        $failed += 1
    } else {
        Write-Host "OK   audio recorder lifecycle guard"
    }
    if (
        $audioRecorderText -notmatch 'private const val SAMPLE_RATE = 16_000' -or
        $audioRecorderText -notmatch 'private const val CHANNELS = 1' -or
        $audioRecorderText -notmatch 'private const val BITS_PER_SAMPLE = 16' -or
        $audioRecorderText -notmatch 'setAudioSource\(MediaRecorder\.AudioSource\.VOICE_RECOGNITION\)' -or
        $cpp -notmatch 'channels == 1' -or
        $cpp -notmatch 'sample_rate == 16000' -or
        $cpp -notmatch 'bits_per_sample == 16' -or
        $cpp -notmatch 'MAX_WAV_PCM_BYTES'
    ) {
        Write-Host "MISS audio format contract -> expected 16 kHz mono PCM16 WAV"
        $failed += 1
    } else {
        Write-Host "OK   audio format contract"
    }
    if (
        $audioRecorderText -notmatch 'File\.createTempFile\("whspr-dictation-"' -or
        $imeText -notmatch '(?s)finally\s*\{\s*runCatching\s*\{\s*audioFile\.delete\(\)\s*\}\s*\}' -or
        $recognitionServiceText -notmatch '(?s)finally\s*\{\s*runCatching\s*\{\s*audioFile\.delete\(\)\s*\}\s*\}'
    ) {
        Write-Host "MISS audio temp files -> expected unique temp WAV and cleanup"
        $failed += 1
    } else {
        Write-Host "OK   audio temp files"
    }
    if (
        $recognitionServiceText -notmatch 'onCheckRecognitionSupport' -or
        $recognitionServiceText -notmatch 'attributionSource: AttributionSource' -or
        $recognitionServiceText -notmatch 'RecognitionSupport\.Builder' -or
        $recognitionServiceText -notmatch 'onSupportResult'
    ) {
        Write-Host "MISS recognition service support check"
        $failed += 1
    } else {
        Write-Host "OK   recognition service support check"
    }
    if (
        $recognitionServiceText -match '@RequiresApi|androidx\.annotation\.RequiresApi|import android\.annotation\.RequiresApi' -or
        $recognitionServiceText -notmatch 'Build\.VERSION\.SDK_INT >= Build\.VERSION_CODES\.S' -or
        $recognitionServiceText -notmatch 'onCheckRecognitionSupport' -or
        $recognitionServiceText -notmatch 'onTriggerModelDownload'
    ) {
        Write-Host "MISS recognition service API guards"
        $failed += 1
    } else {
        Write-Host "OK   recognition service API guards"
    }
    if (
        $recognitionServiceText -notmatch 'onTriggerModelDownload' -or
        $recognitionServiceText -notmatch 'ModelDownloadListener' -or
        $recognitionServiceText -notmatch 'listener\.onSuccess' -or
        $recognitionServiceText -notmatch 'listener\.onScheduled' -or
        $recognitionServiceText -notmatch 'scheduleModelDownload' -or
        $recognitionServiceText -notmatch 'ERROR_NETWORK'
    ) {
        Write-Host "MISS recognition service model-download callback"
        $failed += 1
    } else {
        Write-Host "OK   recognition service model-download callback"
    }

    $syncReturnFailed = 0
    foreach ($file in $kotlinFiles) {
        $text = Get-Content -Raw -Path $file.FullName
        [regex]::Matches($text, 'synchronized\s*\([^\)]*\)\s*\{(?s:.*?)\}') | ForEach-Object {
            if ($_.Value -match '\breturn\b' -and $_.Value -notmatch 'return@') {
                Write-Host "MISS Kotlin synchronized return -> $($file.FullName)"
                $syncReturnFailed += 1
            }
        }
    }
    if ($syncReturnFailed -gt 0) {
        $failed += $syncReturnFailed
    } else {
        Write-Host "OK   no non-local synchronized returns"
    }

    $catalogText = Get-Content -Raw -Path ".\app\src\main\java\dev\jorgex\whspr\ModelCatalog.kt"
    $modelIds = [regex]::Matches($catalogText, 'id\s*=\s*"([^"]+)"') | ForEach-Object { $_.Groups[1].Value }
    $fileNames = [regex]::Matches($catalogText, 'fileName\s*=\s*"([^"]+)"') | ForEach-Object { $_.Groups[1].Value }
    $sha256s = [regex]::Matches($catalogText, 'sha256\s*=\s*"([^"]+)"') | ForEach-Object { $_.Groups[1].Value }
    $urls = [regex]::Matches($catalogText, 'url\s*=\s*"([^"]+)"') | ForEach-Object { $_.Groups[1].Value }
    $catalogFailed = 0
    if (($modelIds | Sort-Object -Unique).Count -ne $modelIds.Count) {
        Write-Host "MISS model catalog -> duplicate ids"
        $catalogFailed += 1
    }
    if (($fileNames | Sort-Object -Unique).Count -ne $fileNames.Count) {
        Write-Host "MISS model catalog -> duplicate file names"
        $catalogFailed += 1
    }
    if ($modelIds.Count -eq 0 -or $modelIds.Count -ne $fileNames.Count -or $modelIds.Count -ne $sha256s.Count -or $modelIds.Count -ne $urls.Count) {
        Write-Host "MISS model catalog -> incomplete model fields"
        $catalogFailed += 1
    }
    foreach ($sha256 in $sha256s) {
        if ($sha256 -notmatch '^[a-f0-9]{64}$') {
            Write-Host "MISS model catalog sha256 -> $sha256"
            $catalogFailed += 1
        }
    }
    foreach ($url in $urls) {
        if ($url -notmatch '^https://') {
            Write-Host "MISS model catalog url -> $url"
            $catalogFailed += 1
        }
    }
    if ($catalogFailed -gt 0) {
        $failed += $catalogFailed
    } else {
        Write-Host "OK   model catalog"
    }

    $modelStoreText = Get-Content -Raw -Path ".\app\src\main\java\dev\jorgex\whspr\ModelStore.kt"
    $nativeText = Get-Content -Raw -Path ".\app\src\main\cpp\native_whisper.cpp"
    $runtimeModelText = @(
        Get-Content -Raw -Path ".\app\src\main\java\dev\jorgex\whspr\MainActivity.kt"
        Get-Content -Raw -Path ".\app\src\main\java\dev\jorgex\whspr\WhsprInputMethodService.kt"
        Get-Content -Raw -Path ".\app\src\main\java\dev\jorgex\whspr\WhsprRecognitionService.kt"
    ) -join "`n"
    if (
        $modelStoreText -notmatch 'fun isReady\(model: SpeechModel\): Boolean' -or
        $modelStoreText -notmatch 'hasExpectedSha256\(model\)' -or
        $runtimeModelText -notmatch 'hasExpectedSha256\(model\)' -or
        $runtimeModelText -notmatch 'transcriber\.transcribe'
    ) {
        Write-Host "MISS model readiness -> Transcription must require SHA256"
        $failed += 1
    } else {
        Write-Host "OK   model readiness"
    }
    $localTranscriberText = Get-Content -Raw -Path ".\app\src\main\java\dev\jorgex\whspr\LocalTranscriber.kt"
    if (
        $localTranscriberText -notmatch 'fun transcribe\(audioFile: File, modelFile: File, language: String\): String\?' -or
        $localTranscriberText -notmatch 'private external fun transcribeNative\(audioPath: String, modelPath: String, language: String\): String\?' -or
        $nativeText -notmatch 'return nullptr' -or
        $imeText -notmatch 'R\.string\.error_no_match' -or
        $recognitionServiceText -notmatch 'ERROR_NO_MATCH' -or
        $recognitionServiceText -notmatch 'ERROR_CLIENT' -or
        $localTranscriberText -notmatch 'System\.loadLibrary\("whspr"\)' -or
        $localTranscriberText -notmatch 'if \(!available\) return null' -or
        $localTranscriberText -notmatch '\?\.trim\(\)'
    ) {
        Write-Host "MISS transcription errors -> expected null technical errors and empty no-match"
        $failed += 1
    } else {
        Write-Host "OK   transcription errors"
    }
    if (
        $runtimeModelText -notmatch 'sessionModelId' -or
        $runtimeModelText -notmatch 'settings\.modelId != sessionModelId' -or
        $runtimeModelText -match 'if \(modelOk && settings\.modelId == sessionModelId\)' -or
        $imeText -notmatch 'if \(session != inputSession\) return@post' -or
        $recognitionServiceText -notmatch 'session == recognitionSession' -or
        $recognitionServiceText -notmatch 'currentCallback === listener'
    ) {
        Write-Host "MISS model session guard"
        $failed += 1
    } else {
        Write-Host "OK   model session guard"
    }
    if ($imeText -notmatch 'dictationModelId = null') {
        Write-Host "MISS IME session cleanup"
        $failed += 1
    } else {
        Write-Host "OK   IME session cleanup"
    }
    if (
        $imeText -notmatch 'isSecureInput = attribute\?\.let \{ isPasswordInput\(it\.inputType\) \} \?: false' -or
        $imeText -notmatch 'micButton\?\.isEnabled = !isSecureInput && !isProcessing' -or
        $imeText -notmatch 'private fun isPasswordInput\(inputType: Int\): Boolean'
    ) {
        Write-Host "MISS secure input guard -> IME must not dictate into password fields"
        $failed += 1
    } else {
        Write-Host "OK   secure input guard"
    }

    $rootBuild = Get-Content -Raw -Path ".\build.gradle.kts"
    $appBuild = Get-Content -Raw -Path ".\app\build.gradle.kts"
    $buildText = "$rootBuild`n$appBuild"
    $dependencyFailed = 0
    if ($buildText -match 'androidx\.|compose|implementation\s*\(') {
        Write-Host "MISS dependencies -> keep Whspr dependency-free beyond Android/Kotlin/NDK"
        $dependencyFailed += 1
    }
    if ($buildText -match 'abiFilters\.add\("arm64-v8a"\)') {
        Write-Host "OK   arm64-only build"
    } else {
        Write-Host "MISS native build -> expected arm64-v8a only"
        $dependencyFailed += 1
    }
    $cmakeText = Get-Content -Raw -Path ".\app\src\main\cpp\CMakeLists.txt"
    if (
        $cmakeText -notmatch 'set\(GGML_CPU_ARM_ARCH "armv8-a"' -or
        $cmakeText -notmatch 'set\(GGML_ACCELERATE OFF' -or
        $cmakeText -notmatch 'set\(GGML_OPENMP OFF'
    ) {
        Write-Host "MISS native build -> expected CPU-only Android flags"
        $dependencyFailed += 1
    } else {
        Write-Host "OK   CPU-only native flags"
    }
    if ($dependencyFailed -gt 0) {
        $failed += $dependencyFailed
    } else {
        Write-Host "OK   minimal dependencies"
    }

    if (
        $appBuild -notmatch 'release\s*\{' -or
        $appBuild -notmatch 'signingConfig = signingConfigs\.getByName\("debug"\)' -or
        $appBuild -notmatch 'isMinifyEnabled = true' -or
        $appBuild -notmatch 'isShrinkResources = true' -or
        $appBuild -notmatch 'proguard-rules\.pro' -or
        -not (Test-Path ".\app\proguard-rules.pro") -or
        (Get-Content -Raw -Path ".\app\proguard-rules.pro") -notmatch 'NativeWhisper'
    ) {
        Write-Host "MISS release build -> expected signed minified local APK"
        $failed += 1
    } else {
        Write-Host "OK   signed minified release build"
    }

    $wrapperText = Get-Content -Raw -Path ".\gradle\wrapper\gradle-wrapper.properties"
    $versionFailed = 0
    foreach ($expected in @(
        'compileSdk = 36',
        'targetSdk = 36',
        'minSdk = 28',
        'ndkVersion = "28.2.13676358"',
        'id("com.android.application") version "9.2.0"',
        'sourceCompatibility = JavaVersion.VERSION_17',
        'targetCompatibility = JavaVersion.VERSION_17',
        'gradle-9.4.1-bin.zip',
        'distributionSha256Sum=2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb'
    )) {
        if ("$buildText`n$wrapperText" -notmatch [regex]::Escape($expected)) {
            Write-Host "MISS build version -> $expected"
            $versionFailed += 1
        }
    }
    if ($versionFailed -gt 0) {
        $failed += $versionFailed
    } else {
        Write-Host "OK   pinned build versions"
    }

    if ($rootBuildText -match 'org\.jetbrains\.kotlin\.android' -or $buildText -match 'org\.jetbrains\.kotlin\.android') {
        Write-Host "MISS AGP 9 built-in Kotlin -> remove org.jetbrains.kotlin.android plugin"
        $failed += 1
    } else {
        Write-Host "OK   AGP 9 built-in Kotlin"
    }

    $gitignoreText = Get-Content -Raw -Path ".\.gitignore"
    $gitignoreFailed = 0
    foreach ($entry in @(".gradle/", "build/", "app/build/", "app/.cxx/", "local.properties", ".idea/")) {
        if ($gitignoreText -notmatch [regex]::Escape($entry)) {
            Write-Host "MISS gitignore -> $entry"
            $gitignoreFailed += 1
        }
    }
    if ($gitignoreFailed -gt 0) {
        $failed += $gitignoreFailed
    } else {
        Write-Host "OK   gitignore Android outputs"
    }

    $networkFailed = 0
    $directNetworkPattern = "OkHttp|Retrofit|HttpURLConnection|java\.net\.URL|java\.net\.Socket|DatagramSocket|Firebase|Analytics|Crashlytics|Telemetry"
    Get-ChildItem -Path ".\app\src\main\java" -Recurse -Filter "*.kt" | ForEach-Object {
        $text = Get-Content -Raw -Path $_.FullName
        if ($text -match $directNetworkPattern) {
            Write-Host "MISS direct network/telemetry -> $($_.FullName)"
            $networkFailed += 1
        }
    }
    if ($networkFailed -gt 0) {
        $failed += $networkFailed
    } else {
        Write-Host "OK   no direct network or telemetry clients"
    }

    $packageName = "dev.jorgex.whspr"
    $identityFailed = 0
    if ($appBuild -notmatch "namespace\s*=\s*`"$packageName`"") {
        Write-Host "MISS app identity -> namespace"
        $identityFailed += 1
    }
    if ($appBuild -notmatch "applicationId\s*=\s*`"$packageName`"") {
        Write-Host "MISS app identity -> applicationId"
        $identityFailed += 1
    }
    Get-ChildItem -Path ".\app\src\main\java\dev\jorgex\whspr" -Filter "*.kt" | ForEach-Object {
        $text = Get-Content -Raw -Path $_.FullName
        if ($text -notmatch "package $packageName") {
            Write-Host "MISS app identity -> $($_.FullName)"
            $identityFailed += 1
        }
    }
    if ($nativeText -notmatch 'ExceptionCheck\(\)' -or $nativeText -notmatch 'ExceptionClear\(\)') {
        Write-Host "MISS native JNI exception guard"
        $failed += 1
    } else {
        Write-Host "OK   native JNI exception guard"
    }
    if (
        $nativeText -notmatch '#include <sys/stat\.h>' -or
        $nativeText -notmatch 'struct ModelFingerprint' -or
        $nativeText -notmatch 'st_size' -or
        $nativeText -notmatch 'st_mtime' -or
        $nativeText -notmatch 'same_model' -or
        $nativeText -notmatch 'std::lock_guard<std::mutex> lock\(transcribe_mutex\(\)\);'
    ) {
        Write-Host "MISS native model cache -> expected serialized path/size/mtime fingerprint"
        $failed += 1
    } else {
        Write-Host "OK   native model cache"
    }
    if ($nativeText -notmatch "Java_dev_jorgex_whspr_NativeWhisper_transcribeNative") {
        Write-Host "MISS app identity -> JNI function"
        $identityFailed += 1
    }
    if ($identityFailed -gt 0) {
        $failed += $identityFailed
    } else {
        Write-Host "OK   app identity"
    }

    $markerPattern = ("TO" + "DO") + "|" +
        ("FIX" + "ME") + "|" +
        ("place" + "holder") + "|" +
        ("old" + " package") + "|" +
        ("dev" + "\.example")
    $markers = Get-ChildItem -Path ".\app\src", ".\scripts" -Recurse -File |
        Select-String -Pattern $markerPattern
    if ($markers) {
        $markers | ForEach-Object { Write-Host "MISS marker -> $($_.Path):$($_.LineNumber): $($_.Line)" }
        $failed += 1
    } else {
        Write-Host "OK   no stale markers"
    }

    $vendorRefs = Get-ChildItem -Path ".\third_party\whisper.cpp" -Recurse -File -Include "CMakeLists.txt", "*.cmake" |
        Select-String -Pattern "arch/(x86|powerpc|riscv|s390|wasm|loongarch)|ggml-cpu/(cmake|kleidiai|llamafile|spacemit|amx)|coreml/|openvino/|bindings/javascript|add_subdirectory\((tests|examples)"
    if ($vendorRefs) {
        $vendorRefs | ForEach-Object { Write-Host "MISS vendor ref -> $($_.Path):$($_.LineNumber): $($_.Line)" }
        $failed += 1
    } else {
        Write-Host "OK   no stale vendor CMake refs"
    }

    if ($failed -gt 0) {
        exit 1
    }
    exit 0
} finally {
    Pop-Location
}
