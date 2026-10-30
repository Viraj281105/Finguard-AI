# Stops the containerised STAGING stack started by staging-up.ps1 and deletes its volume and state (S0-08).
# Never touches the development stack (compose project "finguard").
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$state = Join-Path $root '.staging'
$envFile = Join-Path $state 'staging.env'

Push-Location $root
try {
    if (Test-Path $envFile) {
        docker compose -p finguard-staging --profile full --env-file $envFile -f docker-compose.yml -f docker-compose.staging.yml down -v --remove-orphans
    } else {
        docker compose -p finguard-staging --profile full -f docker-compose.yml -f docker-compose.staging.yml down -v --remove-orphans
    }
} finally {
    Pop-Location
}

if (Test-Path $state) {
    Remove-Item -Recurse -Force $state
}
