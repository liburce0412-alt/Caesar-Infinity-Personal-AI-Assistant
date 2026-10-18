param(
    [Parameter(Mandatory = $true)][switch]$RunSyntheticBoundaryChecks,
    [Parameter(Mandatory = $true)][string]$DeviceSerial,
    [string]$OutputDirectory = ''
)

$ErrorActionPreference = 'Stop'
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}
if (-not $RunSyntheticBoundaryChecks) { throw 'Pass -RunSyntheticBoundaryChecks to opt in.' }
$targetPackage = 'com.aistudio.campusai.ywtpzx'
$adbCandidates = @((Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'), 'adb.exe')
$adb = $adbCandidates | Where-Object {
    if ([IO.Path]::IsPathRooted($_)) { Test-Path -LiteralPath $_ } else { Get-Command $_ -ErrorAction SilentlyContinue }
} | Select-Object -First 1
if (-not $adb) { throw 'adb.exe was not found.' }
$devices = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' })
if (-not ($devices | Where-Object { ($_ -split '\s+')[0] -eq $DeviceSerial })) {
    throw 'The explicitly selected device is not connected and authorized.'
}
# No installation, force-stop, clear-data, login, or Gradle connected-test lifecycle.
$debugIdentity = @(& $adb -s $DeviceSerial shell run-as $targetPackage id 2>$null)
if ($LASTEXITCODE -ne 0 -or $debugIdentity.Count -eq 0) { throw 'The separately installed target must be a debuggable candidate.' }
$runId = [Guid]::NewGuid().ToString('N')
$deviceReport = "files/caesar-boundary-probe/$runId.json"
$deviceFixture = "cache/caesar-boundary-$runId"
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts\caesar-boundary'
}
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
[IO.Directory]::CreateDirectory($outputRoot) | Out-Null
$hostReport = Join-Path $outputRoot "$runId.json"
Write-Output "Synthetic boundary run ID: $runId; device report: $deviceReport"
function Test-DeviceProbePath([string]$Path, [ValidateSet('-e', '-f')][string]$Kind = '-e') {
    # shell v2 propagates the remote exit status; exec-out only reports transport status.
    $result = @(& $adb -s $DeviceSerial shell run-as $targetPackage test $Kind $Path 2>&1)
    $remoteExit = $LASTEXITCODE
    if ($remoteExit -eq 0) { return $true }
    if ($remoteExit -eq 1 -and [string]::IsNullOrWhiteSpace(($result -join "`n"))) { return $false }
    throw "Unable to verify probe path. Preserve run $runId for recovery."
}
$launch = @(& $adb -s $DeviceSerial shell am start -W `
    -n "$targetPackage/com.campusai.debug.CaesarBoundaryProbeActivity" `
    -a 'com.campusai.debug.RUN_BOUNDARY_PROBE' --es run_id $runId --ez explicit_opt_in true 2>&1)
if ($LASTEXITCODE -ne 0 -or ($launch -join "`n") -match 'Error:|Exception|Permission Denial') {
    throw 'Unable to launch the shell-gated isolated boundary activity.'
}
$deadline = [DateTime]::UtcNow.AddSeconds(130)
do {
    if (Test-DeviceProbePath $deviceReport '-f') { break }
    if ([DateTime]::UtcNow -ge $deadline) { throw "Probe $runId timed out; preserve its named report/cache for recovery. The script will not stop or clear the app." }
    Start-Sleep -Seconds 1
} while ($true)
$json = @(& $adb -s $DeviceSerial shell run-as $targetPackage cat $deviceReport 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0) { throw "Unable to read probe $runId; its device report is retained." }
try { $report = $json | ConvertFrom-Json }
catch { throw "Probe $runId report is incomplete or invalid; its device copy is retained." }
if ($report.schema -ne 'caesar.boundary.probe.v1' -or $report.runId -ne $runId -or
    -not $report.syntheticDataOnly -or $report.realAuthenticationSwitch -or $report.database -ne 'room_in_memory') {
    throw 'Unexpected probe identity or isolation metadata.'
}
[IO.File]::WriteAllText($hostReport, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
if ((Test-DeviceProbePath $deviceFixture) -or -not $report.fixtureCleanupSucceeded) {
    throw "Fixture cleanup did not complete. Retain report for review: $hostReport"
}
# Remove only the exact report already saved and parsed on the host; no target package removal.
& $adb -s $DeviceSerial shell run-as $targetPackage rm $deviceReport
if ($LASTEXITCODE -ne 0) { throw 'The report was saved, but its device copy could not be removed.' }
if (Test-DeviceProbePath $deviceReport) { throw 'Device report cleanup could not be verified.' }
if (-not $report.passed) { throw "Synthetic boundary checks failed; evidence saved to $hostReport" }
Write-Output "Synthetic device boundary checks passed; this is not a real authentication switch: $hostReport"
