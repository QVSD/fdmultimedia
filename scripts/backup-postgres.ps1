[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$OutputDirectory,
    [string]$Container = "fdmultimedia-postgres-1",
    [string]$Database = $env:POSTGRES_DB,
    [string]$DatabaseUser = $env:POSTGRES_USER
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($Database)) {
    throw "Database is required. Pass -Database or set POSTGRES_DB."
}
if ([string]::IsNullOrWhiteSpace($DatabaseUser)) {
    throw "DatabaseUser is required. Pass -DatabaseUser or set POSTGRES_USER."
}
if ($Database -notmatch '^[A-Za-z_][A-Za-z0-9_]{0,62}$') {
    throw "Database contains unsupported characters."
}
if ($DatabaseUser -notmatch '^[A-Za-z_][A-Za-z0-9_]{0,62}$') {
    throw "DatabaseUser contains unsupported characters."
}

$directory = [System.IO.Path]::GetFullPath($OutputDirectory)
[System.IO.Directory]::CreateDirectory($directory) | Out-Null
$timestamp = [DateTimeOffset]::UtcNow.ToString("yyyyMMddTHHmmssZ")
$output = Join-Path $directory ("fdmultimedia-{0}-{1}.dump" -f $Database, $timestamp)
$containerFile = "/tmp/fdm-backup-$([Guid]::NewGuid().ToString('N')).dump"

try {
    & docker exec -e "FDM_BACKUP_DB=$Database" -e "FDM_BACKUP_USER=$DatabaseUser" $Container sh -c 'pg_dump -U "$FDM_BACKUP_USER" -d "$FDM_BACKUP_DB" -Fc --no-owner --no-privileges --exclude-table-data=public.spring_session --exclude-table-data=public.spring_session_attributes -f "$1"' -- $containerFile
    if ($LASTEXITCODE -ne 0) { throw "pg_dump failed." }
    & docker cp "${Container}:$containerFile" $output
    if ($LASTEXITCODE -ne 0) { throw "docker cp failed." }
} finally {
    & docker exec $Container rm -f $containerFile 2>$null | Out-Null
}

Write-Output $output
