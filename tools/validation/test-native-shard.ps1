#Requires -Version 5.1
# Contract checks with generated fixtures; no build or prior reports are required.
$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$fixture = Join-Path $root ('.tools/native-shard-checks/' + [Guid]::NewGuid().ToString('N'))
$serverSources = Join-Path $fixture 'ffb-server/src/test/java'
$stateSources = Join-Path $fixture 'ffb-statetest/src/test/java'
$serverReports = Join-Path $fixture 'ffb-server/target/surefire-reports'
$stateReports = Join-Path $fixture 'ffb-statetest/target/surefire-reports'
New-Item -ItemType Directory -Force -Path $serverReports, $stateReports | Out-Null
$manifestPath = Join-Path $fixture 'manifest.json'
$serverClasses = @(1..4 | ForEach-Object { "com.fumbbl.ffb.server.Shard$($_)Test" })
$stateClasses = @(1..4 | ForEach-Object { "com.fumbbl.ffb.test.Shard$($_)Test" })
$extraServer = 'com.fumbbl.ffb.server.ExtraTest'
function New-TestSource([string]$Sources, [string]$Class) {
    $javaFile = Join-Path $Sources ($Class.Replace('.', '/') + '.java')
    New-Item -ItemType Directory -Force -Path (Split-Path $javaFile -Parent) | Out-Null
    Set-Content -LiteralPath $javaFile -Value "class $($Class.Split('.')[-1]) {}" -Encoding Ascii
}
foreach ($class in ($serverClasses + $extraServer)) { New-TestSource $serverSources $class }
foreach ($class in $stateClasses) { New-TestSource $stateSources $class }
$reason = 'org.opentest4j.TestAbortedException: Assumption failed: intentional fixture'
$original = [ordered]@{
    schemaVersion = 2
    shards = @(0..3 | ForEach-Object { ,@($serverClasses[$_], $stateClasses[$_]) })
    allowedSkips = @{ $serverClasses[0] = @{ optIn = $reason } }
    allowedEmptySuites = @{ $extraServer = @('NATIVE_SHARD_FIXTURE_OPT_IN') }
}
$original.shards[0] += $extraServer
$xmlPath = Join-Path $serverReports "TEST-$($serverClasses[0]).xml"
$originalXml = @"
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="$($serverClasses[0])" time="1.25" tests="2" failures="0" errors="0" skipped="1">
  <testcase name="works" classname="$($serverClasses[0])" time="1.25" />
  <testcase name="optIn" classname="$($serverClasses[0])" time="0">
    <skipped type="org.opentest4j.TestAbortedException"><![CDATA[$reason
    at fixture.Test.method(Fixture.java:1)]]></skipped>
  </testcase>
</testsuite>
"@
Set-Content -LiteralPath $xmlPath -Value $originalXml -Encoding UTF8
$plainXml = '<testsuite name="{0}" time="1" tests="1" failures="0" errors="0" skipped="0"><testcase name="works" classname="{0}" time="1" /></testsuite>'
$extraPath = Join-Path $serverReports "TEST-$extraServer.xml"
Set-Content -LiteralPath $extraPath -Value ($plainXml -f $extraServer) -Encoding UTF8
Set-Content -LiteralPath (Join-Path $stateReports "TEST-$($stateClasses[0]).xml") -Value ($plainXml -f $stateClasses[0]) -Encoding UTF8
$shell = (Get-Process -Id $PID).Path
$validator = Join-Path $PSScriptRoot 'native-shard.ps1'
$checks = 0

function Write-Manifest($Value) {
    $Value | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
}

function Assert-Validation([bool]$Pass, [string]$ExpectedText) {
    # Windows PowerShell surfaces expected child stderr as ErrorRecords.
    $ErrorActionPreference = 'Continue'
    $PSNativeCommandUseErrorActionPreference = $false
    $output = & $shell -NoProfile -ExecutionPolicy Bypass -File $validator -Shard 1 -ValidateOnly -ManifestPath $manifestPath -FixtureRoot $fixture 2>&1 | Out-String
    $exitCode = $LASTEXITCODE
    if (($exitCode -eq 0) -ne $Pass -or $output -notmatch [regex]::Escape($ExpectedText)) {
        throw "Unexpected validator outcome (exit $exitCode, expected pass $Pass / $ExpectedText): $output"
    }
    $script:checks++
}

Write-Manifest $original
Assert-Validation $true 'Validated native shard 1: 3 suites.'

$changed = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$changed.shards[1][0] = $serverClasses[0]
Write-Manifest $changed
Assert-Validation $false 'Duplicate native class registration'

Write-Manifest $original
$changed = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$changed.shards[0][0] = 'com.fumbbl.ffb.test.DoesNotExistTest'
Write-Manifest $changed
Assert-Validation $false 'Unknown native class registration'

