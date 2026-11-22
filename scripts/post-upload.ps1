# posts a file to the file server
param(
    [Parameter(Mandatory = $true)]
    [string]$FilePath
)

if (-not $env:FILESERVER_USER -or -not $env:FILESERVER_ACCESS_TOKEN) {
    throw "Set FILESERVER_USER and FILESERVER_ACCESS_TOKEN from SecretService."
}
$curl = if ($PSVersionTable.PSVersion.Major -lt 6 -or $IsWindows) { 'curl.exe' } else { 'curl' }
if ($env:FILESERVER_USER -cnotmatch '^[a-zA-Z0-9][a-zA-Z0-9_.-]{2,63}$' -or
    $env:FILESERVER_ACCESS_TOKEN -cnotmatch '^[a-zA-Z0-9_-]{43}$') {
    throw "Invalid FILESERVER_USER or FILESERVER_ACCESS_TOKEN."
}
$authHeaders = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($authHeaders,
    "X-User: $($env:FILESERVER_USER)`nAuthorization: Bearer $($env:FILESERVER_ACCESS_TOKEN)`n")
$metadataFile = $null
$serverUrl = if ($env:FILESERVER_URL) { $env:FILESERVER_URL.TrimEnd('/') } else { 'http://localhost:8080' }

try {
    $metadataFile = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText(
        $metadataFile,
        '{"description":"Test upload","tags":["test"]}',
        [System.Text.UTF8Encoding]::new($false)
    )

    & $curl --fail-with-body -i -H "@$authHeaders" `
        -F "file=@$FilePath" `
        -F "metadata=<$metadataFile;type=application/json" `
        "$serverUrl/files"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to upload the file."
    }
}
finally {
    Remove-Item -LiteralPath $authHeaders
    if ($null -ne $metadataFile) {
        Remove-Item -LiteralPath $metadataFile
    }
}
