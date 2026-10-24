# Building in GitHub Actions

`.github/workflows/build.yml` builds both installable flavours on every push and publishes a
GitHub release when a version tag is pushed.

## What runs

| Trigger | Result |
|---|---|
| push to `main`, pull request | APK artifact `aemulator-apks` (30 days) |
| tag `0.0.0.4` or `v0.0.0.4` | release with both APKs + `SHA256SUMS.txt` |
| *Run workflow* with **release** on | release, tag from the input or `build-<date>` |

The job installs JDK 17, Android SDK platform 36 and build-tools 36.0.0, then runs:

```bash
./gradlew assembleStandardRelease assembleV0003Release
```

Both flavours are built from the same sources: `app.aemu` (normal) and `app.aemu.v0003`
(installs next to it). The natively built engine binaries and the ~477 MB of guest assets are
already in the repository, so **no NDK is needed** for a normal build.

## Signing

Android only installs an update when the signature matches the installed app.

* **With secrets** — the release key is used and the APK updates an existing install.
* **Without secrets** — a throwaway key is generated per run. The APK installs on a clean
  phone but cannot update an official AEmulator build, nor a build made by another CI run.

This fork has the four secrets configured, so all of its builds share one signature.
GitHub secrets cannot be read back: if the keystore is ever lost, a new one can be made,
but the first build signed with it will need one uninstall/reinstall on each device.

Add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Meaning |
|---|---|
| `KEYSTORE_BASE64` | your `.jks`, base64-encoded: `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | store password |
| `KEY_ALIAS` | key alias (default `aemulator`) |
| `KEY_PASSWORD` | key password (default: store password) |

Generate a key once:

```bash
keytool -genkeypair -keystore release.jks -alias aemulator \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=AEmulator, O=AEmulator, C=US"
base64 -w0 release.jks        # paste into KEYSTORE_BASE64
```

`keystore.properties` and `*.jks` stay in `.gitignore`; the workflow writes them from the
secrets in the runner's workspace only.

## Releasing

```bash
# bump versionCode / versionName in app/build.gradle.kts, commit, then
git tag 0.0.0.4
git push origin main --tags
```

The tag push builds and publishes the release. Notes are generated from the commits since the
previous tag.

## Known issues fixed for CI

* `gradle.properties` contained `org.gradle.java.home=C:/Program Files/Android/Android Studio/jbr`.
  A Windows-only absolute path makes Gradle fail everywhere else; it was removed. Set `JAVA_HOME`
  instead. The workflow also strips it if it is ever added back.
* `gradlew` was committed without the executable bit (`100644`) — `./gradlew` failed on Linux with
  "Permission denied". It is now `100755`.
* `release` without `keystore.properties` produces an unsigned APK, which Android refuses to
  install. The workflow always writes one (real key or throwaway).
