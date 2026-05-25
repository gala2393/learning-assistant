$ErrorActionPreference = "Stop"

$desktop = Split-Path -Parent $MyInvocation.MyCommand.Path
$packageRoot = Join-Path $desktop "upload-package"
$backendSource = Join-Path $desktop "backend"
$frontendSource = Join-Path $desktop "frontend"
$backendTarget = Join-Path $packageRoot "backend"
$frontendTarget = Join-Path $packageRoot "frontend"

if (Test-Path -LiteralPath $packageRoot) {
  Remove-Item -LiteralPath $packageRoot -Recurse -Force
}

New-Item -ItemType Directory -Path $backendTarget, $frontendTarget | Out-Null

robocopy $backendSource $backendTarget /E `
  /XD target .m2 data tools `
  /XF .env.local *.log | Out-Null
if ($LASTEXITCODE -gt 7) {
  throw "Failed to copy backend files. Robocopy exit code: $LASTEXITCODE"
}

robocopy $frontendSource $frontendTarget /E `
  /XD node_modules dist `
  /XF .env.local *.log tsconfig.tsbuildinfo | Out-Null
if ($LASTEXITCODE -gt 7) {
  throw "Failed to copy frontend files. Robocopy exit code: $LASTEXITCODE"
}

Write-Host "Upload package created:"
Write-Host $packageRoot
Write-Host ""
Write-Host "Before starting on the server, create backend/.env.local from backend/.env.example and set real secrets."
