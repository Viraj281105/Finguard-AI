# FinGuard AI - start the local development stack.
#   Docker:  PostgreSQL (host port 5433) and the AI service (port 8000)
#   Host:    Spring Boot backend (8080) and the React dev server (5173), each in its own window
# Usage:  .\start.ps1            (add -NoAi to skip the AI container)
param([switch]$NoAi)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

# Load KEY=VALUE pairs from .env (if present) so the backend sees them; child windows inherit this environment.
$envFile = Join-Path $root '.env'
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$' -and -not $_.TrimStart().StartsWith('#')) {
            Set-Item -Path "Env:$($Matches[1])" -Value $Matches[2]
        }
    }
    Write-Host "Loaded environment from .env" -ForegroundColor DarkGray
} else {
    Write-Host "No .env found - using development defaults (copy .env.example to .env to customise)." -ForegroundColor DarkGray
}

Write-Host "Starting infrastructure..." -ForegroundColor Yellow
Push-Location $root
try {
    $services = @('postgres')
    if (-not $NoAi) { $services += 'ai' }
    docker compose up -d --wait @services
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed. Is Docker Desktop running?" }
} finally {
    Pop-Location
}

Write-Host "Starting Spring Boot backend..." -ForegroundColor Yellow
Start-Process powershell -ArgumentList '-NoExit', '-Command', "Set-Location '$root\backend'; .\mvnw spring-boot:run"

Write-Host "Starting React frontend..." -ForegroundColor Yellow
Start-Process powershell -ArgumentList '-NoExit', '-Command', "Set-Location '$root\frontend'; if (-not (Test-Path node_modules)) { npm install }; npm run dev"

Write-Host ""
Write-Host "Frontend: http://localhost:5173" -ForegroundColor Cyan
Write-Host "Backend:  http://localhost:8080" -ForegroundColor Cyan
Write-Host "AI:       http://localhost:8000/health" -ForegroundColor Cyan
Write-Host "Database: localhost:5433" -ForegroundColor Cyan
