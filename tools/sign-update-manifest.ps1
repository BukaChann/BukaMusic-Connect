[CmdletBinding(DefaultParameterSetName = "Pem")]
param(
    [Parameter(Mandatory = $true)]
    [string]$Manifest,

    [Parameter(Mandatory = $true, ParameterSetName = "Pem")]
    [string]$PrivateKey,

    [Parameter(Mandatory = $true, ParameterSetName = "Dpapi")]
    [string]$ProtectedPrivateKey,

    [Parameter(Mandatory = $true)]
    [string]$Signature
)

$ErrorActionPreference = "Stop"
$manifestPath = (Resolve-Path -LiteralPath $Manifest).Path
$signaturePath = [System.IO.Path]::GetFullPath($Signature)

$data = [System.IO.File]::ReadAllBytes($manifestPath)
[byte[]]$plain = $null

try {
    if ($PSCmdlet.ParameterSetName -eq "Dpapi") {
        Add-Type -AssemblyName System.Security.Cryptography.ProtectedData
        $keyPath = (Resolve-Path -LiteralPath $ProtectedPrivateKey).Path
        $protected = [System.IO.File]::ReadAllBytes($keyPath)
        $entropy = [System.Text.Encoding]::UTF8.GetBytes("BukaMusicUpdateSigningV1")
        try {
            $plain = [System.Security.Cryptography.ProtectedData]::Unprotect(
                $protected,
                $entropy,
                [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
            $pem = [System.Text.Encoding]::UTF8.GetString($plain)
        }
        finally {
            if ($null -ne $plain) {
                [Array]::Clear($plain, 0, $plain.Length)
            }
        }
    }
    else {
        $keyPath = (Resolve-Path -LiteralPath $PrivateKey).Path
        $pem = [System.IO.File]::ReadAllText($keyPath)
    }

    $ecdsa = [System.Security.Cryptography.ECDsa]::Create()
    $ecdsa.ImportFromPem($pem)
    $signatureBytes = $ecdsa.SignData(
        $data,
        [System.Security.Cryptography.HashAlgorithmName]::SHA256)
    $text = [Convert]::ToBase64String($signatureBytes) + "`n"
    [System.IO.File]::WriteAllText(
        $signaturePath,
        $text,
        [System.Text.UTF8Encoding]::new($false))
}
finally {
    if ($null -ne $ecdsa) {
        $ecdsa.Dispose()
    }
    $pem = $null
    $protected = $null
    $entropy = $null
}

Write-Host "Signed manifest: $signaturePath"
