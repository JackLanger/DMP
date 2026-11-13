# posts a file to the file server
param(
    [Parameter(Mandatory = $true)]
    [string]$FilePath
)

$cookies = [System.IO.Path]::GetTempFileName()
$metadataFile = $null

try {
    $metadataFile = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText(
        $metadataFile,
        '{"description":"Test upload","tags":["test"]}',
        [System.Text.UTF8Encoding]::new($false)
    )

    $loginPage = curl.exe -fsS -c $cookies http://localhost:8080/login
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to fetch the login page."
    }

    $match = [regex]::Match(($loginPage -join "`n"), 'name="_csrf".*?value="([^"]*)"')
    if (-not $match.Success) {
        throw "CSRF token not found in the login page."
    }

    $csrf = $match.Groups[1].Value
    curl.exe --fail-with-body -i --netrc-file fileserver.netrc -b $cookies `
        -H "X-CSRF-TOKEN: $csrf" `
        -F "file=@$FilePath" `
        -F "metadata=<$metadataFile;type=application/json" `
        http://localhost:8080/files
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to upload the file."
    }
}
finally {
    Remove-Item -LiteralPath $cookies
    if ($null -ne $metadataFile) {
        Remove-Item -LiteralPath $metadataFile
    }
}
