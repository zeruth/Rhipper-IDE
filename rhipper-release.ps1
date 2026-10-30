# Builds a Rhipper Workbench release and, with -Publish, publishes it on GitHub. CI runs this script on each push to main.
#
# The release tag is UPSTREAM.BUILD: UPSTREAM is the major of the upstream build number in build.txt (271 for 271.SNAPSHOT),
# and BUILD is our build number in version.txt. When the tag exists, the script does nothing. Increase version.txt to release.
#
#   ./rhipper-release.ps1               build the installer for the next tag, and publish nothing (a local test)
#   ./rhipper-release.ps1 -Check        tell the tag, and whether it exists, and build nothing
#   ./rhipper-release.ps1 -SkipBuild    name the files of the last build as a release names them, and publish nothing
#   ./rhipper-release.ps1 -Publish      build, then make the tag and the GitHub release with the installer (needs gh)
[CmdletBinding()]
param(
  [switch]$Check,
  [switch]$Publish,
  # build even when the tag exists, for a local test of the installer
  [switch]$Force,
  # package the installers of the last build again, and build nothing: a quick local test of the release files
  [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

$upstream = ((Get-Content (Join-Path $root 'build.txt') -Raw).Trim() -split '\.')[0]
$build = (Get-Content (Join-Path $root 'version.txt') -Raw).Trim()
if ($upstream -notmatch '^\d+$') { throw "build.txt does not start with an upstream major number: $upstream" }
if ($build -notmatch '^\d+$') { throw "version.txt must hold one whole number, not '$build'" }
$tag = "$upstream.$build"

# the remote is the source of truth: a local tag that was never pushed does not count
git -C $root fetch --quiet --tags origin 2>$null
git -C $root ls-remote --exit-code --tags origin "refs/tags/$tag" *> $null
$exists = $LASTEXITCODE -eq 0
Write-Host "==> Release $tag (upstream $upstream, build $build): the tag $(if ($exists) { 'exists' } else { 'does not exist' })" -ForegroundColor Cyan
if ($env:GITHUB_OUTPUT) {
  "tag=$tag" | Out-File -Append -Encoding utf8 $env:GITHUB_OUTPUT
  "exists=$($exists.ToString().ToLower())" | Out-File -Append -Encoding utf8 $env:GITHUB_OUTPUT
}
if ($Check) { exit 0 }
if ($exists -and -not $Force) {
  Write-Host "    Nothing to do. Increase version.txt to make a new release." -ForegroundColor DarkGray
  exit 0
}
if ($exists -and $Publish) { throw "The tag $tag exists. -Force builds it again, but cannot publish it again." }

if ($SkipBuild -and $Publish) { throw "-SkipBuild is for a local test. A release builds its installers." }
$started = if ($SkipBuild) { [datetime]::MinValue } else { Get-Date }
if (-not $SkipBuild) {
  & (Join-Path $root 'rhipper-installer.ps1') -BuildNumber $tag
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

# the installers and archives that this build wrote, not an older build's; the Uninstall-*.exe files are parts of the installers
$artifacts = Join-Path $root 'out\rhipper\artifacts'
$files = @(Get-ChildItem $artifacts -File -Filter 'rhipper-*' | Where-Object { $_.Extension -in '.exe', '.zip' -and $_.LastWriteTime -ge $started })
if ($files.Count -eq 0) { throw "No installer for $tag in $artifacts" }

# the release names its files for a person who downloads them: Rhipper-Workbench-271.1-windows-x64-setup.exe
$release = Join-Path $root "out\rhipper\release\$tag"
if (Test-Path $release) { Remove-Item -Recurse -Force $release }
New-Item -ItemType Directory -Force $release | Out-Null
$assets = foreach ($file in $files) {
  $arch = if ($file.Name -match 'aarch64') { 'arm64' } else { 'x64' }
  $name = if ($file.Extension -eq '.exe') { "Rhipper-Workbench-$tag-windows-$arch-setup.exe" } else { "Rhipper-Workbench-$tag-windows-$arch.zip" }
  $target = Join-Path $release $name
  Copy-Item $file.FullName $target
  $hash = (Get-FileHash $target -Algorithm SHA256).Hash.ToLower()
  "$hash *$name" | Out-File -Encoding ascii "$target.sha256"
  $target
  "$target.sha256"
}
Write-Host "==> Release assets" -ForegroundColor Cyan
$assets | ForEach-Object { Write-Host "    $_" }

if (-not $Publish) {
  Write-Host "    A local build: nothing was published. -Publish makes the tag $tag and the GitHub release." -ForegroundColor DarkGray
  exit 0
}

$commit = (git -C $root rev-parse HEAD).Trim()
gh release create $tag @assets --target $commit --title "Rhipper Workbench $tag" --generate-notes
if ($LASTEXITCODE -ne 0) { throw "gh release create failed with exit code $LASTEXITCODE" }
Write-Host "==> Published the release $tag" -ForegroundColor Green
