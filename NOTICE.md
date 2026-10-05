# Third-party notices

This file records the third-party code this project depends on or
redistributes, the licence each comes under, and what that means for anyone
shipping an app built on the library.

## This project's licence

**Apache License 2.0.** See [LICENSE](LICENSE), which is the licence text
verbatim with the appendix copyright line filled in. Apache-2.0 is a licence
that individuals and enterprises can both use without negotiation, and
Apache-2.0 adds over MIT an express patent grant and a patent-retaliation
clause — worth having where JPEG 2000 decoding and ePassport cryptography are
involved, and the clause corporate reviewers look for.

## LGPL dependencies

The chip protocol layer comes from three LGPL libraries:

| Dependency | Licence (as declared in its POM) | Scope |
|---|---|---|
| `org.jmrtd:jmrtd` | GNU Lesser General Public License | implementation |
| `net.sf.scuba:scuba-sc-android` | GNU Lesser General Public License | implementation |
| `org.ejbca.cvc:cert-cvc` | LGPL 2.1 | implementation |

**This repository only links against them.** All three are `implementation`
dependencies; the module declares no `api` dependencies at all. None of their
source is modified or included here, and no third-party type appears on this
library's public surface, so a consumer compiles against none of them. LGPL
permits linking from differently-licensed code, so this project's own code is
Apache-2.0 outright.

**Where the types stop.** `mapping/DocumentMappers.kt` and
`mapping/VerificationMappers.kt` are the boundary. Everything JMRTD and SCUBA
expose is converted there into types in `data/`, `model/`, `trust/`,
`crypto/` and `verification/`. Putting a third-party type back on a public
declaration would force `api` scope and undo this; add a mapping instead.
`tools/checks.sh` fails if the recorded public API
(`passport-reader/api/passport-reader.api`) mentions a third-party type, and
fails if an `org.jmrtd` package appears in the source tree.

**What they mean downstream.** The obligations attach to the combined work
anyone distributes on top of this library, not to this repository:

- The LGPL notices must be preserved and the licence made available.
- Whoever receives the combined work must be able to relink it against a
  modified JMRTD. On Android, with everything merged into DEX, this is the
  genuinely awkward clause -- more so than publishing source, which is
  satisfied by these libraries already being public.

The README says this in the place a consumer will actually read it.

## JPEG 2000 decoding

JPEG 2000 images (faces, fingerprints, DG5/DG7 portraits and signatures, DG12
document scans) are decoded by `io.github.michaldvorak-gemalto:jp2-android`, a
JNI wrapper around OpenJPEG.
Both are **BSD 2-Clause**; the POM declares it and the project lives at
<https://github.com/ThalesGroup/JP2ForAndroid> (Gemalto became part of Thales
in 2019).

Pinned to **1.0.5** rather than the current 1.1.0, which requires
`compileSdk 37`, above this project's. Revisit when that moves.

**Why not JJ2000.** The usual pure-Java alternative is JJ2000, the reference
implementation written in 1999-2000 by EPFL, Ericsson and Canon
Research France. JJ2000's terms are not an open-source licence. They are a
non-assertion covenant toward "ISO/IEC and Users of the JPEG 2000 Standard"
which states that **no licence is granted for non JPEG 2000 Standard
conforming products**, together with a warning that use may infringe existing
patents. That is a field-of-use restriction this project does not take on.

**Beware a look-alike.** `io.github.CshtZrgk:jp2-android` is also on Maven
Central. Its POM describes it as an ID card decoding SDK, points at an
unrelated `ReadCardSdk` repository, and declares Apache 2.0 over what is
upstream BSD-2 code. A third party relicensing someone else's library is not
something to depend on.

**Measured.** Neither passport available for testing stores DG2 as JPEG 2000
-- both use `image/jpeg` -- so the JP2 path has not been exercised on a real
document. It was measured instead against generated 240x320 JP2 vectors, kept
in `passport-reader/src/androidTest/assets/jp2` and guarded by
`Jpeg2000DecoderTest`, with JJ2000 for comparison:

