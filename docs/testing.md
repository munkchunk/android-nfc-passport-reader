# Testing

What the automated tests cover, and how to test on a phone with a real
passport. The checks a change must pass before review are in
[CONTRIBUTING.md](../CONTRIBUTING.md#before-you-open-a-pull-request).

## Automated tests

```bash
./gradlew :passport-reader:testDebugUnitTest :sample-app:testDebugUnitTest
```

Unit tests cover what can be tested away from a chip:

- **Library:** Passive and Active Authentication against documents generated
  at test time with BouncyCastle (a wrong signing key, hashes swapped after
  signing, an untrusted CSCA, a tampered group, a cloned chip, a replayed
  answer); PACE-CAM against ICAO 9303-11 Appendix I; EF.CardAccess against
  DG14; DG2 face details in both encodings; parcelling at API 29 and 33;
  data-group mapping and DG16 parsing; error classification from observed
  exception chains; when PACE retries; chip-authentication key choice;
  terminal credentials; CSCA loading.
- **Sample app:** MRZ parsing, dates, check digits, how DG11–DG16 are
  displayed, and palette contrast (`ContrastTest`; see
  [sample-app.md](sample-app.md#contrast-and-how-it-is-enforced)).

The parcelling tests run under Robolectric at API 29 and 33, which downloads
each level's `android-all` jar on the first run, so that run needs network.

Three instrumented tests in `passport-reader/src/androidTest` need a device
or emulator (`./gradlew :passport-reader:connectedDebugAndroidTest`):

- `Jpeg2000DecoderTest` decodes JPEG 2000 portraits through OpenJPEG and
  bounds the error, since few passports exercise that path.
- `VerificationStateTest` pins the starting state of every check, which the
  read path relies on to tell a first result from a later one.
- `CertificateCountryTest` reads the country off a real CSCA certificate.

**The exchanges with the chip itself have no automated coverage**: BAC, PACE,
chip authentication and reading files. The decisions around them are tested
(when to retry, which key to try, how to classify a failure), but verifying a
change to the read path needs a phone and a real passport.

## Testing on a device

No emulator can read a passport. A change to the read path is tested by
reading real documents with the sample app:

```bash
./gradlew :sample-app:installDebug
adb logcat -s NfcPassportReader PassportNFC PassiveAuthentication ActiveAuthentication NFC_PERF PACE_PROBE PACE CHIP_AUTH DATA_GROUPS BIOMETRIC_FORMAT NfcPassportReaderManager
```

In the app, either scan the machine readable zone with the camera or enter the
three fields by hand (document number, date of birth as YYMMDD, expiry date as
YYMMDD), then hold the passport against the back of the phone. The chip is
usually in the back cover. Keep both still.

A successful read shows, in order: authentication succeeding (BAC or PACE),
the holder's details matching what is printed, the DG2 photograph, and the
verification report.

**Test on one passport that uses BAC and one that uses PACE** if you can.
They take different paths and fail differently: a BAC chip, for instance,
may refuse extended-length APDUs, and some failures appear only there. An
older passport without EF.CardAccess usually uses BAC.

**If a read fails with "incorrect passport details"**, check that the MRZ you
entered matches the document in your hand before investigating anything else.
With two passports to hand, it is easy to enter one's details and present the
other.

**If the chip reads but passive authentication fails**, check that the issuing
country has a trust anchor before suspecting the code: six countries are
missing from the bundled list. See [NOTICE.md](../NOTICE.md).

**Timings vary by about ±0.5 s from run to run.** Measure several reads,
using the `NFC_PERF` lines, before believing a difference (see
[design-notes.md](design-notes.md#where-the-time-goes)).

### The demo screen

Most passports carry few of the optional data groups. To see every result
panel without one that does, the debug build has a demo screen showing an
invented report on the ICAO 9303 specimen:

```bash
adb shell am start -n io.github.munkchunk.passportreader.sample/.demo.DemoResultActivity
```

Add `--ez noFace true` to drop the DG2 face, which shows the holder panel
falling back to the DG5 portrait.

### Mind the personal data

A debug build logs MRZ fields and other personal data from the document, and
a screenshot of a real read shows the holder's details and photograph. Do not
attach either to an issue or a pull request, and delete saved logs when you
are done. The demo screen holds only the ICAO specimen, so its screenshots
are safe.

To report what an unfamiliar passport did, see
[Reporting a read](../CONTRIBUTING.md#reporting-a-read).
