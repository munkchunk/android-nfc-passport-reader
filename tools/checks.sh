#!/usr/bin/env bash
#
# Everything that can be decided without a passport and a phone.
#
# This is the gate an agent or a contributor must pass before a human is
# asked to look at a change. It is deliberately deterministic: no model, no
# judgement, and no network once Robolectric has fetched its android-all jars
# on the first run. What it cannot check is the part that matters most --
# a change to the read path is only really tested by reading both documents,
# and nothing in here substitutes for that.
#
# Each check reports PASS, FAIL or SKIP. SKIP means the tooling is not
# configured yet and says so; it is not a pass, and it is not silent.
#
# Usage:  tools/checks.sh [--staged] [--fast]
#
#   --staged check exactly what `git commit` would record: the index is
#            exported to a fresh directory and built there with the Gradle
#            build cache off. This is the mode that gates a commit.
#   --fast   skip the two assemble steps (they dominate the runtime).
#            Do not use --fast for the run that gates a commit: the sample
#            app failing to compile is how an API leak is detected.
#
# Without --staged the checks run on the working tree, incrementally and with
# the build cache - quick, but not proof. Kotlin's incremental build can keep
# class files that no longer match the source (removing a wildcard import can
# change which class a name resolves to without recompiling its users), and
# the build cache then hands those outputs to a clean checkout as well, so a
# commit can pass this way while failing to compile.
#
set -uo pipefail

cd "$(dirname "$0")/.."

STAGED=0
FAST=0
for arg in "$@"; do
    case "$arg" in
        --staged) STAGED=1 ;;
        --fast)   FAST=1 ;;
        *) echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

if (( STAGED )); then
    # Re-run the *staged* copy of this script inside an export of the index.
    # Machine-local files git does not carry are copied in; nothing else is.
    repo=$(pwd)
    tree=$(mktemp -d "${TMPDIR:-/tmp}/checks-staged.XXXXXX")
    trap 'rm -rf "$tree"' EXIT
    git checkout-index --all --prefix="$tree/"
    [[ -f local.properties ]] && cp local.properties "$tree/local.properties"
    printf 'Checking the index, exported to %s\n' "$tree"
    rest=(); (( FAST )) && rest+=(--fast)
    # GIT_DIR lets the tracked-file checks read this repository's index.
    ( cd "$tree" && CHECKS_NO_BUILD_CACHE=1 GIT_DIR="$repo/.git" GIT_WORK_TREE="$tree" \
          bash tools/checks.sh "${rest[@]}" )
    exit $?
fi

GRADLE_FLAGS=(--quiet)
[[ -n "${CHECKS_NO_BUILD_CACHE:-}" ]] && GRADLE_FLAGS+=(--no-build-cache)
gradle() { ./gradlew "${GRADLE_FLAGS[@]}" "$@"; }

FAILED=()
SKIPPED=()

if [[ -t 1 ]]; then
    R=$'\e[31m'; G=$'\e[32m'; Y=$'\e[33m'; B=$'\e[1m'; N=$'\e[0m'
else
    R=""; G=""; Y=""; B=""; N=""
fi

pass() { printf '  %sPASS%s  %s\n' "$G" "$N" "$1"; }
fail() { printf '  %sFAIL%s  %s\n' "$R" "$N" "$1"; FAILED+=("$1"); }
skip() { printf '  %sSKIP%s  %s -- %s\n' "$Y" "$N" "$1" "$2"; SKIPPED+=("$1"); }
head_() { printf '\n%s%s%s\n' "$B" "$1" "$N"; }

# Run a command, show its output only if it fails.
run() {
    local label="$1"; shift
    local out
    if out=$("$@" 2>&1); then
        pass "$label"
    else
        fail "$label"
        printf '%s\n' "$out" | tail -40 | sed 's/^/        /'
    fi
}

# ---------------------------------------------------------------------------
head_ "Build"
# ---------------------------------------------------------------------------
# The sample app is the canary for the public-API invariant. It consumes the
# library the way a third party does, so a JMRTD or SCUBA type escaping onto a
# public declaration stops it compiling. That is why it is built here and why
# --fast is not good enough to gate a commit.
if (( FAST )); then
    skip "assemble" "--fast given; the sample app is the public-API canary"
    skip "network permissions" "--fast given; needs the merged manifest"
else
    run ":passport-reader:assembleDebug" gradle :passport-reader:assembleDebug
    run ":sample-app:assembleDebug"      gradle :sample-app:assembleDebug

    # The app needs no network, but dependencies can request it: ML Kit's
    # telemetry library adds INTERNET and ACCESS_NETWORK_STATE, which the
    # app's manifest removes. Check the merged result, so a new dependency
    # cannot quietly bring them back.
    MERGED="sample-app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml"
    NETWORK='android\.permission\.(INTERNET|ACCESS_NETWORK_STATE|ACCESS_WIFI_STATE)"'
    if [[ ! -f "$MERGED" ]]; then
        fail "sample app requests no network access"
        printf '        merged manifest not found at %s\n' "$MERGED"
    elif grep -qE "$NETWORK" "$MERGED"; then
        fail "sample app requests no network access"
        grep -nE "$NETWORK" "$MERGED" | sed 's/^/        /'
    else
        pass "sample app requests no network access"
    fi
fi