| vector | JJ2000 | OpenJPEG |
|---|---|---|
| high bitrate | 332 ms, worst channel error 44 | 16 ms, error **6** |
| low bitrate | 105 ms, worst channel error 44 | 9 ms, error 43 |

OpenJPEG is never worse, materially more accurate on the high-bitrate stream,
and 12-21x faster.

## CSCA certificates

`passport-reader/src/main/assets/csca/` contains exactly the certificates
published in the German BSI CSCA master list:

- `trust-anchors.pem` — 509 self-signed CSCA certificates, used as trust anchors
- `link-certificates.pem` — 79 link certificates, used for chain building only

**Source.** The BSI publishes a CSCA master list as a CMS SignedData blob whose
encapsulated content is an ICAO `CscaMasterList` (`id-icao-cscaMasterList`,
OID 2.23.136.1.1.2), at:

<https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/ElekAusweise/CSCA/GermanMasterList.zip>

It is published for public download by a national authority so that relying
parties can verify ePassports; that is the purpose these certificates are being
used for here. The certificates are themselves public keys distributed for
verification, not material under a separate licence.

**Refresh.** `tools/update-csca-masterlist.sh` downloads the list, checks the
CMS signature, confirms the content type is the ICAO master list OID, unpacks
every certificate and splits it into the two bundles above. The split follows
what the library expects: `CscaCertificates.addBundles` anchors only the
self-signed roots, and puts them and the links together in the `CertStore`.
Run it with `--dry-run` to inspect the result without touching the assets.

The snapshot currently bundled is `DE_ML_2026-05-28-08-28-45.ml`, signed by:

```
subject = C=DE, O=bund, OU=bsi, serialNumber=0039, CN=CSCA Master List Signer
issuer  = C=DE, O=bund, OU=bsi, CN=csca-germany
```

The signature is checked but the signer is not chained to a local anchor, as
there is no BSI root bundled to chain it to. Verifying the signer out-of-band
before trusting a new snapshot is worthwhile.

BSI republishes roughly monthly. There is no automation in this repository yet;
refreshing is a manual run of the script followed by a device test.

**Known gap.** Six countries have **no trust anchor** in the BSI list, so their
passports will fail passive authentication:

`GH`, `ID`, `IR`, `KZ`, `NG`, `YE`

Closing the gap means sourcing those anchors from a second named source, most
likely the ICAO PKD master list. Every bundled certificate comes from one
named, signed source, and that is worth keeping.

## Dependency licences

Verified against the `<licenses>` block of each resolved POM, or an ancestor's.

| Dependency | Licence |
|---|---|
| `org.jmrtd:jmrtd` | GNU Lesser General Public License |
| `net.sf.scuba:scuba-sc-android` | GNU Lesser General Public License |
| `org.ejbca.cvc:cert-cvc` | LGPL 2.1 |
| `io.github.michaldvorak-gemalto:jp2-android` | BSD 2-Clause |
| `org.bouncycastle:bcprov-jdk18on` | Bouncy Castle Licence (MIT-style) |
| `org.bouncycastle:bcpkix-jdk18on` | Bouncy Castle Licence (MIT-style) |
| `org.bouncycastle:bcutil-jdk18on` | Bouncy Castle Licence (MIT-style) |
| `com.github.mhshams:jnbis` | Apache License 2.0 |
| `com.jakewharton.timber:timber` | Apache License 2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | Apache License 2.0 |
| `commons-codec:commons-codec` | Apache License 2.0 — *declared on the `org.apache:apache` ancestor POM; the jar ships `META-INF/LICENSE.txt`* |
| `org.androidannotations:androidannotations-api` | Apache License 2.0 — *declared on the parent POM, not the artifact's own* |
| AndroidX (including CameraX), Jetpack Compose, Material Components | Apache License 2.0 |
| `com.google.accompanist:accompanist-permissions` (sample app) | Apache License 2.0 |
| `com.google.mlkit:text-recognition` (sample app) | **ML Kit Terms of Service** (<https://developers.google.com/ml-kit/terms>) — not an open-source licence; the library does not use it |

## Standards

The MRZ check digit implementation in `:sample-app` follows ICAO Doc 9303
Part 3. The specimen values used in its unit tests come from that document.
