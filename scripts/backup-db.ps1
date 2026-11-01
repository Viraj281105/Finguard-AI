# Creates a PostgreSQL 18 custom-format backup (-Fc) and SHA-256 manifest (S0-09).
# Usage: .\scripts\backup-db.ps1 [-OutputDir <path>]
param(
    [string]$OutputDir = ""
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path $root '.backups'
}
New-Item -ItemType Directory -Force $OutputDir | Out-Null

$container = if ($env:PG_CONTAINER) { $env:PG_CONTAINER } else { 'finguard-postgres' }
$dbName    = if ($env:POSTGRES_DB)   { $env:POSTGRES_DB }   else { 'finguard' }
$dbUser    = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { 'finguard_user' }
$stamp     = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$dumpFile  = Join-Path $OutputDir "$dbName-$stamp.dump"

Write-Host "Backing up database '$dbName' from container '$container' to $dumpFile..." -ForegroundColor Yellow
docker exec -i $container pg_dump -Fc --no-owner --no-privileges -U $dbUser -d $dbName -f "/tmp/$dbName-$stamp.dump"
if ($LASTEXITCODE -ne 0) { throw "pg_dump failed with exit code $LASTEXITCODE" }
docker cp "${container}:/tmp/$dbName-$stamp.dump" $dumpFile
if ($LASTEXITCODE -ne 0) { throw "docker cp failed with exit code $LASTEXITCODE" }
docker exec $container rm -f "/tmp/$dbName-$stamp.dump" | Out-Null

$hash = (Get-FileHash -Path $dumpFile -Algorithm SHA256).Hash.ToLowerInvariant()
"$hash  $(Split-Path -Leaf $dumpFile)" | Set-Content -Encoding ascii "$dumpFile.sha256"

Write-Host "Backup complete: $dumpFile (SHA-256: $hash)" -ForegroundColor Green

