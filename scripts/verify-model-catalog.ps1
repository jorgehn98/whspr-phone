param(
    [string]$CatalogPath = (Join-Path $PSScriptRoot "..\app\src\main\java\dev\jorgex\whspr\ModelCatalog.kt"),
    [int]$TimeoutSec = 20
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

try {
    [System.Net.ServicePointManager]::SecurityProtocol = (
        [System.Net.ServicePointManager]::SecurityProtocol -bor
        [System.Net.SecurityProtocolType]::Tls12
    )
} catch {
    # If the runtime does not allow it, we keep going: Invoke-WebRequest will try anyway.
}

function Get-FieldValue {
    param(
        [string]$Body,
        [string]$FieldName
    )

    $pattern = "(?m)^\s*$FieldName\s*=\s*""(?<value>(?:\\.|[^""])*)"""
    $match = [regex]::Match($Body, $pattern)
    if (-not $match.Success) {
        throw "Could not read field '$FieldName' from ModelCatalog.kt."
    }

    return $match.Groups["value"].Value
}

function Get-LongExpressionValue {
    param(
        [string]$Body,
        [string]$FieldName
    )

    $pattern = "(?m)^\s*$FieldName\s*=\s*(?<expr>[^,]+)"
    $match = [regex]::Match($Body, $pattern)
    if (-not $match.Success) {
        throw "Could not read field '$FieldName' from ModelCatalog.kt."
    }

    $expr = $match.Groups["expr"].Value.Trim()
    $value = [int64]1
    foreach ($part in ($expr -split '\*')) {
        $token = $part.Trim().TrimEnd('L')
        if (-not $token) {
            continue
        }

        $factor = [int64]0
        if (-not [int64]::TryParse($token, [ref]$factor)) {
            throw "Could not evaluate expression '$expr' for '$FieldName'."
        }

        $value *= $factor
    }

    return $value
}

function Get-HeaderLong {
    param(
        [object]$Headers,
        [string]$Name
    )

    $rawValue = $Headers[$Name]
    if (-not $rawValue) {
        return $null
    }

    $parsed = [int64]0
    if ([int64]::TryParse($rawValue.ToString().Trim(), [ref]$parsed)) {
        return $parsed
    }

    return $null
}

function Get-RemoteSizeBytes {
    param(
        [string]$Url,
        [int]$TimeoutSec
    )

    $attempts = @(
        @{ Method = "HEAD"; Range = $false; Label = "HEAD" },
        @{ Method = "GET"; Range = $true; Label = "GET with Range" }
    )

    foreach ($attempt in $attempts) {
        $response = $null
        try {
            $request = [System.Net.HttpWebRequest]::Create($Url)
            $request.Method = $attempt.Method
            $request.AllowAutoRedirect = $true
            $request.MaximumAutomaticRedirections = 5
            $request.Timeout = $TimeoutSec * 1000
            $request.ReadWriteTimeout = $TimeoutSec * 1000
            if ($attempt.Range) {
                $request.AddRange(0, 0)
            }

            $response = [System.Net.HttpWebResponse]$request.GetResponse()
            $statusCode = [int]$response.StatusCode
            if ($statusCode -lt 200 -or $statusCode -ge 400) {
                throw "HTTP $statusCode"
            }

            $contentRange = $response.Headers["Content-Range"]
            if ($contentRange -and $contentRange -match '/(?<size>\d+)$') {
                return [int64]$Matches["size"]
            }

            $contentLength = Get-HeaderLong -Headers $response.Headers -Name "Content-Length"
            if ($null -ne $contentLength) {
                return $contentLength
            }
        } catch {
            $lastError = $_.Exception.Message
        } finally {
            if ($response) {
                $response.Close()
            }
        }
    }

    throw "Could not get remote size for '$Url'. Last error: $lastError"
}

if (-not (Test-Path $CatalogPath)) {
    throw "Catalog file does not exist at '$CatalogPath'."
}

$catalogText = Get-Content -Raw -Encoding UTF8 -Path $CatalogPath
$modelRegex = [regex]'(?s)SpeechModel\s*\((?<body>.*?)\)\s*,'
$modelMatches = $modelRegex.Matches($catalogText)
if ($modelMatches.Count -eq 0) {
    throw "No models found in '$CatalogPath'."
}

Write-Host "Verifying model catalog..."
Write-Host "Archivo: $CatalogPath"
Write-Host ""

$failed = 0
foreach ($match in $modelMatches) {
    $body = $match.Groups["body"].Value
    $id = Get-FieldValue -Body $body -FieldName "id"
    $label = Get-FieldValue -Body $body -FieldName "label"
    $minBytes = Get-LongExpressionValue -Body $body -FieldName "minBytes"
    $url = Get-FieldValue -Body $body -FieldName "url"

    $uri = $null
    if (-not [Uri]::TryCreate($url, [UriKind]::Absolute, [ref]$uri)) {
        Write-Host "MISS $id -> invalid URL: $url"
        $failed += 1
        continue
    }

    if ($uri.Scheme -ne "https") {
        Write-Host "MISS $id -> URL is not HTTPS: $url"
        $failed += 1
        continue
    }

    try {
        $remoteSize = Get-RemoteSizeBytes -Url $url -TimeoutSec $TimeoutSec
        if ($remoteSize -lt $minBytes) {
            Write-Host "MISS $id -> $label"
            Write-Host "  URL: $url"
            Write-Host "  Remote size: $remoteSize bytes"
            Write-Host "  Minimum expected: $minBytes bytes"
            $failed += 1
            continue
        }

        Write-Host "OK   $id -> $label ($remoteSize bytes)"
    } catch {
        Write-Host "MISS $id -> $label"
        Write-Host "  URL: $url"
        Write-Host "  Error: $($_.Exception.Message)"
        $failed += 1
    }
}

if ($failed -gt 0) {
    Write-Host ""
    Write-Host "Model catalog has errors: $failed"
    exit 1
}

Write-Host ""
Write-Host "Model catalog OK"
