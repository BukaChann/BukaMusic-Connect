# Signed update release

The client no longer contains object-storage credentials. Update discovery is
fail-closed and only works when `BUKA_UPDATE_MANIFEST_PUBLIC_KEY` points to a
public ECDSA P-256 key.

## One-time key setup

Create the private key in the protected release environment, never in this
repository:

```powershell
openssl ecparam -name prime256v1 -genkey -noout -out update-signing-private.pem
openssl ec -in update-signing-private.pem -pubout -out update-signing-public.pem
```

Store `update-signing-private.pem` in the CI secret store and back it up
according to the release-key policy. The public key is not secret.

The repository's current public key is `update-signing-public.pem`. Release
builds load it automatically when `BUKA_UPDATE_MANIFEST_PUBLIC_KEY` is not set.
Its SHA-256 fingerprint is:

```text
9a632fe8c35fb163448d3725fb85a477af0d7efc828b4364c63a7d555813351f
```

On this development machine the matching private key is protected with Windows
DPAPI at:

```text
%LOCALAPPDATA%\Codex\buka-update-signing-private.dpapi
```

It can only be decrypted by the same Windows user profile. Back it up through a
secure password manager or protected CI secret store; anyone who obtains it can
sign updates for existing clients.

## Manifest

Publish `latest.json` in the public Gitee release repository:

```json
{
  "versionCode": 327,
  "versionName": "3.2.7",
  "url": "https://gitee.com/hanhan2048/BukaMusic-Releases/releases/download/v3.2.7/BukaMusic-v3.2.7-release.apk",
  "notes": "Release notes",
  "size": 12345678,
  "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
}
```

The URL, size and SHA-256 must describe the exact APK uploaded in the same
release. Sign the exact manifest bytes:

```powershell
./tools/sign-update-manifest.ps1 `
  -Manifest ./latest.json `
  -ProtectedPrivateKey "$env:LOCALAPPDATA\Codex\buka-update-signing-private.dpapi" `
  -Signature ./latest.json.sig
```

Upload both `latest.json` and `latest.json.sig`.

## Build configuration

The default update channel is:

```text
https://gitee.com/hanhan2048/BukaMusic-Releases/raw/master/latest.json
```

Set the public key in the protected release build environment:

```powershell
$env:BUKA_UPDATE_MANIFEST_PUBLIC_KEY = Get-Content ./update-signing-public.pem -Raw
$env:BUKA_UPDATE_MANIFEST_URL = "https://gitee.com/hanhan2048/BukaMusic-Releases/raw/master/latest.json"
```

The default host allowlist is:

```text
gitee.com,raw.giteeusercontent.com
```

Override it only when using another trusted release host:

```powershell
$env:BUKA_UPDATE_ALLOWED_HOSTS = "gitee.com,raw.giteeusercontent.com"
```

If the key, signature, host, digest or APK signer cannot be verified, update
installation is refused. Version codes must be strictly newer than the
installed app. The build must be signed by the same release certificate as the
installed APK.

## Release signing

Release builds no longer fall back to the Android debug keystore. Configure the
release certificate only in the protected build environment:

```powershell
$env:BUKA_RELEASE_STORE_FILE = "D:\secure\bukamusic-release.jks"
$env:BUKA_RELEASE_STORE_PASSWORD = "..."
$env:BUKA_RELEASE_KEY_ALIAS = "bukamusic"
$env:BUKA_RELEASE_KEY_PASSWORD = "..."
```

Without these values, a release APK is unsigned and cannot be published.
