# Brings up an isolated local STAGING stack with SYNTHETIC data only. Nothing is exposed beyond 127.0.0.1.
#   PostgreSQL 15 + AI service : docker compose project "finguard-staging" (ports 15433 / 18000)
#   Backend (prod profile)     : java -jar on 127.0.0.1:18080  (exercises the production safety guard)
#   Frontend (production build): vite preview on 127.0.0.1:14173
# Secrets are random, generated per run into .staging/ (git-ignored). The development stack is not touched.
# Usage:  .\scripts\staging-up.ps1        Tear down with:  .\scripts\staging-down.ps1
# Native tools (docker, mvnw, npx) write progress to stderr, which Windows PowerShell 5.1 would turn into a
# terminating error; every native call below is followed by an explicit $LASTEXITCODE check instead.
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$state = Join-Path $root '.staging'
New-Item -ItemType Directory -Force $state | Out-Null

function New-Secret([int]$bytes) {
    $b = New-Object byte[] $bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
    [Convert]::ToBase64String($b).TrimEnd('=').Replace('+', 'A').Replace('/', 'B')
}

$pgPort = 15433; $aiPort = 18000; $apiPort = 18080; $webPort = 14173
$envFile = Join-Path $state 'staging.env'
if (-not (Test-Path $envFile)) {
    @(
        'POSTGRES_DB=finguard_staging',
        'POSTGRES_USER=finguard_staging',
        "POSTGRES_PASSWORD=$(New-Secret 24)",
        "AI_INTERNAL_TOKEN=$(New-Secret 32)",
        "JWT_SECRET=$(New-Secret 48)",
        "STAGING_PG_PORT=$pgPort", "STAGING_AI_PORT=$aiPort"
    ) | Set-Content -Encoding ascii $envFile
}
$cfg = @{}
Get-Content $envFile | ForEach-Object { $k, $v = $_ -split '=', 2; $cfg[$k] = $v }

Write-Host "1/5 PostgreSQL + AI service (compose project finguard-staging)" -ForegroundColor Yellow
Push-Location $root
try {
    docker compose -p finguard-staging --env-file $envFile -f docker-compose.yml -f docker-compose.staging.yml up -d --build --wait postgres ai
    if ($LASTEXITCODE -ne 0) { throw 'docker compose failed' }
} finally { Pop-Location }

Write-Host "2/5 Backend jar" -ForegroundColor Yellow
Push-Location (Join-Path $root 'backend')
try {
    .\mvnw -B -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'backend package failed' }
} finally { Pop-Location }
$jar = Get-ChildItem (Join-Path $root 'backend\target') -Filter 'backend-*.jar' | Where-Object Name -notmatch 'plain' | Select-Object -First 1

Write-Host "3/5 Backend (SPRING_PROFILES_ACTIVE=prod, loopback only)" -ForegroundColor Yellow
# -Duser.timezone=UTC: the JDBC driver sends the JVM zone to PostgreSQL, which rejects legacy aliases such as
# Asia/Calcutta (FATAL: invalid value for parameter "TimeZone"), so the backend would not start on an IST host.
# Business dates use app.timezone (Asia/Kolkata by default), not the JVM zone.
$backendEnv = @{
    SPRING_PROFILES_ACTIVE = 'prod'
    SPRING_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:$pgPort/$($cfg.POSTGRES_DB)"
    SPRING_DATASOURCE_USERNAME = $cfg.POSTGRES_USER
    SPRING_DATASOURCE_PASSWORD = $cfg.POSTGRES_PASSWORD
    JWT_SECRET = $cfg.JWT_SECRET
    JWT_COOKIE_SECURE = 'true'
    CORS_ALLOWED_ORIGINS = "http://localhost:$webPort"
    AI_SERVICE_URL = "http://127.0.0.1:$aiPort"
    AI_INTERNAL_TOKEN = $cfg.AI_INTERNAL_TOKEN
    SERVER_PORT = "$apiPort"
    SERVER_ADDRESS = '127.0.0.1'
}
foreach ($k in $backendEnv.Keys) { Set-Item "Env:$k" $backendEnv[$k] }
$api = Start-Process java -ArgumentList '-Duser.timezone=UTC', '-jar', $jar.FullName -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $state 'backend.log') -RedirectStandardError (Join-Path $state 'backend.err.log')
$api.Id | Set-Content (Join-Path $state 'backend.pid')

Write-Host "4/5 Frontend production build + preview" -ForegroundColor Yellow
Push-Location (Join-Path $root 'frontend')
try {
    $env:VITE_API_BASE_URL = "http://localhost:$apiPort/api"
    npx vite build --outDir (Join-Path $state 'frontend-dist') --emptyOutDir
    if ($LASTEXITCODE -ne 0) { throw 'frontend build failed' }
    $web = Start-Process npx.cmd -ArgumentList 'vite', 'preview', '--outDir', (Join-Path $state 'frontend-dist'), '--host', '127.0.0.1', '--port', $webPort, '--strictPort' `
        -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $state 'frontend.log') -RedirectStandardError (Join-Path $state 'frontend.err.log')
    $web.Id | Set-Content (Join-Path $state 'frontend.pid')
} finally { Pop-Location }

Write-Host "5/5 Waiting for health" -ForegroundColor Yellow
$deadline = (Get-Date).AddSeconds(120)
do {
    Start-Sleep 3
    try { $ok = (Invoke-WebRequest "http://127.0.0.1:$apiPort/actuator/health" -UseBasicParsing -TimeoutSec 3).StatusCode -eq 200 } catch { $ok = $false }
    if (-not $ok) {
        try { $ok = (Invoke-WebRequest "http://127.0.0.1:$apiPort/api/auth/login" -Method Post -ContentType 'application/json' -Body '{}' -UseBasicParsing -TimeoutSec 3 -ErrorAction Stop) -ne $null } catch { $ok = $_.Exception.Response -ne $null }
    }
} until ($ok -or (Get-Date) -gt $deadline)
if (-not $ok) { throw "backend did not come up; see $state\backend.err.log" }

Write-Host ""
Write-Host "Frontend: http://localhost:$webPort   Backend: http://localhost:$apiPort   AI: http://127.0.0.1:$aiPort/health" -ForegroundColor Cyan
Write-Host "Synthetic data only. Logs and pids: $state" -ForegroundColor DarkGray
