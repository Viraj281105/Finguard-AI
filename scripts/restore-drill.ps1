# End-to-end PostgreSQL 18 backup + scratch database restore + row-count/MD5 checksum verification drill (S0-09).
# Usage: .\scripts\restore-drill.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$container = if ($env:PG_CONTAINER) { $env:PG_CONTAINER } else { 'finguard-postgres' }
$sourceDb  = if ($env:POSTGRES_DB)   { $env:POSTGRES_DB }   else { 'finguard' }
$dbUser    = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { 'finguard_user' }
$scratchDb = "${sourceDb}_restore_drill"
$tmpDir    = Join-Path ([System.IO.Path]::GetTempPath()) "finguard-drill-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Force $tmpDir | Out-Null

try {
    $swTotal = [System.Diagnostics.Stopwatch]::StartNew()
    & (Join-Path $PSScriptRoot 'backup-db.ps1') -OutputDir $tmpDir
    $dumpFile = (Get-ChildItem -Path $tmpDir -Filter '*.dump' | Select-Object -First 1).FullName

    docker exec $container psql -U $dbUser -d postgres -c "DROP DATABASE IF EXISTS $scratchDb;" | Out-Null
    docker exec $container psql -U $dbUser -d postgres -c "CREATE DATABASE $scratchDb;" | Out-Null

    $swRestore = [System.Diagnostics.Stopwatch]::StartNew()
    docker cp $dumpFile "${container}:/tmp/restore-drill.dump"
    docker exec $container pg_restore --no-owner --no-privileges --exit-on-error -U $dbUser -d $scratchDb /tmp/restore-drill.dump
    if ($LASTEXITCODE -ne 0) { throw "pg_restore failed with exit code $LASTEXITCODE" }
    $swRestore.Stop()

    $tables = (docker exec $container psql -U $dbUser -d $sourceDb -Atc "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename;") -split "`r?`n" | Where-Object { $_ -ne '' }
    foreach ($tbl in $tables) {
        $q = "SELECT count(*) || ':' || coalesce(md5(string_agg(t::text, '|' ORDER BY 1)), 'EMPTY') FROM (SELECT * FROM $tbl ORDER BY 1) t;"
        $srcSig = (docker exec $container psql -U $dbUser -d $sourceDb -Atc $q).Trim()
        $dstSig = (docker exec $container psql -U $dbUser -d $scratchDb -Atc $q).Trim()
        if ($srcSig -ne $dstSig) {
            throw "Checksum mismatch on table ${tbl}: source=$srcSig restored=$dstSig"
        }
        Write-Host "Verified table ${tbl}: $dstSig" -ForegroundColor Cyan
    }
    $swTotal.Stop()
    Write-Host "Restore drill PASSED (pg_restore RTO: $($swRestore.ElapsedMilliseconds) ms, total drill: $($swTotal.ElapsedMilliseconds) ms)." -ForegroundColor Green
} finally {
    docker exec $container rm -f /tmp/restore-drill.dump 2>$null | Out-Null
    docker exec $container psql -U $dbUser -d postgres -c "DROP DATABASE IF EXISTS $scratchDb;" 2>$null | Out-Null
    if (Test-Path $tmpDir) { Remove-Item -Recurse -Force $tmpDir }
}

