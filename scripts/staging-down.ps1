# Stops the local STAGING stack started by staging-up.ps1 and deletes its database volume (synthetic data only).
# Never touches the development stack (compose project "finguard", containers finguard-postgres / finguard-ai).
$root = Split-Path -Parent $PSScriptRoot
$state = Join-Path $root '.staging'
foreach ($name in 'frontend', 'backend') {
    $pidFile = Join-Path $state "$name.pid"
    if (Test-Path $pidFile) {
        $procId = [int](Get-Content $pidFile)
        Get-CimInstance Win32_Process -Filter "ParentProcessId=$procId" -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
        Remove-Item $pidFile
    }
}
Push-Location $root
try {
    if (Test-Path (Join-Path $state 'staging.env')) {
        docker compose -p finguard-staging --env-file (Join-Path $state 'staging.env') -f docker-compose.yml -f docker-compose.staging.yml down -v
    }
} finally { Pop-Location }
