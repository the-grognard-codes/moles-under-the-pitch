#Requires -Version 5.1
# Integration checks in an isolated throwaway project, including paths with
# spaces. Requires a completed bootstrap. Network checks use loopback only.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$manifest = Get-Content (Join-Path $PSScriptRoot 'target-build-toolchain.json') -Raw | ConvertFrom-Json
$javaHome = (Get-Content (Join-Path $root '.tools/java-home.txt') -Raw).Trim()
$fixture = Join-Path $root ('.tools/tooling checks/' + [Guid]::NewGuid().ToString('N'))
$fixtureTools = Join-Path $fixture 'tools'
New-Item -ItemType Directory -Force -Path $fixtureTools, (Join-Path $fixture '.mvn') | Out-Null
foreach ($file in @('bootstrap.ps1', 'build.ps1', 'target-build.ps1', 'check-java.ps1', 'target-build-toolchain.json')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $file) -Destination $fixtureTools
}
Copy-Item -LiteralPath (Join-Path $root '.mvn/settings.xml') -Destination (Join-Path $fixture '.mvn/settings.xml')
$shell = (Get-Process -Id $PID).Path
$bootstrap = Join-Path $fixtureTools 'bootstrap.ps1'
$build = Join-Path $fixtureTools 'build.ps1'
$checks = 0
function Assert-Result($Condition, $Message) {
    if (!$Condition) { throw $Message }
    Write-Host "PASS: $Message"
    $script:checks++
}
function Invoke-CheckProcess([string]$Script, [string[]]$Arguments, [string]$Log) {
    # Expected failures write native stderr. Windows PowerShell turns redirected
    # stderr into ErrorRecords; inspect the process exit code instead.
    $ErrorActionPreference = 'Continue'
    $PSNativeCommandUseErrorActionPreference = $false
    & $shell -NoProfile -File $Script @Arguments *> (Join-Path $fixture $Log)
    return $LASTEXITCODE
}

function Invoke-DownloadCheck($Urls) {
    $manifest.maven.urls = $Urls
    $manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $fixtureTools 'target-build-toolchain.json')
    if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive }
    $waits = [Collections.Generic.List[int]]::new()
    # Record the requested waits without sleeping so the 60-second cap can be tested.
    function Start-Sleep([int]$Seconds) { $waits.Add($Seconds) }
    $failure = $null
    try { & $bootstrap -JavaHome $javaHome } catch { $failure = $_.Exception.Message }
    return @{ Failure = $failure; Waits = $waits }
}

function Test-ArchiveDownloads {
    $server = Start-Job -ArgumentList (Join-Path $root ('.tools/downloads/' + $manifest.maven.archive)) -ScriptBlock {
        param($ArchivePath)
        $ErrorActionPreference = 'Stop'
        $zip = [IO.File]::ReadAllBytes($ArchivePath)
        # Use a raw listener so a response can intentionally end before Content-Length.
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
        $listener.Start()
        try {
            "http://127.0.0.1:$($listener.LocalEndpoint.Port)"
            $retryRequests = 0
            while ($true) {
                if (!$listener.Pending()) {
                    Start-Sleep -Milliseconds 20
                    continue
                }
                $client = $listener.AcceptTcpClient()
                try {
                    $client.ReceiveTimeout = 5000
                    $stream = $client.GetStream()
                    $reader = [IO.StreamReader]::new($stream)
                    $path = ($reader.ReadLine() -split ' ')[1]
                    while ($reader.ReadLine()) { }

                    $status = '200 OK'
                    $extraHeaders = ''
                    $body = $zip
                    $truncated = $false
                    switch ($path) {
                        '/retry' {
                            $retryRequests++
                            if ($retryRequests -le 3) {
                                $status = '429 Too Many Requests'
                                $body = [byte[]]@()
                                $retryAfter = switch ($retryRequests) {
                                    1 { '3' }
                                    2 { [DateTime]::UtcNow.AddSeconds(30).ToString('r') }
                                    3 { '120' }
                                }
                                $extraHeaders = "Retry-After: $retryAfter`r`n"
                            }
                        }
                        '/unavailable' { $status = '503 Service Unavailable'; $body = [byte[]]@() }
                        '/valid' { }
                        '/corrupt' { $body = [Text.Encoding]::ASCII.GetBytes('invalid archive') }
                        '/truncated' { $truncated = $true; $body = [byte[]]@(80, 75) }
                        default { $status = '404 Not Found'; $body = [byte[]]@() }
                    }
                    $length = $body.Length
                    if ($truncated) { $length = $zip.Length }
                    $headers = [Text.Encoding]::ASCII.GetBytes("HTTP/1.1 $status`r`nConnection: close`r`nContent-Length: $length`r`n$extraHeaders`r`n")
                    $stream.Write($headers, 0, $headers.Length)
                    $stream.Write($body, 0, $body.Length)
                } finally {
                    $client.Dispose()
                }
            }
        } finally {
            $listener.Stop()
        }
    }

    try {
        $deadline = [DateTime]::UtcNow.AddSeconds(20)
        do {
            $baseUrl = Receive-Job $server
            if ($baseUrl) { break }
            Start-Sleep -Milliseconds 100
        } while ($server.State -in @('NotStarted', 'Running') -and [DateTime]::UtcNow -lt $deadline)
        Assert-Result ($baseUrl -like 'http://127.0.0.1:*') 'Local archive HTTP stub started'

        $result = Invoke-DownloadCheck @("$baseUrl/retry")
        Assert-Result (!$result.Failure -and (Test-Path -LiteralPath $archive)) 'Bootstrap retries HTTP 429 and installs the verified archive'
        Assert-Result ($result.Waits.Count -eq 3 -and $result.Waits[0] -eq 3 -and
            $result.Waits[1] -ge 25 -and $result.Waits[1] -le 30 -and $result.Waits[2] -eq 60) `
            'Bootstrap honors Retry-After seconds and HTTP dates and caps each wait at 60 seconds'

        $result = Invoke-DownloadCheck @("$baseUrl/unavailable", "$baseUrl/valid")
        Assert-Result (!$result.Failure -and ($result.Waits -join ',') -eq '1,2,4,8' -and (Test-Path -LiteralPath $archive)) `
            'Bootstrap uses the fallback after five primary attempts with exponential backoff'

        $result = Invoke-DownloadCheck @("$baseUrl/unavailable", "$baseUrl/corrupt")
        Assert-Result ($result.Failure -eq "Checksum mismatch: $baseUrl/corrupt. Nothing was extracted." -and
            !(Test-Path -LiteralPath $archive)) 'Bootstrap rejects a corrupt fallback before publishing the archive'

        $result = Invoke-DownloadCheck @("$baseUrl/truncated", "$baseUrl/missing")
        Assert-Result ($result.Failure -like "Archive download failed. Sources tried: $baseUrl/truncated`: *; $baseUrl/missing`: HTTP 404" -and
            ($result.Waits -join ',') -eq '1,2,4,8') `
            "Bootstrap retries interrupted transfers and reports the final failure for each URL. Observed: $($result.Failure)"
        Assert-Result (!(Get-ChildItem -LiteralPath (Split-Path -Parent $archive) -Force)) `
            'Failed transfers leave no archive or temporary files'
    } finally {
        Remove-Job $server -Force
    }
}

