param(
  [int]$Port = 5174
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

function Test-FrontendHealthy([int]$p) {
  try {
    $response = Invoke-WebRequest -Uri ("http://127.0.0.1:{0}" -f $p) -TimeoutSec 3 -UseBasicParsing
    return $response.StatusCode -ge 200 -and $response.StatusCode -lt 500
  } catch {
    return $false
  }
}

$listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($listener -and (Test-FrontendHealthy $Port)) {
  Write-Host "Frontend is already running at http://127.0.0.1:$Port/"
  exit 0
}

Set-Location $root
if (!(Test-Path -LiteralPath (Join-Path $root "node_modules"))) {
  cmd /c npm install
  if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
  }
}

cmd /c npm run dev -- --host 127.0.0.1 --port $Port

