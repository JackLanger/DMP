param(
    [Parameter(Mandatory = $true)]
    [string]$FilePath,
    [guid]$TransferId = [guid]::Empty,
    [string]$ServerUrl = 'http://localhost:8080',
    [string]$NetrcPath = (Join-Path $PSScriptRoot 'fileserver.netrc')
)

$ErrorActionPreference = 'Stop'
$file = Get-Item -LiteralPath $FilePath
if ($file.PSIsContainer) {
    throw "FilePath must refer to a file."
}

$ServerUrl = $ServerUrl.TrimEnd('/')
$cookies = [System.IO.Path]::GetTempFileName()
$requestFile = $null
$chunkFile = $null
$headerFile = $null
$stream = $null
try {
    $stream = [System.IO.File]::Open($file.FullName, [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
    $length = $stream.Length
    $loginPage = curl.exe -fsS -c $cookies "$ServerUrl/login"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to fetch the login page."
    }
    $match = [regex]::Match(($loginPage -join "`n"), 'name="_csrf".*?value="([^"]*)"')
    if (-not $match.Success) {
        throw "CSRF token not found in the login page."
    }
    $csrf = $match.Groups[1].Value
    $requestFile = [System.IO.Path]::GetTempFileName()
    $chunkFile = [System.IO.Path]::GetTempFileName()
    $headerFile = [System.IO.Path]::GetTempFileName()

    if ($TransferId -eq [guid]::Empty) {
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try {
            $checksum = [System.BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '').ToLowerInvariant()
        }
        finally {
            $sha.Dispose()
        }
        $request = @{
            filename = $file.Name
            size = $length
            sha256 = $checksum
            description = 'Test upload'
            tags = @('test')
        } | ConvertTo-Json -Compress
        [System.IO.File]::WriteAllText($requestFile, $request, [System.Text.UTF8Encoding]::new($false))
        $response = curl.exe -fsS --netrc-file $NetrcPath -b $cookies `
            -H "X-CSRF-TOKEN: $csrf" -H "Content-Type: application/json" `
            --data-binary "@$requestFile" "$ServerUrl/files/transfers"
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to create the transfer session."
        }
        $status = ($response -join "`n") | ConvertFrom-Json
        $TransferId = [guid]$status.transferId
        if ($TransferId -eq [guid]::Empty) {
            throw "The server returned an invalid transfer ID."
        }
    }
    else {
        $response = curl.exe -fsS --netrc-file $NetrcPath -b $cookies `
            -H "X-CSRF-TOKEN: $csrf" "$ServerUrl/files/transfers/$TransferId"
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to retrieve transfer $TransferId."
        }
        $status = ($response -join "`n") | ConvertFrom-Json
    }

    Write-Host "Transfer ID: $TransferId"
    Write-Host "Resume with: .\create-transfer.ps1 -FilePath <file> -TransferId $TransferId"
    $offset = [long]$status.offset
    if ([long]$status.length -ne $length -or $offset -lt 0 -or $offset -gt $length) {
        throw "The local file length or server offset does not match the transfer."
    }

    $stream.Position = $offset
    $buffer = New-Object byte[] 262144
    while ($offset -lt $length) {
        $count = [int][Math]::Min($buffer.Length, $length - $offset)
        $read = 0
        while ($read -lt $count) {
            $bytesRead = $stream.Read($buffer, $read, $count - $read)
            if ($bytesRead -eq 0) {
                throw "Unexpected end of file at offset $($offset + $read)."
            }
            $read += $bytesRead
        }
        $chunk = [System.IO.File]::Open($chunkFile, [System.IO.FileMode]::Create,
            [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        try {
            $chunk.Write($buffer, 0, $count)
        }
        finally {
            $chunk.Dispose()
        }
        $end = $offset + $count - 1
        curl.exe -fsS -o NUL -D $headerFile --netrc-file $NetrcPath -b $cookies `
            -X PATCH -H "X-CSRF-TOKEN: $csrf" -H "Content-Type: application/octet-stream" `
            -H "Content-Range: bytes $offset-$end/$length" --data-binary "@$chunkFile" `
            "$ServerUrl/files/transfers/$TransferId"
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to upload transfer $TransferId at offset $offset."
        }
        $headers = [System.IO.File]::ReadAllText($headerFile)
        $offsetMatch = [regex]::Match($headers, '(?im)^Upload-Offset:\s*(\d+)\s*$')
        if (-not $offsetMatch.Success -or [long]$offsetMatch.Groups[1].Value -ne ($end + 1)) {
            throw "Server returned an unexpected upload offset for transfer $TransferId."
        }
        $offset = $end + 1
        Write-Host "Uploaded $offset of $length bytes."
    }

    $completion = curl.exe -fsS --netrc-file $NetrcPath -b $cookies `
        -X POST -H "X-CSRF-TOKEN: $csrf" "$ServerUrl/files/transfers/$TransferId/complete"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to complete transfer $TransferId."
    }
    $completion
}
finally {
    if ($null -ne $stream) { $stream.Dispose() }
    Remove-Item -LiteralPath $cookies
    if ($null -ne $requestFile) { Remove-Item -LiteralPath $requestFile }
    if ($null -ne $chunkFile) { Remove-Item -LiteralPath $chunkFile }
    if ($null -ne $headerFile) { Remove-Item -LiteralPath $headerFile }
}
