# Windows CI signing

The Windows workflow signs the portable build's `.exe`, `.dll`, and `.pyd` files, then signs the Inno Setup installer. It requires a trusted Authenticode code-signing certificate exported as a password-protected PFX.

Add these repository Actions secrets before running the Windows workflow:

- `WINDOWS_SIGNING_CERTIFICATE_BASE64`: Base64-encoded PFX bytes.
- `WINDOWS_SIGNING_PFX_PASSWORD`: The PFX export password.

Create the Base64 value locally in PowerShell without printing the PFX contents:

```powershell
$pfxPath = "C:\path\to\codesign.pfx"
[Convert]::ToBase64String([IO.File]::ReadAllBytes($pfxPath)) | Set-Clipboard
```

Paste the clipboard value directly into the `WINDOWS_SIGNING_CERTIFICATE_BASE64` secret. The workflow uses SHA-256 Authenticode signing with an RFC 3161 timestamp and verifies each signature before packaging.

The public certificate is included in the portable folder and installer as `UnipusAI-Helper-CodeSigning.cer`. The generated certificate currently has SHA-1 thumbprint `8594D22017FE0E9545CC59A734F9186C633D7733`; verify the thumbprint through this repository before trusting the certificate. To trust it for the current Windows user, import the CER into both `Trusted Root Certification Authorities` and `Trusted Publishers`.

Signatures let Windows and `signtool verify` detect changes to signed executable binaries and the installer. They do not encrypt Python source or resource files, and they do not stop a user from replacing files; they make changes to signed files detectable. This self-signed certificate is useful for controlled distribution where recipients verify and trust its thumbprint. It is not a publicly trusted CA certificate and does not provide general SmartScreen reputation. The private key stays in GitHub Secrets and is removed from the runner after each signing step.
