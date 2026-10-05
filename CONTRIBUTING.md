# Contributing

Thanks for looking. Two kinds of contribution are especially valuable here:

- **A report of what your passport did**, successful or not, from a country or
  document type the library has not seen. See
  [Reporting a read](#reporting-a-read).
- **Code changes**, with the checks below passing and, for anything that
  touches the read path, a read on a real passport.

Read [docs/architecture.md](docs/architecture.md) and
[docs/design-notes.md](docs/design-notes.md) first. The design notes explain
the decisions that look arbitrary and are not.

## Building

Needs JDK 17 and the Android SDK (API 36). Point Gradle at your SDK:

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
```

```bash
./gradlew :passport-reader:assembleDebug
./gradlew :sample-app:assembleDebug
./gradlew :passport-reader:testDebugUnitTest
./gradlew :sample-app:testDebugUnitTest
./gradlew lintDebug
./gradlew detekt apiCheck
./gradlew :sample-app:installDebug
```

What the tests cover, and how to test on a phone with a real passport, is in
[docs/testing.md](docs/testing.md).

## Before you open a pull request

Run the check script:

```bash
tools/checks.sh --staged
```

It builds both modules from exactly what you have staged, in a fresh
directory with the build cache off, checks that the sample app requests no
network access, then runs the unit tests, lint, detekt, the API check and the
licensing checks. Without `--staged` it checks the working tree
incrementally, which is quicker but not proof: a stale incremental build can
pass it while the commit fails to compile. `--fast` skips the assemble steps
and the network check; do not rely on it for the final run, since the sample
app failing to compile is how an API leak is caught.

Some checks print SKIP when their tooling is not set up. That is not a pass,
but it is not a failure either.

**detekt** runs against a baseline of existing issues, so it fails only on
new ones (see [design notes](docs/design-notes.md#static-analysis)). Fix what
it reports in your change rather than adding to the baseline.

**The public API is recorded** in `passport-reader/api/passport-reader.api`.
If `apiCheck` fails, your change altered the public API. If that was
intended, run `./gradlew apiDump` and commit the updated file, so the change
is visible in review.

## House rules

These are the rules a change is most likely to break.

- **New classes are `internal`** unless they are meant to be API. Kotlin makes
  everything public by default; the public packages are `data/`, `model/`,
  `reader/`, `timing/` and `trust/`.
- **No third-party type on the public API.** JMRTD, SCUBA and BouncyCastle are
  `implementation` dependencies because none of their types escapes. Convert
  in `mapping/`.
- **Write it rather than copy it in.** JMRTD is a linked dependency, and this
  repository contains none of its source. JMRTD 0.8.8 is the protocol layer
  only; if you need something above that, write it here.
- **Log through `NfcLog`, never `printStackTrace()`.** Personal data (MRZ
  fields, names, document numbers) goes only through `NfcLog.personal { }`,
  which is silent unless the host app is debuggable. A deliberately broad
  `catch` says so with a `// Deliberately broad:` comment and its reason.
- **Never put credentials or personal data in a `CheckResult` reason.** Those
  reach the `VerificationReport`, which apps display and send.
- **User-facing copy lives in string resources.** The library's error copy is
  in its `res/values/strings.xml` and reaches apps through
  `titleRes`/`messageRes`; the sample app does the same. The exceptions are
  deliberate: `technicalDetails` and `CheckResult` reasons are plain-English
  diagnostics.
- **Every delay belongs in `NfcReadTimingConfig`.** If you add one, add the
  field there, so the presets keep covering the whole read.
- **Decide on status words, not message text.** Error classification uses
  the status word and JMRTD's protocol step. The single exception, Android's
  stale-tag message, is documented where it is used.
- **New colour pairings in the sample app need a row in `ContrastTest`**
  (see [docs/sample-app.md](docs/sample-app.md#accessibility)).
- **Match the surrounding style.** Some of the chip code is noisier than the
  rest. Do not reformat it while making a functional change, or the diff stops
  being reviewable.

## Testing on a device

A change to the read path needs a read on a real passport, ideally one that
uses BAC and one that uses PACE. See
[docs/testing.md](docs/testing.md#testing-on-a-device), and mind the personal
data in debug logs and screenshots.

## Reporting a read

Reports from unfamiliar documents are how the library learns. Every branch
that depends on the document is logged under a named tag:

```bash
adb logcat -s PassportNFC PassiveAuthentication ActiveAuthentication PACE_PROBE PACE CHIP_AUTH DATA_GROUPS BIOMETRIC_FORMAT
```

| Tag | Says |
|---|---|
| `PassportNFC` | How access went (including the status word of a refused BAC), which files were read, and a one-line verification summary |
| `PassiveAuthentication`, `ActiveAuthentication` | Why a signature, chain, hash or chip-signature check failed |
| `PACE_PROBE` | Whether the chip answered the EF.CardAccess probe, and how |
| `PACE` | Which PACE variants were tried, and how each ended |
| `CHIP_AUTH` | How the chip proved it is genuine |
| `DATA_GROUPS` | Which optional data groups were present, read, or failed |
| `BIOMETRIC_FORMAT` | How the face and fingerprints were encoded, and whether they decoded |

Open an issue with:

- the issuing country and roughly when the passport was issued,
- the phone model and Android version,
- what happened (success, or the error shown), and
- those log lines, **with every name, date, document number and MRZ removed**.

See [docs/data-groups.md](docs/data-groups.md) for what the library reads and
which paths a real passport has already exercised.

## Commit messages

Say *why*, and what was measured or verified. When a change comes from device
testing, say what was observed. Several decisions here look arbitrary until
you know the failure that caused them, so the reasoning belongs in the
history.

## Security

Report vulnerabilities privately; see [SECURITY.md](SECURITY.md).

## Licence

By contributing, you agree that your contribution is licensed under the
[Apache License 2.0](LICENSE), as the rest of the project is.