Write-Manifest $original
$changed = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$changed.shards[0] = @($serverClasses[0], $stateClasses[0])
Write-Manifest $changed
Assert-Validation $false "Missing native class registration: $extraServer"

Write-Manifest $original
$changed = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$changed.shards[1] = @($stateClasses[1])
Write-Manifest $changed
Assert-Validation $false 'Native shard 2 has no classes from ffb-server'

Write-Manifest $original
$changed = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$changed.schemaVersion = 1
Write-Manifest $changed
Assert-Validation $false 'Native shard manifest schemaVersion must be 2'

Write-Manifest $original
New-TestSource $serverSources 'com.fumbbl.ffb.server.NewTest'
Assert-Validation $false 'Missing native class registration: com.fumbbl.ffb.server.NewTest'
Remove-Item -LiteralPath (Join-Path $serverSources 'com/fumbbl/ffb/server/NewTest.java')
New-TestSource $stateSources 'com.fumbbl.ffb.test.Test'
Assert-Validation $false 'Missing native class registration: com.fumbbl.ffb.test.Test'
Remove-Item -LiteralPath (Join-Path $stateSources 'com/fumbbl/ffb/test/Test.java')

New-TestSource $stateSources $serverClasses[0]
Assert-Validation $false 'Duplicate native test source across modules'
Remove-Item -LiteralPath (Join-Path $stateSources 'com/fumbbl/ffb/server/Shard1Test.java')

$savedXml = Join-Path $fixture 'saved.xml'
Move-Item -LiteralPath $extraPath -Destination $savedXml
Assert-Validation $false "Missing native Surefire suite: $extraServer"
Move-Item -LiteralPath $savedXml -Destination $extraPath

Move-Item -LiteralPath $stateReports -Destination (Join-Path $fixture 'saved-reports')
Assert-Validation $false 'Missing native Surefire reports'
Move-Item -LiteralPath (Join-Path $fixture 'saved-reports') -Destination $stateReports

$extra = Join-Path $serverReports "TEST-$($serverClasses[1]).xml"
Set-Content -LiteralPath $extra -Value ($plainXml -f $serverClasses[1]) -Encoding UTF8
Assert-Validation $false 'Unexpected native suite'
Remove-Item -LiteralPath $extra

$duplicate = Join-Path $stateReports "TEST-$extraServer.xml"
Copy-Item -LiteralPath $extraPath -Destination $duplicate
Assert-Validation $false 'Duplicate native suite'
Remove-Item -LiteralPath $duplicate
Move-Item -LiteralPath $extraPath -Destination $duplicate
Assert-Validation $false 'Native suite reported by the wrong module'
Move-Item -LiteralPath $duplicate -Destination $extraPath

$emptyXml = '<testsuite name="{0}" time="0" tests="0" failures="0" errors="0" skipped="0" />'
Set-Content -LiteralPath $extraPath -Value ($emptyXml -f $extraServer) -Encoding UTF8
$previousOptIn = $env:NATIVE_SHARD_FIXTURE_OPT_IN
try {
    Remove-Item Env:NATIVE_SHARD_FIXTURE_OPT_IN -ErrorAction SilentlyContinue
    Assert-Validation $true 'Validated native shard 1: 3 suites.'
    $env:NATIVE_SHARD_FIXTURE_OPT_IN = '1'
    Assert-Validation $false "Native suite has zero tests: $extraServer"
} finally {
    [Environment]::SetEnvironmentVariable('NATIVE_SHARD_FIXTURE_OPT_IN', $previousOptIn, 'Process')
}
Set-Content -LiteralPath $extraPath -Value ($plainXml -f $extraServer) -Encoding UTF8

$changed = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$changed.allowedSkips.PSObject.Properties.Remove($serverClasses[0])
Write-Manifest $changed
Assert-Validation $false 'Unexpected native skip'

Write-Manifest $original
$changed = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$changed.allowedSkips.PSObject.Properties[$serverClasses[0]].Value.optIn = 'changed reason'
Write-Manifest $changed
Assert-Validation $false 'Unexpected native skip'

Write-Manifest $original
[xml]$xml = $originalXml
$xml.testsuite.SetAttribute('failures', '1')
$xml.Save($xmlPath)
Assert-Validation $false 'Native suite failed'

[xml]$xml = $originalXml
$xml.testsuite.SetAttribute('errors', '1')
$xml.Save($xmlPath)
Assert-Validation $false 'Native suite failed'

[xml]$xml = $originalXml
$xml.testsuite.SetAttribute('tests', '0')
$xml.Save($xmlPath)
Assert-Validation $false 'Native suite has zero tests'

Write-Host "$checks native shard validation checks passed. Fixture: $fixture"
# GitHub's pwsh wrapper propagates LASTEXITCODE; the final child failure above was expected.
exit 0
