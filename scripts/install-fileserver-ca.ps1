param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string] $Certificate,
    [Parameter(Mandatory = $true, Position = 1)]
    [string] $ExpectedFingerprint
)

$ErrorActionPreference = 'Stop'
$expected = $ExpectedFingerprint.Replace(':', '').ToUpperInvariant()
if ($expected -notmatch '^[0-9A-F]{64}$') {
    throw 'Expected fingerprint must be 64 hexadecimal characters, optionally separated by colons.'
}
if (-not (Test-Path -LiteralPath $Certificate -PathType Leaf)) {
    throw "Cannot read certificate: $Certificate"
}

$content = Get-Content -LiteralPath $Certificate -Raw
if ($content -notmatch '(?s)-----BEGIN CERTIFICATE-----\s*(.*?)\s*-----END CERTIFICATE-----') {
    throw 'Expected a PEM certificate.'
}
$bytes = [Convert]::FromBase64String($Matches[1] -replace '\s', '')
$cert = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new($bytes)
$sha256 = [System.Security.Cryptography.SHA256]::Create()
try {
    $fingerprint = [BitConverter]::ToString($sha256.ComputeHash($cert.RawData)).Replace('-', '')
    if ($fingerprint -ne $expected) {
        throw "Certificate fingerprint does not match; nothing was installed. Actual SHA-256 fingerprint: $fingerprint"
    }

    $store = [System.Security.Cryptography.X509Certificates.X509Store]::new(
        [System.Security.Cryptography.X509Certificates.StoreName]::Root,
        [System.Security.Cryptography.X509Certificates.StoreLocation]::CurrentUser
    )
    try {
        $store.Open([System.Security.Cryptography.X509Certificates.OpenFlags]::ReadWrite)
        $store.Add($cert)
    }
    finally {
        $store.Close()
    }
}
finally {
    $sha256.Dispose()
    $cert.Dispose()
}

Write-Host 'FileServer CA installed for the current Windows user. Restart your browser if necessary.'
