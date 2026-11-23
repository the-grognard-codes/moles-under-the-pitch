#Requires -Version 5.1
[CmdletBinding()]
param(
    # Defaults to the pinned Windows JDK; supply an existing JDK on other hosts.
    [string]$JavaHome,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$toolRoot = Join-Path $root '.tools'
$manifest = Get-Content (Join-Path $PSScriptRoot 'target-build-toolchain.json') -Raw | ConvertFrom-Json
$windowsHost = [Environment]::OSVersion.Platform -eq 'Win32NT'
New-Item -ItemType Directory -Force -Path (Join-Path $toolRoot 'downloads') | Out-Null

function Assert-ArchiveHash($Path, $Hash, $Label) {
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA512).Hash -ne $Hash) {
        throw [IO.InvalidDataException]::new("Checksum mismatch: $Label. Nothing was extracted.")
    }
}

function Get-RetryDelay($Response, $Attempt) {
    $delay = [math]::Pow(2, $Attempt - 1)
    $retryAfter = $null
    if ($Response -is [Net.HttpWebResponse]) {
        $retryAfter = $Response.Headers['Retry-After']
    } elseif ($Response) {
        $values = $null
        if ($Response.Headers.TryGetValues('Retry-After', [ref]$values)) {
            $retryAfter = $values | Select-Object -First 1
        }
    }

    $seconds = 0L
    $date = [DateTimeOffset]::MinValue
    if ([long]::TryParse($retryAfter, [ref]$seconds)) {
        $delay = [math]::Max($delay, [double]$seconds)
    } elseif ([DateTimeOffset]::TryParse($retryAfter, [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::AssumeUniversal, [ref]$date)) {
        $delay = [math]::Max($delay, ($date - [DateTimeOffset]::UtcNow).TotalSeconds)
    }
    return [int][math]::Ceiling([math]::Min(60, $delay))
}

function Save-Archive($Urls, $Archive, $Hash) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $temporaryArchive = "$Archive.$([Guid]::NewGuid().ToString('N')).tmp"
    $maxAttempts = 5
    $sourceReports = [Collections.Generic.List[string]]::new()
    :sources foreach ($url in $Urls) {
        for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
            $requestException = $null
            try {
                Write-Host "Downloading $url (attempt $attempt/$maxAttempts)"
                try {
                    $download = Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $temporaryArchive -PassThru -TimeoutSec 30
                    # Windows PowerShell can return successfully after a short response body.
                    $length = $download.Headers['Content-Length'] | Select-Object -First 1
                    if ($length -and !$download.Headers['Content-Encoding'] -and
                        (Get-Item -LiteralPath $temporaryArchive).Length -lt [long]$length) {
                        throw [IO.EndOfStreamException]::new('The response ended before the declared Content-Length.')
                    }
                } catch {
                    $requestException = $_.Exception
                }
                if (!$requestException) {
                    try {
                        Assert-ArchiveHash $temporaryArchive $Hash $url
                    } catch [IO.InvalidDataException] {
                        $sourceReports.Add("${url}: checksum mismatch")
                        break sources
                    }
                    Move-Item -LiteralPath $temporaryArchive -Destination $Archive -Force
                    return
                }
            } finally {
                if (Test-Path -LiteralPath $temporaryArchive) {
                    Remove-Item -LiteralPath $temporaryArchive
                }
            }

            $response = $requestException.Response
            if ($response) {
                $status = [int]$response.StatusCode
                $lastError = "HTTP $status"
                $retryable = $status -eq 429 -or ($status -ge 500 -and $status -lt 600)
            } else {
                # A failed response-body read can wrap the network error in an AggregateException.
                $cause = $requestException.GetBaseException()
                $lastError = $cause.Message
                $retryable = $requestException -is [Net.WebException] -or $cause -is [IO.IOException] -or
                    $cause -is [Net.Sockets.SocketException] -or $cause -is [OperationCanceledException] -or
                    $cause -is [TimeoutException] -or
                    ($PSVersionTable.PSVersion.Major -ge 7 -and $requestException -is [Net.Http.HttpRequestException])
            }
            if (!$retryable -or $attempt -eq $maxAttempts) { break }
            $delay = Get-RetryDelay $response $attempt
            Write-Host "Download failed ($lastError). Retrying in $delay seconds."
            Start-Sleep -Seconds $delay
        }
        $sourceReports.Add("${url}: $lastError")
    }
    throw "Archive download failed. Sources tried: $($sourceReports -join '; '). Nothing was extracted. Rerun bootstrap to retry."
}

function Install-Archive($Specification, $AuditArchive) {
    $archive = Join-Path $toolRoot ('downloads/' + $Specification.archive)
    $hash = $Specification.sha512
    if (Test-Path -LiteralPath $archive) {
        Assert-ArchiveHash $archive $hash "$archive (remove this archive and retry)"
    } elseif (Test-Path -LiteralPath $AuditArchive) {
        Write-Host "Reusing audit archive: $AuditArchive"
        Assert-ArchiveHash $AuditArchive $hash $AuditArchive
        Copy-Item -LiteralPath $AuditArchive -Destination $archive
    } elseif ($Offline) {
        throw "Offline setup needs $archive. Run bootstrap once online or supply the pinned archive."
    } else {
        Save-Archive $Specification.urls $archive $hash
    }
    Write-Host "Verified SHA512 for $($Specification.archive)"
    $destination = Join-Path $toolRoot $Specification.directory
    $marker = Join-Path $destination '.bootstrap-complete'
    if (!(Test-Path -LiteralPath $marker)) {
        if (Test-Path -LiteralPath $destination) {
            throw "Incomplete tool directory: $destination. Move it aside and rerun bootstrap."
        }
        Expand-Archive -LiteralPath $archive -DestinationPath $toolRoot
        Set-Content -LiteralPath $marker -Value $hash -Encoding Ascii
    }
}

$audit = Join-Path $root '.notes/overhaul-analysis/verification'
Install-Archive $manifest.maven (Join-Path $audit $manifest.maven.archive)

if (!$JavaHome) {
    if (!$windowsHost) {
        throw 'Supply -JavaHome pointing to a JDK 21 or newer. See tools/setup-ubuntu.sh for the pinned Linux JDK.'
    }
    $JavaHome = $manifest.java.defaultWindowsHome
}
$JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
# Validate before saving configuration; the build also checks on every run.
. (Join-Path $PSScriptRoot 'check-java.ps1')
Assert-SupportedJava $JavaHome $manifest.java | Out-Null
Set-Content -LiteralPath (Join-Path $toolRoot 'java-home.txt') -Value $JavaHome -Encoding UTF8
Write-Host "Ready. Run ./tools/target-build.ps1 info, test, install or verify."
