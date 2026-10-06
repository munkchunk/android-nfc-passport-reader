# Design notes

The library's design decisions, and the reason for each. Several look
arbitrary, or like something to tidy away, until you know the failure they
prevent. Read these before changing the read path.

For how a read works end to end, see [architecture.md](architecture.md).

## What belongs in the library

**Two modules: a library and a sample app.** Getting a reliable read on real
hardware is harder than the chip protocols themselves, so everything that
makes a read work belongs in `:passport-reader`: the protocols, the data
model, the verification report, and the reliability layer around ReaderMode.
The library has no dependency injection, analytics, UI or product flavours.
The sample app has one `Activity`, no DI and no navigation library, so the
wiring an app has to do stays visible.

**The timing layer is in the library, not left to apps.** It is the part most
expensive to rediscover. `NfcReadTimingConfig` has a default for every field
and `NfcTimingPresets` offers `DEFAULT`, `CONSERVATIVE` and `FAST`, so it can
be tuned but never has to be.

**Deciding whether a report is good enough is the app's job.** The library
reports each check and its verdict. Whether, say, a passport without chip
authentication is acceptable is a product decision, so no validator ships
here.

**Error reporting is an interface, not a dependency.** `NfcErrorReporter` is
an optional constructor argument; the library never calls a crash reporter
itself. It is not called for a cancelled read, and is called on the main
thread for a real failure.

**The chip is read as a plain blocking call.** `utils/ChipReader` does the
work, and `NfcPassportReader` runs it with `runInterruptible` on
`Dispatchers.IO`, so cancelling a read interrupts it and reports nothing.

**Tag hand-off is per manager.** `NfcTagBroadcaster` belongs to one
`NfcPassportReaderManager` rather than being global, so two managers cannot
take each other's tags.

## Where the time goes

A PACE read takes about 10 s and a BAC read about 12.5 s on a mid-range phone
(Galaxy A32). Roughly, for PACE:

```
settle 1.5s | probe 0.2s | stabilise 1.0s | PACE 1.3s | files 3.9s | verify 1.0s
```

DG2, a ~19 KB face image, is about 3.2 s of that, at ~6 KB/s. BAC documents
are read in 223-byte blocks, which is noticeably slower for the same image.
These are single measurements on two documents, and run-to-run variance is
about ±0.5 s, so measure several reads before believing a difference. Lines
tagged `NFC_PERF` time each stage.

## NFC and timing

**All timing lives in `NfcReadTimingConfig`.** Every delay in the library
reads from it, so a preset such as `CONSERVATIVE` changes the whole read, not
part of it. A new delay anywhere needs a field there.

**`cardStabilizationMs` (1 s) is load-bearing.** Without it PACE fails at key
derivation.

**`isoDepTimeoutMs` (15 s) is load-bearing too.** A single command can stall
for over ten seconds and still succeed. Some chips impose a delay of about 10 s
on the first correct BAC after a refused one, very likely an anti-brute-force
measure, and it survives the passport being put away. After a mistyped MRZ,
the corrected read on such a chip takes about 11 s longer. A 10 s timeout,
which looks reasonable, would turn that into a failure that looks like a flaky
passport.

**Extended-length APDUs need the chip's support, not just the phone's.** The
phone reporting `isExtendedLengthApduSupported` says nothing about the chip. A
BAC-era chip accepts the connection, completes BAC, then fails mid-transfer on
the first 4 KB read, costing a long timeout. So the probe in step 3 of the
read asks the chip: one that answers with EF.CardAccess supports PACE and, in
practice, extended APDUs, and one that does not is read in short blocks.

**The probe tells a chip answer from a link failure.** A real status word
means the chip replied, so its answer can be trusted. `SW_NONE` means the
command never completed, and that is rethrown rather than read as "no PACE".
Were the two treated alike, a link glitch during the probe would put a PACE
document on the slow BAC path, and the read would then fail as if the chip
were at fault. Every branch is logged under `PACE_PROBE` (`supported`,
`absent`, `link-failed`, `absent-unexpected-sw`, `unusable`), so an issuer
that behaves differently shows up as a branch nobody expected.

**Retrying after a lost tag is manual.** The read does not restart by itself
when the passport comes back: a quick return usually means the hand is still
moving, and a read restarted then is usually lost again. The sample app leaves
ReaderMode on and offers Retry instead.

## Access control

**A wrong MRZ is inferred from status words, and BAC and PACE read them
differently.** No specification says *why* authentication failed. PACE follows
BSI TR-03110-3 B.14.2: `0x6300` and `0x63CX` with tries left mean a wrong
credential; `0x63C0`/`0x63C1` and `0x6982`–`0x6985` mean a blocked, suspended
or deactivated password. BAC has no such table, and a BAC key has no password
states, so BAC's MUTUAL AUTHENTICATE refused with any status word is
`WrongMrz`: that step checks nothing but the MRZ-derived key. A refused GET
CHALLENGE, before any key is used, is not. The same `0x6982` on a file read
means secure messaging is not in place and is `NfcIo`, so authentication
failures are wrapped (`AccessControlException`) to keep the two apart.

