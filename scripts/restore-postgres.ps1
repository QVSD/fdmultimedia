[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$InputFile,
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$TargetDatabase,
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$ConfirmTargetDatabase,
    [string]$Container = "fdmultimedia-postgres-1",
    [string]$DatabaseUser = $env:POSTGRES_USER,
    [switch]$ReplaceTarget
)

$ErrorActionPreference = "Stop"
if ($TargetDatabase -cne $ConfirmTargetDatabase) {
    throw "ConfirmTargetDatabase must exactly match TargetDatabase."
}
if ($TargetDatabase -notmatch '^[A-Za-z_][A-Za-z0-9_]{0,62}$') {
    throw "TargetDatabase contains unsupported characters."
}
if ([string]::IsNullOrWhiteSpace($DatabaseUser)) {
    throw "DatabaseUser is required. Pass -DatabaseUser or set POSTGRES_USER."
}
if ($DatabaseUser -notmatch '^[A-Za-z_][A-Za-z0-9_]{0,62}$') {
    throw "DatabaseUser contains unsupported characters."
}
if ($TargetDatabase -in @("postgres", "template0", "template1")) {
    throw "Refusing to restore over a PostgreSQL system database."
}
$configuredDatabase = & docker exec $Container printenv POSTGRES_DB
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($configuredDatabase | Out-String).Trim())) {
    throw "Cannot determine the container's primary database; refusing restore."
}
if ($TargetDatabase -eq ($configuredDatabase | Out-String).Trim()) {
    throw "Refusing to restore over the container's primary database."
}
if ($env:POSTGRES_DB -and $TargetDatabase -eq $env:POSTGRES_DB) {
    throw "Refusing to restore over POSTGRES_DB. Use a fresh, explicitly named target database."
}

$source = [System.IO.Path]::GetFullPath($InputFile)
$containerFile = "/tmp/fdm-restore-$([Guid]::NewGuid().ToString('N')).dump"
try {
    & docker cp $source "${Container}:$containerFile"
    if ($LASTEXITCODE -ne 0) { throw "docker cp failed." }
    $exists = & docker exec $Container psql -U $DatabaseUser -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '$TargetDatabase'"
    if ($LASTEXITCODE -ne 0) { throw "Target database lookup failed." }
    if (($exists | Out-String).Trim() -eq "1") {
        if (-not $ReplaceTarget) {
            throw "Target database already exists. Pass -ReplaceTarget only for a disposable target."
        }
        & docker exec -e "FDM_TARGET_DB=$TargetDatabase" -e "FDM_DB_USER=$DatabaseUser" $Container sh -c 'dropdb -U "$FDM_DB_USER" --force "$FDM_TARGET_DB"'
        if ($LASTEXITCODE -ne 0) { throw "Failed to drop disposable target database." }
    }
    & docker exec -e "FDM_TARGET_DB=$TargetDatabase" -e "FDM_DB_USER=$DatabaseUser" $Container sh -c 'createdb -U "$FDM_DB_USER" "$FDM_TARGET_DB"'
    if ($LASTEXITCODE -ne 0) { throw "Failed to create target database." }
    & docker exec -e "FDM_TARGET_DB=$TargetDatabase" -e "FDM_DB_USER=$DatabaseUser" $Container sh -c 'pg_restore -U "$FDM_DB_USER" -d "$FDM_TARGET_DB" --no-owner --no-privileges --exit-on-error "$1"' -- $containerFile
    if ($LASTEXITCODE -ne 0) { throw "pg_restore failed." }
    # The SQL is piped on stdin: quoting of embedded spaces/quotes does not survive Windows PowerShell native-argument parsing.
    'TRUNCATE spring_session_attributes, spring_session;' | & docker exec -i $Container psql -U $DatabaseUser -d $TargetDatabase -v ON_ERROR_STOP=1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Session invalidation failed." }
} finally {
    & docker exec $Container rm -f $containerFile 2>$null | Out-Null
}

Write-Output $TargetDatabase
