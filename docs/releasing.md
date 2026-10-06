# Releasing

A release is one version number shared by the library and the sample app,
one git tag `v<version>` marking both, the library on Maven Central, and the
signed sample app attached to a GitHub Release.

The version lives in one place, `VERSION_NAME` in `gradle.properties`, as
`MAJOR.MINOR.PATCH` with minor and patch below 100. The sample app derives
its `versionCode` from it (`major * 10000 + minor * 100 + patch`, so 0.1.0 is
100), which only ever has to go up. Below, `<version>` stands for it, as in
`0.1.0`.

## One-time setup

Everything secret lives in the publisher's own `~/.gradle/gradle.properties`,
never in this repository.

**Maven Central.** Sign in at <https://central.sonatype.com> with the GitHub
account that owns the repository; that verifies the `io.github.munkchunk`
namespace. Generate a user token and add it:

```properties
mavenCentralUsername=<token username>
mavenCentralPassword=<token password>
```

**Signing the library.** Central rejects unsigned artefacts. Create a GPG key,
publish its public half to a keyserver Central checks (keys.openpgp.org), and
export the secret key for Gradle to a file outside `~/.gnupg`, where GnuPG
could mistake it for its own:

```bash
gpg --full-generate-key                      # RSA 4096
gpg --keyserver keys.openpgp.org --send-keys <key id>
gpg --export-secret-keys <key id> > ~/.gradle/release-signing.gpg
```

```properties
signing.keyId=<last 8 hex digits of the key id>
signing.password=<passphrase>
signing.secretKeyRingFile=/home/<you>/.gradle/release-signing.gpg
```

An ASCII-armoured key in `signingInMemoryKey` (with `signingInMemoryKeyId`
and `signingInMemoryKeyPassword`) works instead, which suits CI. With neither
`signing.keyId` nor `signingInMemoryKey` set, the build signs nothing: that
is what lets anyone run `publishToMavenLocal`, and Central rejects the
result.

**Signing the sample app.** A PKCS12 keystore kept outside the repository:

```properties
RELEASE_STORE_FILE=/path/to/release.p12
RELEASE_STORE_PASSWORD=<password>
RELEASE_KEY_ALIAS=release
RELEASE_KEY_PASSWORD=<password>
```

Back the keystore up. An installed APK can only be updated by one signed
with the same key, so losing it means every user has to uninstall to move
to a new one.

The sample app's signing certificate:

```
CN=Iain Griffiths
SHA-256 04:C4:F9:CF:49:A5:E7:26:0D:65:60:F9:FB:09:CA:9D:82:02:A3:34:53:27:F8:DE:B9:18:8F:57:93:11:9D:4A
```

## Each release

1. **Set the version.** Change `VERSION_NAME`, and anything in `README.md`
   that quotes it, and commit on `main`. `tools/checks.sh --staged` must pass.
2. **Push `main` and wait for CI** to go green on that commit. CI builds debug
   only, so the next step is the only test of the release build.
3. **Device test the release build**, not the debug one: it is minified, and
   R8 is the likeliest thing to break a read. Android will not install it over
   a debug build, which is signed with a different key, so uninstall that
   first:

   ```bash
   adb uninstall io.github.munkchunk.passportreader.sample
   ./gradlew :sample-app:installRelease
   ```

   Read a BAC-only document and a PACE one in full and scan an MRZ with the
   camera. The release build writes no log, so judge it by the result screen.
4. **Tag and push the tag.**

   ```bash
   git tag -a v<version> -m "<version>"
   git push origin v<version>
   ```

5. **Publish the library.**

   ```bash
   ./gradlew :passport-reader:publishAndReleaseToMavenCentral
   ```

   The deployment shows at <https://central.sonatype.com/publishing>. It is
   validated, then released; it can take half an hour to resolve from
   `mavenCentral()`.

6. **Build and check the APK.**

   ```bash
   ./gradlew :sample-app:assembleRelease
   cp sample-app/build/outputs/apk/release/sample-app-release.apk passport-reader-sample-<version>.apk
   apksigner verify --print-certs passport-reader-sample-<version>.apk   # the certificate above
   sha256sum passport-reader-sample-<version>.apk
   ```

7. **GitHub Release.** Create a release from the tag, attach the APK, and put
   its SHA-256 and what changed in the notes.
8. **Check from outside.** A new project depending on
   `io.github.munkchunk:passport-reader:<version>` resolves and builds, and the
   APK from the release page installs.

Once something is on Maven Central it cannot be changed or removed. A mistake
is fixed by releasing the next patch version.