# ---------------------------------------------------------------------------
head_ "Tests"
# ---------------------------------------------------------------------------
# These cover MRZ parsing, dates, check digits and the parcelling of the data
# model. They do not cover the chip read path at all. Green here means those
# are still right, nothing more.
run ":sample-app:testDebugUnitTest"      gradle :sample-app:testDebugUnitTest
run ":passport-reader:testDebugUnitTest" gradle :passport-reader:testDebugUnitTest

# ---------------------------------------------------------------------------
head_ "Static analysis"
# ---------------------------------------------------------------------------
run "lintDebug" gradle lintDebug

if grep -rqs "io.gitlab.arturbosch.detekt" --include='*.kts' --include='*.toml' . ; then
    run "detekt" gradle detekt
else
    skip "detekt" "not configured; see docs/design-notes.md"
fi

if grep -rqs "org.jlleitschuh.gradle.ktlint\|com.ncorti.ktfmt" --include='*.kts' --include='*.toml' . ; then
    run "ktlint" gradle ktlintCheck
else
    skip "ktlint" "not configured"
fi

# ---------------------------------------------------------------------------
head_ "Public API surface"
# ---------------------------------------------------------------------------
# The library's hardest invariant is that no third-party type appears on a
# public declaration. JMRTD, SCUBA and cert-cvc are `implementation` scope
# precisely because none does; one public signature mentioning them forces
# them to `api` and puts an LGPL library on every consumer's compile
# classpath. A checked-in .api dump turns that from "the sample app broke" into
# a reviewable line in a diff.
if grep -rqs "binary-compatibility-validator\|apiValidation" --include='*.kts' --include='*.toml' . ; then
    run "apiCheck" gradle apiCheck
else
    skip "apiCheck" "binary-compatibility-validator not applied; the sample app is the only guard"
fi

# apiCheck only says the API changed; `apiDump` makes it pass again. This says
# whether the recorded API is allowed at all. Before the API was recorded, 33
# public declarations carried third-party types, through classes the
# sample app never touched - which is why the sample app alone was not enough.
API_FILE="passport-reader/api/passport-reader.api"
THIRD_PARTY='org/jmrtd|net/sf/scuba|org/ejbca|org/bouncycastle|io/reactivex|org/androidannotations'
if [[ -f "$API_FILE" ]]; then
    if grep -qE "$THIRD_PARTY" "$API_FILE"; then
        fail "no third-party types in the public API"
        grep -nE "$THIRD_PARTY" "$API_FILE" | head -20 | sed 's/^/        /'
    else
        pass "no third-party types in the public API"
    fi
else
    skip "api-leaks" "$API_FILE absent; run ./gradlew apiDump"
fi

# ---------------------------------------------------------------------------
head_ "Licensing invariants"
# ---------------------------------------------------------------------------
# JMRTD is linked, never copied in: the repository holds none of its source
# (NOTICE.md, "LGPL dependencies"). An org.jmrtd package here would bring
# LGPL terms into this project's own code.
jmrtd_dirs=$(find . \( -name build -o -name .git -o -name .gradle \) -prune -o -type d -name jmrtd -print 2>/dev/null)
if [[ -n "$jmrtd_dirs" ]]; then
    fail "no vendored org.jmrtd package"
    printf '%s\n' "$jmrtd_dirs" | sed 's/^/        /'
else
    pass "no vendored org.jmrtd package"
fi

# An `api` dependency in the library puts it on every consumer's compile
# classpath. The one allowed is kotlinx-coroutines, whose StateFlow is the
# manager's readState (docs/design-notes.md, "Public API").
API_ALLOWED='libs\.kotlinx\.coroutines\.android'
if grep -E '^\s*api\(' passport-reader/build.gradle.kts | grep -Evq "api\($API_ALLOWED\)"; then
    fail "library declares no api() dependencies beyond coroutines"
    grep -En '^\s*api\(' passport-reader/build.gradle.kts | grep -Ev "api\($API_ALLOWED\)" | sed 's/^/        /'
else
    pass "library declares no api() dependencies beyond coroutines"
fi

# ---------------------------------------------------------------------------
head_ "Secrets"
# ---------------------------------------------------------------------------
# Signing material and Firebase config are gitignored, but gitignore does not
# help if a file was added before the rule was. Check what git is tracking.
if git rev-parse --git-dir >/dev/null 2>&1; then
    tracked=$(git ls-files | grep -Ei '\.(jks|keystore|p12)$|google-services\.json|signature\.properties|secure_properties/' || true)
    if [[ -n "$tracked" ]]; then
        fail "no signing or Firebase material tracked"
        printf '%s\n' "$tracked" | sed 's/^/        /'
    else
        pass "no signing or Firebase material tracked"
    fi
else
    skip "tracked-secrets" "not a git repository"
fi

if command -v gitleaks >/dev/null 2>&1; then
    run "gitleaks" gitleaks detect --no-banner --redact
else
    skip "gitleaks" "not installed"
fi

# ---------------------------------------------------------------------------
head_ "Summary"
# ---------------------------------------------------------------------------
(( ${#SKIPPED[@]} )) && printf '  %d skipped: %s\n' "${#SKIPPED[@]}" "${SKIPPED[*]}"
if (( ${#FAILED[@]} )); then
    printf '  %s%d failed: %s%s\n\n' "$R" "${#FAILED[@]}" "${FAILED[*]}" "$N"
    exit 1
fi
printf '  %sall checks passed%s\n' "$G" "$N"
printf '  Not covered here: the chip read path. If this change touches it,\n'
printf '  read both documents before committing.\n\n'
exit 0