$checkExit = Invoke-CheckProcess $build @('info') 'missing-setup.log'
Assert-Result ($checkExit -ne 0) 'Build rejects missing setup'
$checkExit = Invoke-CheckProcess $bootstrap @('-JavaHome', $javaHome, '-Offline') 'offline-missing.log'
Assert-Result ($checkExit -ne 0) 'Offline bootstrap rejects a missing archive'
$archive = Join-Path $fixture ('.tools/downloads/' + $manifest.maven.archive)
Set-Content -LiteralPath $archive -Value 'invalid archive' -Encoding Ascii
$checkExit = Invoke-CheckProcess $bootstrap @('-JavaHome', $javaHome, '-Offline') 'bad-hash.log'
Assert-Result ($checkExit -ne 0 -and !(Test-Path (Join-Path $fixture ('.tools/' + $manifest.maven.directory)))) 'Checksum failure prevents extraction'
Copy-Item -LiteralPath (Join-Path $root ('.tools/downloads/' + $manifest.maven.archive)) -Destination $archive -Force
$checkExit = Invoke-CheckProcess $bootstrap @('-JavaHome', $javaHome, '-Offline') 'bootstrap.log'
Assert-Result ($checkExit -eq 0) 'Offline setup accepts the pinned archive and existing JDK in a path with spaces'
$checkExit = Invoke-CheckProcess $bootstrap @('-JavaHome', $javaHome, '-Offline') 'bootstrap-repeat.log'
Assert-Result ($checkExit -eq 0) 'Repeated setup succeeds'

$savedJava = $env:JAVA_HOME
$savedArguments = $env:MAVEN_ARGS
$savedLocation = (Get-Location).Path
try {
    $env:JAVA_HOME = 'intentionally invalid ambient JAVA_HOME'
    $env:MAVEN_ARGS = 'intentionally invalid ambient Maven arguments'
    & $build info *> (Join-Path $fixture 'info.log')
    Assert-Result ($LASTEXITCODE -eq 0) 'Build uses configured tools instead of ambient Java and Maven arguments'
    Assert-Result ($env:JAVA_HOME -eq 'intentionally invalid ambient JAVA_HOME' -and
        $env:MAVEN_ARGS -eq 'intentionally invalid ambient Maven arguments' -and
        (Get-Location).Path -eq $savedLocation) 'Build restores caller environment and working directory'
} finally {
    $env:JAVA_HOME = $savedJava
    $env:MAVEN_ARGS = $savedArguments
}

. (Join-Path $PSScriptRoot 'check-java.ps1')
foreach ($version in @('1.8', '11', '17', '20', 'invalid')) {
    $rejected = $false
    try { Assert-JavaVersion "java.specification.version = $version`n" $manifest.java.minimumVersion } catch { $rejected = $true }
    Assert-Result $rejected "Unsupported/malformed Java version $version is rejected"
}
foreach ($version in @('21', '25')) {
    Assert-JavaVersion "java.specification.version = $version`n" $manifest.java.minimumVersion
    Assert-Result $true "Java version $version satisfies the minimum"
}
$wrongVersion = [PSCustomObject]@{ minimumVersion = 999 }
$rejected = $false
try { Assert-SupportedJava $javaHome $wrongVersion | Out-Null } catch { $rejected = $true }
Assert-Result $rejected 'JDK below the required minimum is rejected'
Assert-SupportedJava $javaHome $manifest.java | Out-Null
Assert-Result $true 'Java 21 or newer is accepted'
# A malformed project exercises Maven failure propagation without fetching dependencies.
Set-Content -LiteralPath (Join-Path $fixture 'pom.xml') -Value '<invalid' -Encoding Ascii
$checkExit = Invoke-CheckProcess $build @('verify', '-Offline') 'failed-build.log'
Assert-Result ($checkExit -ne 0) 'Maven failure reaches the caller as a nonzero exit code'
Test-ArchiveDownloads
Write-Host "$checks tooling checks passed. Diagnostic files: $fixture"
exit 0
