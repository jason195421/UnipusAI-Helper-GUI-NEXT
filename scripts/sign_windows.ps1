param(
    [Parameter(Mandatory = $true)]
    [string]$Path,
    [string]$ExportCertificatePath,
    [switch]$RequireSigning
)

$ErrorActionPreference = "Stop"

if (-not $env:WINDOWS_SIGNING_CERTIFICATE_BASE64 -or -not $env:WINDOWS_SIGNING_PFX_PASSWORD) {
    if ($RequireSigning) {
        throw "Set WINDOWS_SIGNING_CERTIFICATE_BASE64 and WINDOWS_SIGNING_PFX_PASSWORD repository secrets."
    }
    Write-Warning "No code-signing certificate configured; output will be unsigned."
    exit 0
}

$signtool = (Get-Command signtool.exe -ErrorAction SilentlyContinue).Source
if (-not $signtool) {
    $kitsRoot = "${env:ProgramFiles(x86)}\Windows Kits\10\bin"
    $signtool = Get-ChildItem -Path $kitsRoot -Filter signtool.exe -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.Directory.Name -eq "x64" } |
        Sort-Object { [version]$_.Directory.Parent.Name } -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $signtool) {
    throw "signtool.exe was not found in the Windows SDK."
}

$target = (Resolve-Path -LiteralPath $Path).Path
$pfxPath = Join-Path $env:RUNNER_TEMP "unipus-code-signing.pfx"
$cerPath = Join-Path $env:RUNNER_TEMP "unipus-code-signing.cer"
[IO.File]::WriteAllBytes($pfxPath, [Convert]::FromBase64String($env:WINDOWS_SIGNING_CERTIFICATE_BASE64))
$certificate = $null
$thumbprint = $null
$addedStores = @()

try {
    $certificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new(
        $pfxPath,
        $env:WINDOWS_SIGNING_PFX_PASSWORD,
        [System.Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet
    )
    $thumbprint = $certificate.Thumbprint
    [IO.File]::WriteAllBytes(
        $cerPath,
        $certificate.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Cert)
    )

    foreach ($storeName in @("Root", "TrustedPublisher")) {
        $storePath = "Cert:\CurrentUser\$storeName"
        $present = Get-ChildItem -Path $storePath | Where-Object { $_.Thumbprint -eq $thumbprint }
        if (-not $present) {
            Import-Certificate -FilePath $cerPath -CertStoreLocation $storePath | Out-Null
            $addedStores += $storePath
        }
    }

    if ($ExportCertificatePath) {
        $exportDirectory = Split-Path -Parent $ExportCertificatePath
        if ($exportDirectory) {
            New-Item -ItemType Directory -Path $exportDirectory -Force | Out-Null
        }
        Copy-Item -LiteralPath $cerPath -Destination $ExportCertificatePath -Force
    }

    if (Test-Path -LiteralPath $target -PathType Container) {
        $files = @(Get-ChildItem -LiteralPath $target -Recurse -File |
            Where-Object { $_.Extension -in @(".exe", ".dll", ".pyd") })
    } else {
        $files = @(Get-Item -LiteralPath $target)
    }
    if ($files.Count -eq 0) {
        throw "No executable binaries found to sign under $target"
    }

    foreach ($file in $files) {
        & $signtool sign /fd SHA256 /f $pfxPath /p $env:WINDOWS_SIGNING_PFX_PASSWORD `
            /tr https://timestamp.digicert.com /td SHA256 /v $file.FullName
        if ($LASTEXITCODE -ne 0) { throw "Signing failed: $($file.FullName)" }
        & $signtool verify /pa /all /v $file.FullName
        if ($LASTEXITCODE -ne 0) { throw "Signature verification failed: $($file.FullName)" }
    }
    Write-Host "Signed and verified $($files.Count) binaries."
}
finally {
    if ($thumbprint) {
        foreach ($storePath in $addedStores) {
            Get-ChildItem -Path $storePath | Where-Object { $_.Thumbprint -eq $thumbprint } |
                Remove-Item -Force -ErrorAction SilentlyContinue
        }
    }
    Remove-Item -LiteralPath $pfxPath -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $cerPath -Force -ErrorAction SilentlyContinue
}