**JMRTD's refusal of a malformed MRZ field names the value**, for instance the
offending character. Every place that builds a key from user input discards
the refusal and reports `WrongMrz` with no cause, so nothing of the MRZ
reaches a message or log.

**DG3 and DG4 are never requested without Terminal Authentication.** They need
a terminal certificate from the issuing state. A chip refuses them, and some
chips end secure messaging when they refuse, so every later read fails.
Passive Authentication records their hashes as not checked without touching
the chip.

## Biometrics

**DG2 and DG3 come in two encodings.** ICAO is moving from ISO/IEC 19794 to
ISO/IEC 39794: readers must handle 39794 from 2026, issuers must use it from
2030, and a chip carries one or the other. JMRTD parses both, but its
convenience getters (`DG2File.getFaceInfos()`, `DG3File.getFingerInfos()`)
return 19794 records only, so a 39794 face would silently vanish while every
hash verified. The library reads the sub-records instead, reports which
encoding the chip used in `Passport.faceEncoding`, and logs every outcome
under `BIOMETRIC_FORMAT`.

**JMRTD 0.8.8 returns a null MIME type for 39794 fingerprints.** The library
fills it in from the format code. `jmrtdStillOmitsFingerprintMimeTypes` is a
canary test that fails when JMRTD fixes this.

**Face pose and quality.** For 39794, pose and quality follow ISO's published
ASN.1 schemas. For 19794 they are returned raw (`rawPose`, `rawQuality`): the
2005 standard's scaling is not freely published, and a guessed conversion is
worse than none.

## Logging and personal data

**Personal data must not reach release logs.** `NfcLog.allowPersonalData` is
set from the *host app's* debuggable flag; the library cannot use its own
`BuildConfig.DEBUG`, because a published AAR is always a release build. MRZ
fields, names and document numbers are logged only through
`NfcLog.personal { ... }`. `CheckResult` reasons never carry credentials,
because they reach the report, which apps display and send.

**Never `printStackTrace()`.** It writes to `System.err`, which Android sends
to logcat in release builds too, past both gates. Broad catches are deliberate
in the chip path, where JMRTD and BouncyCastle can throw anything on malformed
data. Most carry a `// Deliberately broad:` comment giving the reason, and
detekt flags any new broad catch that is not suppressed on purpose.

## Public API

**No third-party type may appear on the public API.** That is what lets JMRTD,
SCUBA and BouncyCastle be `implementation` dependencies, so no app compiles
against an LGPL library through this one. Convert in `mapping/` instead.

The one exception is kotlinx-coroutines: `NfcPassportReaderManager.readState`
is a `StateFlow`, and `readPassport` suspends, so coroutines is part of how
the API is used. It is an `api` dependency, so apps get it on their compile
classpath, and it is Apache-2.0. `checks.sh` allows it and nothing else.

**The public API is recorded and checked.** binary-compatibility-validator
keeps it in `passport-reader/api/passport-reader.api`. `apiCheck` fails when
it changes and `apiDump` accepts a change, so every API change shows up as a
diff in review. The sample app is an application and is not recorded. The
sample app failing to compile catches a leaked type only in the classes it
happens to use, and `apiDump` would accept one, so `checks.sh` also fails if
the recorded API mentions a third-party type.

**Kotlin makes everything public by default**, so a new class outside `data/`,
`model/`, `reader/`, `timing/` and `trust/` should be `internal` unless it is
meant to be API. `apiCheck` will say so if it is not.

**User-facing text is in resources; diagnostics are not.** Error titles and
messages are string resources an app can override by name or ignore, so the
library decides *what happened* and leaves *what to say about it* to the app.
`technicalDetails` and `CheckResult` reasons stay plain English: ISO 7816
status words and protocol steps are diagnostics, easier to search for
untranslated.

## Static analysis

**detekt runs against a baseline.** Both modules use detekt's default rules,
with `FunctionNaming` and `MagicNumber` relaxed inside `@Composable`
functions, where Compose's naming and layout dimensions make them noise
(`config/detekt/detekt.yml`). Existing issues are recorded in each module's
`detekt-baseline.xml`, so detekt fails only on new code. Clearing everything
first would mean reformatting the chip code wholesale, which would make its
diffs unreviewable.

**The baseline is debt, not approval.** An entry stays silent until its line
changes. The ones most worth paying down are the broad and swallowed catches
in the library: an empty `catch` around face extraction is exactly how a
whole encoding of face images can vanish while the read reports success.
