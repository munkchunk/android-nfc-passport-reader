# Security policy

This library verifies identity documents, so a flaw in it can matter to
someone relying on its answer. Reports are welcome and taken seriously.

## Reporting a vulnerability

**Please do not open a public issue.** Use GitHub's private vulnerability
reporting instead: the **Security** tab of this repository, then **Report a
vulnerability**. Only the maintainer can see the report.

Include what you found, how to reproduce it, and what an attacker could do with
it. If reproducing it needs a document, describe the document rather than
sending its data: never include a real passport's MRZ, personal details,
images or raw data groups.

You can expect an acknowledgement within two weeks. This is a small project
maintained in spare time, so a fix may take longer; you will be kept informed,
and credited in the fix unless you would rather not be.

## In scope

- **A wrong verdict.** Anything that makes the `VerificationReport` say
  `SUCCEEDED` when it should not: a forged or altered document passing Passive
  Authentication, a cloned chip passing Chip Authentication, PACE-CAM or
  Active Authentication, or EF.CardAccess hiding PACE without the PACE check
  failing.
- **Personal data leaking.** MRZ fields, names, document numbers, images or
  key material reaching release-build logs, a `CheckResult` reason, an
  exception message, or anywhere else outside the `Passport` returned to the
  app.
- **A malformed chip response causing harm** beyond a failed read: a crash
  of the host app, unbounded memory use, or a hang that a cancel does not end.
- **The bundled trust anchors**, if the refresh script could be made to accept
  a master list not signed by the BSI. It checks the list's signature against
  the signer certificate the list carries, not against a BSI root; see
  [docs/architecture.md](docs/architecture.md#trust-anchors).

## Not in scope

- **The six countries with no bundled CSCA anchor** (GH, ID, IR, KZ, NG, YE).
  Their passports fail Passive Authentication by design; see
  [NOTICE.md](NOTICE.md).
- **Checks the chip does not support.** A chip without Chip or Active
  Authentication cannot prove it is genuine; the report shows Active
  Authentication as `NOT_PRESENT` and chip authentication as absent. That is a
  property of the document.
- **Certificates checked at today's date** rather than at signing time. This
  is a known limitation; see [docs/architecture.md](docs/architecture.md).
- **What an app does with the report.** Deciding whether a result is good
  enough is the application's responsibility.
- **Vulnerabilities in JMRTD, SCUBA, BouncyCastle or OpenJPEG** themselves.
  Please report those upstream. If this library's use of them makes a problem
  worse, that is in scope.

## Supported versions

Only the latest release receives fixes.
