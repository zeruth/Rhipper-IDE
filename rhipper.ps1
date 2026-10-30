# Builds and runs the Rhipper IDE from this checkout.
#
#   ./rhipper.ps1               build the dev distribution and start the IDE
#   ./rhipper.ps1 -Regenerate   first regenerate the Bazel files (after an .iml or a plugin descriptor changed)
#   ./rhipper.ps1 -BuildOnly    compile the Rhipper plugin, and do not start the IDE
#
# Bazel compiles what changed before it runs the IDE. Close a running Rhipper IDE first: two cannot share one config directory.
# ./rhipper-installer.ps1 builds the installer.
[CmdletBinding()]
param(
  [switch]$Regenerate,
  [switch]$BuildOnly
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$bazel = Join-Path $root 'bazel.cmd'

function Invoke-Step([string]$Name, [string]$Exe, [string[]]$Arguments) {
  Write-Host "==> $Name" -ForegroundColor Cyan
  $started = Get-Date
  & $Exe @Arguments
  if ($LASTEXITCODE -ne 0) {
    Write-Host "$Name failed with exit code $LASTEXITCODE" -ForegroundColor Red
    exit $LASTEXITCODE
  }
  Write-Host ("    {0} took {1:mm\:ss}" -f $Name, ((Get-Date) - $started)) -ForegroundColor DarkGray
}

Push-Location $root
try {
  if ($Regenerate) {
    Invoke-Step 'Generate the Bazel files from the .iml files' (Join-Path $root 'build\jpsModelToBazelCommunityOnly.cmd') @()
    Invoke-Step 'Generate the dev distribution' $bazel @('run', '//build:dev_dist_generator')
  }
  if ($BuildOnly) {
    Invoke-Step 'Build the Rhipper plugin' $bazel @('build', '//plugins/rhipper/...')
  }
  else {
    if (Get-Process -Name rhipper -ErrorAction SilentlyContinue) {
      Write-Host 'A Rhipper IDE is running. Close it first.' -ForegroundColor Yellow
      exit 1
    }
    Invoke-Step 'Build and run the Rhipper IDE' $bazel @('run', '//build:rhipper')
  }
}
finally {
  Pop-Location
}
