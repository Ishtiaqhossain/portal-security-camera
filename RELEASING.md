# Releasing the Portal Security Camera app

Cutting a release builds a **release-signed** `app-release.apk` and publishes it
to the project's [Releases](../../releases) page, where users download it
(see [`INSTALL.md`](INSTALL.md)). The build runs in CI — you only push a tag.

## How a release happens

1. Bump the version in `portal-app/app/build.gradle.kts`
   (`versionCode` **and** `versionName`) and commit it.
2. Tag and push:
   ```bash
   git tag v0.1.2
   git push origin v0.1.2
   ```
3. The **Release APK** workflow (`.github/workflows/release.yml`) builds the
   signed APK and creates the GitHub Release with auto-generated notes and the
   `app-release.apk` attached.

You can also run it manually from the **Actions** tab (workflow_dispatch),
supplying the tag to publish under.

Every push/PR also triggers the **CI** workflow (`.github/workflows/ci.yml`),
which builds the debug APK to catch breakage before you tag.

## One-time setup: signing secrets

CI signs with the dedicated release key. The keystore is **gitignored**
(`portal-app/release.keystore`) and must be provided to GitHub as secrets. The
build reads them via the `RELEASE_*` env vars already wired in
`build.gradle.kts`.

| Secret | Value |
|--------|-------|
| `RELEASE_KEYSTORE_BASE64` | base64 of `portal-app/release.keystore` |
| `RELEASE_STORE_PASSWORD`  | keystore password |
| `RELEASE_KEY_ALIAS`       | key alias (`portal-release`) |
| `RELEASE_KEY_PASSWORD`    | key password |

Set them with the `gh` CLI (values live in your gitignored
`portal-app/local.properties`):

```bash
base64 -i portal-app/release.keystore | gh secret set RELEASE_KEYSTORE_BASE64
gh secret set RELEASE_STORE_PASSWORD   # paste when prompted
gh secret set RELEASE_KEY_ALIAS
gh secret set RELEASE_KEY_PASSWORD
```

> **Keep the keystore safe.** If you lose `release.keystore` or its passwords
> you can no longer ship upgrades signed with the same identity — users would
> have to uninstall/reinstall. Back up both somewhere offline.

The release workflow **fails** if any secret is missing or if the resulting APK
is debug-signed, so a misconfiguration can never ship an unsigned build to
users.
