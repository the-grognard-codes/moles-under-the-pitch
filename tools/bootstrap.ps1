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

function Assert-ArchiveHash($Path, $Algorithm, $Hash, $Source) {
    if ((Get-FileHash -LiteralPath $Path -Algorithm $Algorithm).Hash -ne $Hash) {
        throw "Checksum mismatch: $Source. Nothing was extracted."
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

function Save-Archive($Urls, $Archive, $Algorithm, $Hash) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $temporaryArchive = "$Archive.$([Guid]::NewGuid().ToString('N')).tmp"
    $failures = foreach ($url in $Urls) {
        for ($attempt = 1; $attempt -le 5; $attempt++) {
            $failure = $null
            try {
                Write-Host "Downloading $url (attempt $attempt/5)"
                try {
                    $download = Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $temporaryArchive -PassThru -TimeoutSec 30
                    # Windows PowerShell can return successfully after a short response body.
                    $length = $download.Headers['Content-Length'] | Select-Object -First 1
                    if ($length -and !$download.Headers['Content-Encoding'] -and
                        (Get-Item -LiteralPath $temporaryArchive).Length -lt [long]$length) {
                        throw [IO.EndOfStreamException]::new('The response ended before the declared Content-Length.')
                    }
                } catch {
                    $failure = $_.Exception
                }
                if (!$failure) {
                    Assert-ArchiveHash $temporaryArchive $Algorithm $Hash $url
                    Move-Item -LiteralPath $temporaryArchive -Destination $Archive -Force
                    return
                }
            } finally {
                if (Test-Path -LiteralPath $temporaryArchive) {
                    Remove-Item -LiteralPath $temporaryArchive
                }
            }

            $response = $failure.Response
            if ($response) {
                $status = [int]$response.StatusCode
                $lastStatus = "HTTP $status"
                $retryable = $status -eq 429 -or ($status -ge 500 -and $status -lt 600)
            } else {
                # A failed response-body read can wrap the network error in an AggregateException.
                $cause = $failure.GetBaseException()
                $lastStatus = $cause.Message
                $retryable = $failure -is [Net.WebException] -or $cause -is [IO.IOException] -or
                    $cause -is [Net.Sockets.SocketException] -or $cause -is [OperationCanceledException] -or
                    $cause -is [TimeoutException] -or
                    ($PSVersionTable.PSVersion.Major -ge 7 -and $failure -is [Net.Http.HttpRequestException])
            }
            if (!$retryable -or $attempt -eq 5) { break }
            $delay = Get-RetryDelay $response $attempt
            Write-Host "Download failed ($lastStatus). Retrying in $delay seconds."
            Start-Sleep -Seconds $delay
        }
        "${url}: $lastStatus"
    }
    throw "Archive download failed. Sources tried: $($failures -join '; ')"
}

function Install-Archive($Specification, $Algorithm, $Hash, $AuditArchive, $Directory) {
    $archive = Join-Path $toolRoot ('downloads/' + $Specification.archive)
    if (Test-Path -LiteralPath $archive) {
        Assert-ArchiveHash $archive $Algorithm $Hash "$archive (remove this archive and retry)"
    } elseif (Test-Path -LiteralPath $AuditArchive) {
        Write-Host "Reusing audit archive: $AuditArchive"
        Assert-ArchiveHash $AuditArchive $Algorithm $Hash $AuditArchive
        Copy-Item -LiteralPath $AuditArchive -Destination $archive
    } elseif ($Offline) {
        throw "Offline setup needs $archive. Run bootstrap once online or supply the pinned archive."
    } else {
        Save-Archive $Specification.urls $archive $Algorithm $Hash
    }
    Write-Host "Verified $Algorithm for $($Specification.archive)"
    $destination = Join-Path $toolRoot $Directory
    $marker = Join-Path $destination '.bootstrap-complete'
    if (!(Test-Path -LiteralPath $marker)) {
        if (Test-Path -LiteralPath $destination) {
            throw "Incomplete tool directory: $destination. Move it aside and rerun bootstrap."
        }
        Expand-Archive -LiteralPath $archive -DestinationPath $toolRoot
        Set-Content -LiteralPath $marker -Value $Hash -Encoding Ascii
    }
}

$audit = Join-Path $root '.notes/overhaul-analysis/verification'
Install-Archive $manifest.maven 'SHA512' $manifest.maven.sha512 `
    (Join-Path $audit $manifest.maven.archive) $manifest.maven.directory

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
