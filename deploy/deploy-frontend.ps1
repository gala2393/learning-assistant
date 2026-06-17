param(
  [Parameter(Mandatory = $true)]
  [string]$Server,
  [Parameter(Mandatory = $true)]
  [string]$SshKey,
  [string]$RemoteAppDir = "/opt/learning-assistant",
  [string]$ApiBase = "/api",
  [string]$IcpTextBase64 = "6Ze9SUNQ5aSHMjAyNjAyMTkyMeWPtw==",
  [string]$IcpUrl = "https://beian.miit.gov.cn/"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$frontendDir = Join-Path $repoRoot "frontend"
$archive = Join-Path $env:TEMP "learning-frontend-dist.tar.gz"
$icpText = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($IcpTextBase64))

if (-not (Test-Path $frontendDir)) {
  throw "frontend directory not found: $frontendDir"
}

if (-not (Test-Path $SshKey)) {
  throw "SSH key not found: $SshKey"
}

Push-Location $frontendDir
try {
  $env:VITE_API_BASE = $ApiBase
  $env:VITE_ICP_BEIAN_TEXT = $icpText
  $env:VITE_ICP_BEIAN_URL = $IcpUrl

  npm run build
  if ($LASTEXITCODE -ne 0) {
    throw "frontend build failed"
  }

  $localhostInBundle = Select-String -Path "dist\assets\*.js" -Pattern "localhost:8080" -Quiet
  if ($localhostInBundle) {
    throw "production bundle contains localhost:8080; check VITE_API_BASE"
  }
}
finally {
  Pop-Location
}

if (Test-Path $archive) {
  Remove-Item $archive -Force
}

tar -czf $archive -C (Join-Path $frontendDir "dist") .
if ($LASTEXITCODE -ne 0) {
  throw "failed to archive frontend dist"
}

scp -i $SshKey $archive "${Server}:/tmp/learning-frontend-dist.tar.gz"
if ($LASTEXITCODE -ne 0) {
  throw "failed to upload frontend dist"
}

$remoteScript = @"
set -euo pipefail
cd "$RemoteAppDir"

stamp=`$(date +%Y%m%d%H%M%S)
mkdir -p frontend-dist
cp -a frontend-dist "frontend-dist.bak-`$stamp"

tmp_dir="frontend-dist.new"
rm -rf "`$tmp_dir"
mkdir -p "`$tmp_dir"
tar -xzf /tmp/learning-frontend-dist.tar.gz -C "`$tmp_dir"

# Keep the frontend-dist directory itself. Docker bind mounts track the
# directory inode, so deleting and recreating it can make Nginx see an
# empty stale mount until the container is restarted.
find frontend-dist -mindepth 1 -maxdepth 1 -exec rm -rf {} +
cp -a "`$tmp_dir"/. frontend-dist/
rm -rf "`$tmp_dir"

docker exec learning-nginx nginx -s reload || docker restart learning-nginx
docker exec learning-nginx test -f /usr/share/nginx/html/index.html
"@

$remoteScript | ssh -i $SshKey $Server "tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
  throw "failed to replace frontend dist on server"
}

Write-Host "Frontend deployed safely: kept frontend-dist directory and replaced only its contents."
