# What the library reads

An ePassport chip holds a set of files defined by ICAO Doc 9303. This page
lists every one of them, and says for each whether the library reads it, what
it gives back on `Passport`, whether the sample app shows it, and whether a
real passport has ever exercised that path.

The last column matters. Development has had two passports to test with, and
both carry only DG1, DG2 and sometimes DG14. Everything else is tested against
documents generated in the unit tests, or the invented specimen document in the
sample app's debug build. Until someone reads a passport that carries a group,
that group's handling is known from the specification, not from a chip. Reports
from other documents are welcome; the log tags under
[Telling us what your passport did](#telling-us-what-your-passport-did) are
what makes one useful.

## Data groups

**Hash checked** means passive authentication compares the group against the
hash signed into EF.SOd. Every group EF.SOd lists is hash checked, whether or
not the library parses it, except DG3 and DG4 (see below). DG1, DG2, DG14 and
DG15 must verify for the document to pass. An optional group the chip refuses
for access reasons is recorded as unchecked rather than failed.

| File | Contents | Read | On `Passport` | Sample app | Real document? |
|---|---|---|---|---|---|
| DG1 | The MRZ | Always | `personDetails`, `mrz` | Holder panel | Yes |
| DG2 | Face image | Always | `face`, `faceEncoding` | Holder panel | Yes. JPEG, ISO/IEC 19794. JPEG 2000 and ISO/IEC 39794 tested only from generated files. |
| DG3 | Fingerprints | Only after terminal authentication | `fingerprints` | Not shown | No |
| DG4 | Iris images | **Never.** See below | — | — | No |
| DG5 | Displayed portrait | When EF.SOd lists it | `portrait` | Portrait panel; stands in for the face if DG2 has none | No |
| DG6 | Reserved for future use | No | — | — | — |
| DG7 | Displayed signature or usual mark | When EF.SOd lists it | `signature` | Signature panel | No |
| DG8–DG10 | Reserved for future use | No | — | — | — |
| DG11 | Additional personal details | When EF.SOd lists it | `additionalPersonDetails` | Personal details panel | No |
| DG12 | Additional document details | When EF.SOd lists it | `additionalDocumentDetails` | Document details panel | No |
| DG13 | Optional details, defined by each issuer | When EF.SOd lists it | `optionalDetails`, raw bytes | Size and the first 256 bytes in hex | No |
| DG14 | Chip authentication keys | When EF.SOd lists it | Used, not returned | Verification panel | Yes |
| DG15 | Active authentication public key | When EF.SOd lists it | Used, not returned | Verification panel | No |
| DG16 | Persons to notify | When EF.SOd lists it | `personsToNotify` | Persons to notify panel | No |

### Other files

| File | Purpose | Read |
|---|---|---|
| EF.CardAccess | Says whether the chip offers PACE, and with which parameters | Always, before authenticating. Its absence means BAC. |
| EF.COM | Lists the data groups present | **No.** EF.SOd's list is used instead, because EF.SOd is signed and EF.COM is not. |
| EF.SOd | The signed hashes of every data group, and the document signer certificate | Always. Returned as `sodBytes`. |
| EF.CVCA | Which country verifying CA terminal authentication must chain to | Always tried; most documents have none |
| EF.CardSecurity | The chip's signed security information, including the key PACE-CAM proves | Only after PACE-CAM. Its signature and certificate chain are checked as EF.SOd's are. |

## Notes on particular groups

### DG3 and DG4: not readable without the issuing state's permission

Fingerprints and iris images are protected by Extended Access Control. Reading
them requires terminal authentication with an inspection-system certificate
issued by the passport's own country. This library can perform terminal
authentication if you supply such credentials (`CscaTrustStore.addCvcaKeyStore`),
but no one outside a government border system has them, so in practice DG3
and DG4 are never read.

They are not even requested without terminal authentication. A chip refuses
the request, and some chips end the secure session when they refuse, so every
read after it would fail.

**DG4 is not read even after terminal authentication.** Passive authentication
does check DG4's hash in that case, so its integrity is covered. But nothing
decodes or returns the iris images, and there is no field for them on
`Passport`. Neither path could be tested without a government terminal
certificate, so it is left out rather than shipped untested. DG3 is read after
terminal authentication and its images are returned in `fingerprints`, but that
too has never run against a real chip.

### DG2 and DG3: two biometric encodings

ICAO is moving the biometric groups from ISO/IEC 19794 to ISO/IEC 39794. A chip
uses one or the other. Readers had to support 39794 from 2026, and issuers must
use it from 2030. The library reads both, and `Passport.faceEncoding` reports
which one the chip used. Images are decoded from JPEG, JPEG 2000 (via OpenJPEG),
PNG and WSQ. A fingerprint in a format with no decoder here, such as PGM, is
skipped and the others are kept.

### DG2: what each face record says

Besides the image, each face record can describe it: the holder's eye and hair
colour, landmarks marked on the face, the head's pose, a quality score, and
when it was captured. These are returned in `Passport.faceDetails`, one entry
per face, and the sample app shows the first face's in its Biometric data
panel: landmarks, colours and capture date from either encoding, pose and
quality only from ISO/IEC 39794. Eye and hair colour describe the holder, so treat them as personal data.

The two encodings differ here:

- **ISO/IEC 39794:** pose is yaw, pitch and roll, each −180 to 180, and
  quality is a score from 0 to 100, as ISO's published ASN.1 schemas for
  39794-5 and 39794-1 define them. A quality block can instead record that
  its algorithm failed to assess the image.
- **ISO/IEC 19794-5:2005:** how this edition scales its pose and quality
  fields is not freely published, nor whether zero means "not recorded". The
  library does not guess. It returns the stored values unconverted, as
  `rawPose` and `rawQuality`, for every 19794 face. The sample app does not
  show them: unexplained numbers would only confuse.

The mapping is tested against records built and parsed back with JMRTD. No
ISO/IEC 39794 document has been read yet.

### DG12: document images

DG12's images of the front and rear of the document carry no format label, so
the format is detected from the image's first bytes. JPEG 2000, either as a JP2
file or a bare codestream, goes to OpenJPEG; anything else goes to Android's
decoder. An image that will not decode is left out of the result and logged.

### DG13: shown, not interpreted

ICAO leaves DG13's contents entirely to each issuing state, so there is no
general way to parse it. The library returns the file's bytes exactly as the
chip stores them, and the sample app shows only their size and hex. An app that
knows a particular issuer's format can parse `optionalDetails` itself.

### DG16: persons to notify

JMRTD, which the library uses for the other groups, has no parser for DG16, so
the library parses it itself, following ICAO 9303-10 §4.7.16. Each entry
gives the date the entry was recorded, a name, a telephone number and an
address, each passed through as stored. The only test data is the
specification's own two-entry example.

## Protocols

| Protocol | What it proves | Supported | Real document? |
|---|---|---|---|
| BAC | Access, using a key from the MRZ | Yes, when the chip has no EF.CardAccess | Yes |
| PACE | Access, with a stronger key exchange than BAC | Yes. A document that offers PACE is never read with BAC. | Yes, generic mapping. Integrated mapping has not been seen. |
| PACE-CAM | Access and chip authenticity in one step | Yes. See below. | No. Tested against ICAO's worked example. |
| Chip authentication | The chip holds a private key it cannot reveal, so it is not a clone | Yes, from DG14 | Yes |
| Active authentication | The same, by an older method | Yes, from DG15, RSA and ECDSA | No |
| Terminal authentication | The reader is authorised by the issuing state | Only with credentials you supply | No |
| Passive authentication | The data is what the issuer signed | Yes | Yes |

### PACE-CAM

PACE with Chip Authentication Mapping combines access control with proof that
the chip is genuine (ICAO 9303-11 §4.4.3.5). During PACE the chip sends a
number only it can compute, because computing it needs the chip's private key.
The library checks that number against the chip's public key, which it reads
from EF.CardSecurity. It trusts that key only if EF.CardSecurity's signature
verifies and chains to a trusted CSCA.

The result is the report's chip authentication verdict:

- **Succeeded** when the chip proved its key.
- **Failed** when it did not. It also fails when EF.CardSecurity's signature
  does not verify, or when EF.CardSecurity does not chain to a trusted CSCA
  although the same document's EF.SOd does. Each means the chip could not
  prove a key the issuer signed for it, which is what a clone looks like.
- **Not checked** when something the check needs was missing or unreadable, or
  when neither EF.CardSecurity nor EF.SOd chains to a trusted CSCA. The usual
  reason for the second is a country with no trust anchor, which says nothing
  about the chip.

A chip that also offers chip authentication through DG14 gets that too, because
terminal authentication needs it. A PACE-CAM failure outranks a DG14 success.

EF.CardAccess, which tells a reader which PACE variants the chip offers, is
not signed. A clone could edit it to offer ordinary PACE instead of PACE-CAM,
or no PACE at all so that it is read with BAC. ICAO 9303-11 §4.2 requires a
reader to check EF.CardAccess against DG14, which is signed, and the library
does. Once EF.SOd's signature and certificate chain hold and DG14 matches its
signed hash, every PACE variant DG14 lists must have been offered, and the one
used must be among them. Otherwise the report's PACE check fails. The DG14
compared is the one that was hashed, not a separate read, so a chip cannot
show one DG14 to the check and another to the hash. A chip with no DG14, or one
that lists no PACE variants, cannot be checked this way, and nor can one whose
EF.SOd is not trusted.

A chip that refuses to give up EF.CardSecurity after PACE-CAM is reported as
not checked rather than failed. No real PACE-CAM chip has been read yet, and a
genuine chip's quirk should not be reported as a clone.

No document read so far uses PACE-CAM. The check is tested against the worked
example in ICAO 9303-11 Appendix I, and against simulated chips, but has never
run against a real one.

## Telling us what your passport did

Every branch in the read path that depends on the document is logged under a
named tag, so that a report from an unfamiliar passport can be reduced to a
few lines:

```bash
adb logcat -s DATA_GROUPS PACE_PROBE PACE CHIP_AUTH BIOMETRIC_FORMAT
```

`DATA_GROUPS` says which optional groups were present, read or failed;
`PACE_PROBE` and `PACE` say how the chip was authenticated, and
`CHIP_AUTH` how it proved it is genuine;
`BIOMETRIC_FORMAT` says how the face and fingerprints were encoded and
whether they decoded. A debug build may log personal data from the document,
so check what you are sharing and remove anything personal first.
