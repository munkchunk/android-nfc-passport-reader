# The sample app

A small Compose app showing the library in use: camera MRZ scan or manual
entry, the read, and a result screen. It is also how most people will judge
the library, so its screens are designed rather than left as a demo.

For the library itself, see [architecture.md](architecture.md).

## Structure

One `Activity`, a `ViewModel`, and Compose screens, with no DI or navigation
library, so the wiring an app has to do stays visible.

| Path | Holds |
|---|---|
| `MainActivity`, `PassportReaderViewModel` | Screen switching, the manager's lifecycle, NFC on/off |
| `mrz/` | Camera MRZ scanning (`MrzImageAnalyzer`, ML Kit text recognition), parsing and check digits (`MrzParser`), the last successful MRZ (`MrzStore`) |
| `ui/` | Entry, scan, reading and result screens, the passport placement drawing, verdict wording |
| `ui/theme/` | The palette, held to WCAG 2.2 AA by `ContrastTest` (see [Accessibility](#accessibility)) |
| `src/debug/demo/` | `DemoResultActivity`: the result screen on the ICAO specimen, debug builds only |

**The app has no network access.** It requests the camera and NFC only. ML
Kit's text recognition brings in Google's telemetry library, which asks for
`INTERNET` and `ACCESS_NETWORK_STATE`; the manifest removes both, and
`tools/checks.sh` fails if the merged manifest gains either again. The text
model is bundled with the app, so scanning works offline.

## The result screen

The screen shows what the chip holds and what could be verified about it.
This project is for people who want to see what a passport chip actually
contains, so it shows everything the library returns rather than a summary.

### Layout

The DG2 photograph sits beside the identity fields, arranged like the printed
data page: surname, given names, document number, date of birth, sex and
nationality, date of expiry. Beneath them are both MRZ lines exactly as held
in DG1, monospaced and horizontally scrollable: wrapping an MRZ destroys the
character positions that give it meaning.

Then come verification, the biometric details of the face (its encoding and
what its record says), the document signer certificate and the certificate
chain, followed by one panel per optional data group, in data-group order:
Portrait (DG5), Signature (DG7), Personal details (DG11), Document details
(DG12), Optional details (DG13) and Persons to notify (DG16). They come last
so that the panels every read produces sit in the same place on every
document, and so that verification, which covers these groups' hashes too,
is not pushed down by data most documents lack.

- The group number sits beside each panel's title, as the verification rows
  name their protocols, so it is clear which data group each value came
  from.
- A group the chip does not carry gets no panel, and nor does one with nothing
  to show. Each group's images stay in its own panel: proof of citizenship
  with DG11, front and rear of the document with DG12.
- The DG5 portrait always has its own panel. When DG2 has no decodable face,
  the holder panel falls back to it too, so it appears twice: once standing in
  for the face on the data page, and once as what the chip holds.
- DG11 and DG12 hold names and dates in MRZ style, but without the MRZ's
  limits. `ChipFields` formats them to match the rest of the screen, and
  **shows any value that does not fit that form exactly as stored** rather
  than guessing: issuers do not all follow ICAO 9303 here, and a guess would
  be wrong without anyone noticing.
- A DG11 proof-of-citizenship image that will not decode is shown as present,
  with its size, since leaving it out would look as if the chip had no such
  image.
- DG13's contents are defined by each issuer, so its panel shows only its size
  and its first 256 bytes in hex: any reading of them would be a guess.
- DG16 shows one block per person; an entry with none of its four fields is
  left out.

**Every field value uses one typeface.** Monospace is kept for raw technical
strings only: the MRZ lines, certificate rows and the DG tags. Mixing
monospaced numbers and dates with proportional names reads as inconsistent
rather than as a distinction.

### Verdict marks

`CheckVerdict` has five values, and each gets its own mark. Shape carries the
meaning and colour reinforces it: colour alone would make a red cross and a
green tick identical to a colour-blind reader.

| Verdict | Mark | Colour |
|---|---|---|
| `SUCCEEDED` | tick | pass green |
| `FAILED` | cross | red |
| `NOT_PRESENT` | dash | neutral |
| `NOT_CHECKED` | struck circle | neutral |
| `UNKNOWN` | query | amber |

`NOT_PRESENT` and `NOT_CHECKED` are deliberately neutral. A chip that does not
offer Active Authentication, or a BAC check skipped because PACE was used, has
done nothing wrong, and colouring either as a warning would say otherwise.
Five verdicts are kept rather than collapsed to pass, fail and other: "the
chip does not have this", "this was skipped deliberately" and "this was
indeterminate" are different answers, and only the last is worth
investigating.

A data group whose hash was not compared, such as DG3 without Terminal
Authentication, is listed as "Not checked" rather than shown as matching.

### Summary tally

A single line beside the verification heading, such as "5 passed · 1 not
available", rather than a full-width verdict banner. It answers the question
at a glance without a wall of green inviting the reader to trust it unread,
which for a verification tool defeats the purpose. Its colour follows one
rule:

> **Red if anything failed; otherwise amber if anything is unknown; otherwise
> green.** Not present and not checked never colour it.

### Expiry

An expired passport still authenticates, and its chip passes every check a
valid one does. Expiry is a fact about the document's validity for travel,
not a failure of the chip. So an expired date is marked **amber, beside the
field**, with an "Expired" badge, and never appears as a failed check.

Reading an MRZ `YYMMDD` date needs a sliding century window: read naively, a
passport that expired in 2012 would expire in 2112.

### Palette

Deep green carries the app. A brighter green is spent only on success, a
passed check or a finished reading stage: if the whole app were bright green,
a green tick would stop meaning anything.
Neutrals are biased slightly green rather than pure grey, so they sit with the
accent instead of beside it. All colours are defined once, in `ui/theme/`, in
both themes.

| Token | Light | Dark | Use |
|---|---|---|---|
| brand | `#1B4332` | `#2D6A4F` | primary actions; the dark-mode header |
| brand text | `#1B4332` | `#8DBFA5` | text buttons, focused fields, the active reading stage |
| header | `#FFFFFF` | `#2D6A4F` | app bar and status bar |
| pass | `#13773A` | `#4ADE80` | a passed check, and a finished reading stage |
| fail | `#B3261E` | `#FF8A80` | a failed check |
| unknown | `#8A5A00` | `#E3B341` | indeterminate, and expiry |
| absent | `#5A695F` | `#8C9A91` | not present, not checked |
| ground | `#E6ECE8` | `#101512` | page background |
| panel | `#FFFFFF` | `#1F2A23` | a data-group panel, and the warning card |
| inset | `#F3F6F4` | `#182019` | boxes inside a panel: MRZ, images |
| border | `#758479` | `#6B7C71` | text fields, the upcoming-stage ring |
| line | `#C9D4CD` | `#2C3830` | dividers and image frames |

Both themes layer the same way: a panel stands out from the page, and a box
inside a panel steps back from it. In light mode the header is white, with a
hairline rule under it and the title in brand green; in dark mode it is solid
brand green. The status bar follows the header.

The dark brand green works as a fill but not as text, so text in the brand
colour uses the separate brand-text token. It is kept greyer than the pass
green, so that words in it are not read as a passed check.

## MRZ scanning

**Three agreeing readings are required.** Three check digits only narrow a
false match to about 1 in 1000, and at tens of frames a second that becomes
likely within a minute: a scanner that accepted a single valid reading would
sooner or later complete on text that is not an MRZ at all. One second of
frames can also hold two different check-digit-valid readings of the same
MRZ. A frame that fails to parse is not disagreement; only a different valid
reading resets the count.

**A check digit does not catch a clipped document number.** `<` weighs 0 in
the ICAO check digit, so losing a prefix whose weighted sum is a multiple of
ten leaves the check digit unchanged. The ICAO specimen has this property:
`L898902C<` and `<<<8902C<` both check to 3. Over random nine-character
numbers of letters and digits, 41.5% admit such a collision from a clip of one
to five characters. `MrzParser` therefore also checks the field's shape
(left-justified, filler only as a trailing pad).

**Agreement measures stability, not correctness.** While the phone stays in
the same wrong position, a clipped number reads identically frame after
frame.

**The frozen capture is the frame that was analysed.** Text recognition takes
around 100 ms a frame, so the live preview runs ahead of the evidence, and
freezing it would show a position the reading never came from.

**The scan band is mapped through the preview's scaling.** `PreviewView` fills
and centres, cropping the frame, so the band on screen is not the same
fraction of the camera frame. The crop and the on-screen guide share
constants (`MrzImageAnalyzer.BAND_*`), and the mapping between them is what
keeps them aligned.

The guide is a plain band over the MRZ, not a face or passport outline: the
MRZ is the only part read, and a face-shaped guide would have people framing
the photograph.

## Waiting for a passport

The library gives up if no passport arrives within `awaitTagTimeoutMs`, 20
seconds by default. The sample app sets no limit (`Long.MAX_VALUE`, which the
library treats as "until cancelled"): that wait comes before any chip is
found, so a limit is the app's choice, and WCAG 2.2.1 asks that a user not be
hurried.

Nothing in the library ends that wait, so the app cancels it on Cancel and
Back, when the Activity pauses (the screen locking included), and when NFC is
switched off. The quick-settings shade does not pause the app, so a receiver
for `NfcAdapter.ACTION_ADAPTER_STATE_CHANGED` handles that case. The receiver
must be exported because the broadcast comes from the NFC service rather than
the system; it is a protected broadcast, and the receiver re-asks the adapter
rather than trusting its extras. The screen is not kept awake, so the longest
wait is the user's own screen timeout.

A timeout also covers a silent platform failure: Android can fail to start
listening for the chip without saying so, leaving the passport on the phone
unheard. With no timeout, the app covers it instead: after 20 seconds the
screen says nothing has been found yet and what to try, and ReaderMode is
re-asserted once more.

While the reading screen is showing, the phone's own contactless payments are
off: Android's documentation for ReaderMode says it disables card emulation.

## Accessibility

The sample app meets **WCAG 2.2 AA** in both themes, visually. Mobile apps
are held to WCAG through WCAG2ICT, and in the EU through EN 301 549; AA is the
level both expect. The library has no UI; the only user-facing output it owns
is its error text, which the app shows.

**Screen readers are out of scope.** Nothing is done for what TalkBack says or
where its focus goes, so the app does not claim the criteria that turn on it:
4.1.2 and 4.1.3, 2.5.3, and the spoken side of 1.1.1, 1.3.1 and 3.3.1. Verdict
marks and chip images do carry spoken descriptions.

**Text size and landscape: what bites, not full reflow.** The screens work at
system font scales up to 2.0 and in landscape. The expired badge drops under
the date when there is no room; the reading-screen drawing is capped in
landscape so the instruction stays visible; the drawing's cover lettering is
sized with the drawing, not the font setting; certificate rows stack from
1.5×. At 2.0 the holder panel's labels break mid-phrase beside the photo:
nothing is lost, and restacking that panel would be a redesign. Tap targets
are 48dp or more.

The criteria that need a decision in this app:

| Criterion | What it means here |
|---|---|
| 1.3.4 Orientation | Rotation is not locked, and every screen works in landscape. |
| 1.3.5 Identify input purpose | The document may not be the user's own, so no field is marked as the user's date of birth. |
| 1.4.1 Use of colour | A verdict is told by shape and words, never colour alone. Reading stages are too: done is a filled dot, upcoming a hollow ring. |
| 1.4.3 Contrast (minimum) | Text is at least 4.5:1 against what it sits on. |
| 1.4.11 Non-text contrast | Component boundaries a user needs, such as text-field borders, and state indicators are at least 3:1. |
| 1.4.12 Text spacing | Android gives users no way to override an app's text spacing, so under WCAG2ICT there is nothing to meet. |
| 2.2.1 Timing adjustable | No limit on the wait for a passport; see [above](#waiting-for-a-passport). |
| 2.5.8 Target size (minimum) | Anything tappable is at least 24dp square, and in practice 48dp. |
| 3.3.3 Error suggestion | A rejected field says how to put it right, not only that it is wrong. |
| 3.3.7 Redundant entry | The form keeps what was typed or scanned across a cancelled or failed read. |
| 3.3.8 Accessible authentication (minimum) | Typing the MRZ is a transcription test; the camera scan is the alternative that avoids it. |

### Contrast, and how it is enforced

- Text: **4.5:1** against its background. Every verdict colour clears it on
  its own container, in both themes.
- Component boundaries and state indicators: **3:1**.
- Disabled controls, decorative borders and dividers are exempt, as WCAG
  allows.
- The scanner draws white text over the camera feed under a black scrim at
  `SCAN_SCRIM_ALPHA` (0.55). The feed can be anything, so it is measured
  against the worst case, white paper under the scrim.

`ContrastTest`, a JVM unit test, reads the real colour schemes from
`Theme.kt` and checks every colour pair the app puts on screen, in both
themes, each named for the element it stands for. **A new pairing on screen
needs a new row there.** Any pair that falls short must be listed in its
`KNOWN_FAILURES`, which the test requires to match the failing pairs exactly,
so it fails both when a new pair falls short and when a listed one is fixed
but left on the list. The list is empty.

AAA is not the target: a 7:1 text minimum would rule out most of the verdict
palette's containers, and neither WCAG2ICT nor EN 301 549 asks for it.
Material's own contrast guarantees cover its generated palettes, not a
hand-set scheme like this one. On-device tools such as Accessibility Scanner
complement the unit test, but would let a palette change pass the build.

Text size, landscape and target sizes cannot be checked by a unit test. They
are checked on the device, and new screens are expected to follow the same
rules.
