#!/usr/bin/env bash
#
# Refresh the bundled CSCA trust material from the German BSI master list.
#
# The BSI publishes a CSCA master list as a CMS SignedData blob whose
# encapsulated content is an ICAO CscaMasterList (id-icao-cscaMasterList,
# 2.23.136.1.1.2):
#
#     CscaMasterList ::= SEQUENCE {
#         version   INTEGER,
#         certSet   SET OF Certificate }
#
# This script downloads it, checks the CMS signature, unpacks every
# certificate and writes two PEM bundles:
#
#     trust-anchors.pem       self-signed CSCA certificates, used as trust anchors
#     link-certificates.pem   link certificates, used for chain building only
#
# That split matches what the library expects: NfcPassportReader anchors
# only self-signed roots, and puts roots and links together in the
# CertStore. See CscaCertificates.addBundles.
#
# Requires: curl, unzip, openssl, sed, awk and GNU dd. No gawk extensions.
#
# Usage:  tools/update-csca-masterlist.sh [--dry-run]
#
set -euo pipefail

MASTERLIST_URL="https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/ElekAusweise/CSCA/GermanMasterList.zip?__blob=publicationFile"
ICAO_MASTERLIST_OID="2.23.136.1.1.2"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
assets_dir="$repo_root/passport-reader/src/main/assets/csca"
roots_out="$assets_dir/trust-anchors.pem"
links_out="$assets_dir/link-certificates.pem"

dry_run=false
[[ "${1:-}" == "--dry-run" ]] && dry_run=true

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

say() { printf '%s\n' "$*" >&2; }

say "==> Downloading BSI master list"
curl -sSL --fail -o "$work/masterlist.zip" "$MASTERLIST_URL"
say "    $(wc -c < "$work/masterlist.zip") bytes"

unzip -qo "$work/masterlist.zip" -d "$work"
ml="$(find "$work" -name '*.ml' -print -quit)"
[[ -n "$ml" ]] || { say "ERROR: no .ml file inside the archive"; exit 1; }
say "    archive member: $(basename "$ml")"

# Confirm this really is an ICAO master list before trusting the content.
content_type="$(openssl cms -inform DER -in "$ml" -noout -cmsout -print 2>/dev/null \
    | awk '/eContentType:/ {print $NF}' | tr -d '()')"
if [[ "$content_type" != "$ICAO_MASTERLIST_OID" ]]; then
    say "ERROR: unexpected eContentType '$content_type' (want $ICAO_MASTERLIST_OID)"
    exit 1
fi

# -noverify checks the CMS signature but does not try to chain the signer
# to a local trust store; we have no BSI anchor to chain it to. The signer
# identity is printed below so it can be eyeballed against NOTICE.md.
say "==> Verifying CMS signature"
openssl cms -inform DER -in "$ml" -verify -noverify \
    -out "$work/content.der" -certsout "$work/signers.pem" 2>&1 | sed 's/^/    /' >&2

say "==> Signed by"
awk '/BEGIN CERT/{n++} {print > ("'"$work"'/sg_" n ".pem")}' "$work/signers.pem"
for f in "$work"/sg_*.pem; do
    [[ -s "$f" ]] || continue
    openssl x509 -in "$f" -noout -subject -issuer 2>/dev/null | sed 's/^/    /' >&2 || true
done

say "==> Unpacking certificates"
openssl asn1parse -inform DER -in "$work/content.der" > "$work/parse.txt"

# Certificates are the depth-2 SEQUENCEs inside the SET OF Certificate.
# asn1parse reports the offset and the content length; the DER element is
# header (hl) + content (l) bytes long.
sed -n 's/^ *\([0-9][0-9]*\):d=2  *hl=\([0-9][0-9]*\)  *l= *\([0-9][0-9]*\)  *cons: SEQUENCE.*/\1 \2 \3/p' \
    "$work/parse.txt" | awk '{ print $1, $2 + $3 }' > "$work/offsets.txt"

total="$(wc -l < "$work/offsets.txt")"
[[ "$total" -gt 0 ]] || { say "ERROR: no certificates found"; exit 1; }
say "    $total certificates in the master list"

: > "$work/roots.pem"
: > "$work/links.pem"
roots=0; links=0; skipped=0

while read -r off len; do
    # skip_bytes/count_bytes give byte granularity at a sane block size;
    # bs=1 would mean a syscall per byte, and tail|head trips pipefail
    # when head closes the pipe early.
    dd if="$work/content.der" of="$work/cert.der" bs=64K \
        iflag=skip_bytes,count_bytes skip="$off" count="$len" status=none
    if ! pem="$(openssl x509 -inform DER -in "$work/cert.der" -outform PEM 2>/dev/null)"; then
        skipped=$((skipped + 1)); continue
    fi
    subject="$(openssl x509 -inform DER -in "$work/cert.der" -noout -subject 2>/dev/null)"
    issuer="$(openssl x509 -inform DER -in "$work/cert.der" -noout -issuer 2>/dev/null)"
    # CscaCertificates.addBundles compares the subject and issuer names,
    # so mirror that rather than verifying the signature.
    if [[ "${subject#subject=}" == "${issuer#issuer=}" ]]; then
        printf '%s\n' "$pem" >> "$work/roots.pem"; roots=$((roots + 1))
    else
        printf '%s\n' "$pem" >> "$work/links.pem"; links=$((links + 1))
    fi
done < "$work/offsets.txt"

say "    roots (self-signed): $roots"
say "    links:               $links"
[[ "$skipped" -gt 0 ]] && say "    unparseable, skipped: $skipped"

if $dry_run; then
    say "==> --dry-run: leaving $roots_out and $links_out untouched"
    cp "$work/roots.pem" "${TMPDIR:-/tmp}/trust-anchors.new.pem"
    cp "$work/links.pem" "${TMPDIR:-/tmp}/link-certificates.new.pem"
    say "    wrote ${TMPDIR:-/tmp}/trust-anchors.new.pem and link-certificates.new.pem for inspection"
    exit 0
fi

mkdir -p "$assets_dir"
cp "$work/roots.pem" "$roots_out"
cp "$work/links.pem" "$links_out"
say "==> Wrote $roots_out ($roots certificates)"
say "==> Wrote $links_out ($links certificates)"
say ""
say "Record the master list date and signer in NOTICE.md, then device-test"
say "both passports before committing."
