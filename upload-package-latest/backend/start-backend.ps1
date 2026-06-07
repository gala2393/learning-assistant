param(
  [int]$Port = 0
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$envFile = Join-Path $root ".env"
$localEnvFile = Join-Path $root ".env.local"

function Import-EnvFile([string]$Path) {
  if (!(Test-Path -LiteralPath $Path)) {
    return
  }
  Get-Content -LiteralPath $Path | ForEach-Object {
    $line = $_.Trim()
    if (!$line -or $line.StartsWith("#")) {
      return
    }
    $parts = $line.Split("=", 2)
    if ($parts.Count -ne 2) {
      return
    }
    $name = $parts[0].Trim()
    $value = $parts[1].Trim().Trim('"').Trim("'")
    if ($name) {
      Set-Item -Path "Env:$name" -Value $value
    }
  }
}

Import-EnvFile $envFile
Import-EnvFile $localEnvFile

if ($Port -gt 0) {
  $env:SERVER_PORT = [string]$Port
}

$port = if ($env:SERVER_PORT) { [int]$env:SERVER_PORT } else { 8080 }
$healthUrl = "http://127.0.0.1:$port/api/health"

function Test-BackendHealthy {
  try {
    $response = Invoke-WebRequest -Uri $healthUrl -TimeoutSec 3 -UseBasicParsing
    return $response.StatusCode -eq 200
  } catch {
    return $false
  }
}

$listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($listener) {
  if (Test-BackendHealthy) {
    Write-Host "Backend is already running at $healthUrl"
    exit 0
  }
  Write-Error "Port $port is occupied but the backend is not healthy. Stop that process or change SERVER_PORT in .env."
  exit 1
}

$mvn = Join-Path $root "tools\apache-maven-3.9.11\bin\mvn.cmd"
if (!(Test-Path -LiteralPath $mvn)) {
  $mvn = "mvn.cmd"
}

Set-Location $root
& $mvn spring-boot:run
