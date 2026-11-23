#Requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 4)]
    [int]$Shard,
    [string]$JavaHome,
    [string]$ReportDirectory,
    # Validation-only mode exercises the manifest and Surefire checks without Maven.
    # It never writes a successful shard report.
    [switch]$ValidateOnly,
    [string]$ManifestPath,
    [string]$FixtureRoot
)

$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (!$ReportDirectory) { $ReportDirectory = Join-Path $root '.tools/validation' }
if (!$ManifestPath) { $ManifestPath = Join-Path $PSScriptRoot 'native-shards.json' }
if ($FixtureRoot -and !$ValidateOnly) { throw '-FixtureRoot requires -ValidateOnly.' }
$testRoot = if ($FixtureRoot) { $FixtureRoot } else { $root }
$modules = @('ffb-server', 'ffb-statetest')
$schemaVersion = 2

function Assert-Condition([bool]$Condition, [string]$Message) {
    if (!$Condition) { throw $Message }
}

function Get-NonnegativeInteger([string]$Value, [string]$Description) {
    $number = 0
    Assert-Condition ([int]::TryParse($Value, [ref]$number) -and $number -ge 0) "Invalid $Description`: $Value"
    return $number
}

function Get-TestSources([string]$Directory) {
    Assert-Condition (Test-Path -LiteralPath $Directory -PathType Container) "Missing native test source directory: $Directory"
    $prefix = (Resolve-Path -LiteralPath $Directory).Path.TrimEnd([char[]]@('/', '\')) + [IO.Path]::DirectorySeparatorChar
    $classes = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($file in Get-ChildItem -LiteralPath $Directory -Recurse -File -Filter '*.java') {
        if ($file.Name -notmatch '^(Test.*|.*Test|.*Tests|.*TestCase)\.java$') { continue }
        $relative = $file.FullName.Substring($prefix.Length)
        $class = $relative.Replace([IO.Path]::DirectorySeparatorChar, '.').Substring(0, $relative.Length - 5)
        Assert-Condition ($classes.Add($class)) "Duplicate native test source: $class"
    }
    Assert-Condition ($classes.Count -gt 0) 'No native test source classes discovered.'
    return ,$classes
}

function Get-TestSourceModules([string]$TestRoot, [string[]]$Modules) {
    $sourceModules = New-Object 'System.Collections.Generic.Dictionary[string,string]' ([StringComparer]::Ordinal)
    foreach ($module in $Modules) {
        $moduleRoot = Join-Path $TestRoot $module
        foreach ($class in (Get-TestSources (Join-Path $moduleRoot 'src/test/java'))) {
            Assert-Condition (!$sourceModules.ContainsKey($class)) "Duplicate native test source across modules: $class"
            $sourceModules.Add($class, $module)
        }
    }
    return ,$sourceModules
}

function Read-NativeManifest([string]$Path, $SourceModules) {
    Assert-Condition (Test-Path -LiteralPath $Path -PathType Leaf) "Missing native shard manifest: $Path"
    $manifest = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    Assert-Condition (($manifest.schemaVersion -is [int] -or $manifest.schemaVersion -is [long]) -and $manifest.schemaVersion -eq $schemaVersion) "Native shard manifest schemaVersion must be $schemaVersion."
    Assert-Condition ($manifest.shards -is [array] -and $manifest.shards.Count -eq 4) 'Native shard manifest must have four shards.'
    $registered = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    for ($index = 0; $index -lt 4; $index++) {
        $classes = $manifest.shards[$index]
        Assert-Condition ($classes -is [array] -and $classes.Count -gt 0) "Native shard $($index + 1) must be a nonempty array."
        $shardModules = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
        foreach ($class in $classes) {
            Assert-Condition ($class -is [string] -and $class -match '^com\.fumbbl\.[A-Za-z_][A-Za-z0-9_.]*$') "Invalid native class in shard $($index + 1): $class"
            Assert-Condition ($registered.Add($class)) "Duplicate native class registration: $class"
            Assert-Condition ($SourceModules.ContainsKey($class)) "Unknown native class registration: $class"
            [void]$shardModules.Add($SourceModules[$class])
        }
        foreach ($module in $modules) {
            Assert-Condition ($shardModules.Contains($module)) "Native shard $($index + 1) has no classes from $module."
        }
    }
    foreach ($class in $SourceModules.Keys) {
        Assert-Condition ($registered.Contains($class)) "Missing native class registration: $class"
    }
    Assert-Condition ($manifest.allowedSkips -is [pscustomobject]) 'Native shard manifest needs an allowedSkips object.'
    foreach ($classEntry in $manifest.allowedSkips.PSObject.Properties) {
        Assert-Condition ($registered.Contains($classEntry.Name)) "Skip allowance refers to unknown class: $($classEntry.Name)"
        Assert-Condition ($classEntry.Value -is [pscustomobject]) "Skip allowances must be keyed by test case: $($classEntry.Name)"
        foreach ($caseEntry in $classEntry.Value.PSObject.Properties) {
            Assert-Condition ($caseEntry.Name -match '^[A-Za-z_][A-Za-z0-9_]*$' -and $caseEntry.Value -is [string] -and $caseEntry.Value.Length -gt 0) "Invalid skip allowance: $($classEntry.Name)#$($caseEntry.Name)"
        }
    }
    Assert-Condition ($manifest.allowedEmptySuites -is [pscustomobject]) 'Native shard manifest needs an allowedEmptySuites object.'
    foreach ($classEntry in $manifest.allowedEmptySuites.PSObject.Properties) {
        Assert-Condition ($registered.Contains($classEntry.Name)) "Empty suite allowance refers to unknown class: $($classEntry.Name)"
        Assert-Condition ($classEntry.Value -is [array] -and $classEntry.Value.Count -gt 0) "Empty suite allowance requires environment variables: $($classEntry.Name)"
        foreach ($variable in $classEntry.Value) {
            Assert-Condition ($variable -is [string] -and $variable -cmatch '^[A-Z_][A-Z0-9_]*$') "Invalid opt-in environment variable: $($classEntry.Name)"
        }
    }
    return $manifest
}

function Read-NativeSuites($Manifest, [int]$ShardNumber, $SourceModules, [string]$TestRoot) {
    $expected = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($class in $Manifest.shards[$ShardNumber - 1]) { [void]$expected.Add($class) }
    $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    $suites = @()
    foreach ($module in $modules) {
        $directory = Join-Path (Join-Path $TestRoot $module) 'target/surefire-reports'
        Assert-Condition (Test-Path -LiteralPath $directory -PathType Container) "Missing native Surefire reports: $directory"
        $files = @(Get-ChildItem -LiteralPath $directory -File -Filter 'TEST-*.xml')
        Assert-Condition ($files.Count -gt 0) "No native Surefire XML reports found for $module."
        foreach ($file in $files) {
            [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw
            $node = $xml.DocumentElement
            Assert-Condition ($node.LocalName -eq 'testsuite') "Invalid Surefire testsuite XML: $($file.Name)"
            $id = [string]$node.GetAttribute('name')
            Assert-Condition ($id -eq $file.BaseName.Substring(5)) "Surefire suite name does not match filename: $($file.Name)"
            Assert-Condition ($expected.Contains($id)) "Unexpected native suite in shard $ShardNumber`: $id"
            Assert-Condition ($seen.Add($id)) "Duplicate native suite in shard $ShardNumber`: $id"
            Assert-Condition ($SourceModules[$id] -eq $module) "Native suite reported by the wrong module $module`: $id"
            $tests = Get-NonnegativeInteger ($node.GetAttribute('tests')) "$id tests"
            $failures = Get-NonnegativeInteger ($node.GetAttribute('failures')) "$id failures"
            $errors = Get-NonnegativeInteger ($node.GetAttribute('errors')) "$id errors"
            $skipped = Get-NonnegativeInteger ($node.GetAttribute('skipped')) "$id skipped"
            Assert-Condition ($failures -eq 0 -and $errors -eq 0) "Native suite failed: $id ($failures failures, $errors errors)"
            $missingEnvironmentVariable = $null
            if ($tests -eq 0) {
                # Surefire emits an empty suite when these opt-in tests abort in BeforeAll.
                $requiredEnvironment = $Manifest.allowedEmptySuites.PSObject.Properties[$id]
                foreach ($variable in $requiredEnvironment.Value) {
                    if ($null -eq [Environment]::GetEnvironmentVariable($variable, 'Process')) {
                        $missingEnvironmentVariable = $variable
                        break
                    }
                }
                Assert-Condition ($null -ne $missingEnvironmentVariable) "Native suite has zero tests: $id"
            }
            $seconds = 0.0
            Assert-Condition ([double]::TryParse($node.GetAttribute('time'), [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$seconds) -and $seconds -ge 0 -and ![double]::IsInfinity($seconds) -and ![double]::IsNaN($seconds)) "Invalid native suite duration: $id"
            $testCases = @($node.SelectNodes('testcase'))
            Assert-Condition ($testCases.Count -eq $tests) "Native suite test count disagrees with XML test cases: $id"
            $skippedTests = @()
            foreach ($case in $testCases) {
                Assert-Condition ($null -eq $case.SelectSingleNode('failure') -and $null -eq $case.SelectSingleNode('error')) "Native testcase failed: $id#$($case.GetAttribute('name'))"
                $skipNode = $case.SelectSingleNode('skipped')
                if ($null -eq $skipNode) { continue }
                $name = [string]$case.GetAttribute('name')
                $reason = ([string]$skipNode.InnerText -split '\r?\n', 2)[0].Trim()
                if (!$reason) { $reason = [string]$skipNode.GetAttribute('message') }
                $classAllowance = $Manifest.allowedSkips.PSObject.Properties[$id]
                $allowedReason = if ($null -eq $classAllowance) { $null } else { $classAllowance.Value.PSObject.Properties[$name] }
                Assert-Condition ($null -ne $allowedReason -and $reason -ceq [string]$allowedReason.Value) "Unexpected native skip: $id#$name ($reason)"
                $skippedTests += [ordered]@{ name = $name; reason = $reason }
            }
            Assert-Condition ($skippedTests.Count -eq $skipped) "Native suite skip count disagrees with XML test cases: $id"
            $suite = [ordered]@{
                id = $id
                durationMs = [int][Math]::Round($seconds * 1000, [MidpointRounding]::AwayFromZero)
                tests = $tests
                failures = $failures
                errors = $errors
                skipped = $skipped
                skippedTests = @($skippedTests)
            }
            if ($missingEnvironmentVariable) { $suite.missingEnvironmentVariable = $missingEnvironmentVariable }
            $suites += $suite
        }
    }
    foreach ($class in $expected) { Assert-Condition ($seen.Contains($class)) "Missing native Surefire suite: $class" }
    return ,@($suites | Sort-Object { $_.id })
}

$reportPath = Join-Path $ReportDirectory "native-$Shard.json"
if (!$ValidateOnly -and (Test-Path -LiteralPath $reportPath)) { Remove-Item -LiteralPath $reportPath }
$sourceModules = Get-TestSourceModules $testRoot $modules
$nativeManifest = Read-NativeManifest $ManifestPath $sourceModules
if ($ValidateOnly) {
    $nativeSuites = Read-NativeSuites $nativeManifest $Shard $sourceModules $testRoot
    Write-Host "Validated native shard $Shard`: $($nativeSuites.Count) suites."
    exit 0
}

New-Item -ItemType Directory -Force -Path $ReportDirectory | Out-Null
$includeDirectory = Join-Path $root '.tools/validation'
New-Item -ItemType Directory -Force -Path $includeDirectory | Out-Null
$includePath = Join-Path $includeDirectory "native-$Shard-includes.txt"
$nativeManifest.shards[$Shard - 1] | ForEach-Object { $_.Replace('.', '/') + '.java' } | Set-Content -LiteralPath $includePath -Encoding Ascii
$shell = (Get-Process -Id $PID).Path
& $shell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'tools/target-build.ps1') verify -JavaHome $JavaHome -NativeIncludesFile $includePath
if ($LASTEXITCODE -ne 0) { throw "Native shard $Shard Maven verify failed (exit $LASTEXITCODE)." }
$nativeSuites = Read-NativeSuites $nativeManifest $Shard $sourceModules $testRoot
$checkout = (& git -C $root rev-parse HEAD).Trim()
$commit = if ($env:GITHUB_SHA) { $env:GITHUB_SHA } else { $checkout }
Assert-Condition ($commit -match '^[0-9a-fA-F]{40}$') "Invalid native shard commit: $commit"
Assert-Condition ($commit -ceq $checkout) "Native shard commit differs from checkout: $commit != $checkout"
$report = [ordered]@{
    schemaVersion = $schemaVersion
    family = 'native'
    shard = $Shard
    commit = $commit
    passed = $true
    suites = @($nativeSuites)
}
[IO.File]::WriteAllText($reportPath, ($report | ConvertTo-Json -Depth 8), (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Native shard $Shard passed: $($nativeSuites.Count) suites; report $reportPath"
