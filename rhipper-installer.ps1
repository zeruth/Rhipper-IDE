# Builds the Rhipper Workbench installer: the Windows .exe installer and the .zip, into out/rhipper/artifacts.
#
#   ./rhipper-installer.ps1                       build with a snapshot build number (RH-271.SNAPSHOT)
#   ./rhipper-installer.ps1 -BuildNumber 271.1    build RH-271.1, the number that a release carries
#   ./rhipper-installer.ps1 -Os all               build for every operating system, not only Windows
#
# The build compiles every module, so the first run takes long. Later runs reuse the compiled output.
[CmdletBinding()]
param(
  [string]$BuildNumber,
  [ValidateSet('windows', 'linux', 'mac', 'all', 'current')]
  [string]$Os = 'windows'
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$started = Get-Date

# the build reads its options as JVM system properties
$options = @("-Dintellij.build.target.os=$Os")
if ($BuildNumber) {
  $options += "-Dbuild.number=$BuildNumber"
}

Write-Host "==> Build the Rhipper installer ($Os$(if ($BuildNumber) { ", build $BuildNumber" }))" -ForegroundColor Cyan
& (Join-Path $root 'build\run_build_target.ps1') $root '@community//build:rhipper_build_target' @options
if ($LASTEXITCODE -ne 0) {
  Write-Host "The installer build failed with exit code $LASTEXITCODE" -ForegroundColor Red
  exit $LASTEXITCODE
}

Write-Host ("    The build took {0:hh\:mm\:ss}" -f ((Get-Date) - $started)) -ForegroundColor DarkGray
$artifacts = Join-Path $root 'out\rhipper\artifacts'
if (Test-Path $artifacts) {
  Write-Host "==> Artifacts in $artifacts" -ForegroundColor Cyan
  Get-ChildItem $artifacts -File | Format-Table Name, @{ Name = 'MB'; Expression = { '{0:N1}' -f ($_.Length / 1MB) } } -AutoSize
}
else {
  Write-Host "No artifacts directory at $artifacts. Look for the output under out\ in the build log." -ForegroundColor Yellow
}
