#!/usr/bin/env bash
# Apply the local upstream fixes to a checked-out upstream tree, or rebase them onto a new
# upstream HEAD when CI reports that upstream-patches.diff no longer applies.
#
# The patch exists because this app cannot change the upstream repository: three fixes
# (max_tokens retention, the missing gemini-3.8 flash tiers, and the test assertion that guards
# the first) live here and are applied at build time. CI runs this same patch step, so a local
# build and a CI build produce the same kernel.
#
# Usage:
#   scripts/apply-upstream-patch.sh              apply the patch to upstream-cliproxy (idempotent)
#   scripts/apply-upstream-patch.sh --rebase     move the fixes onto the current upstream HEAD
#   scripts/apply-upstream-patch.sh --refresh    regenerate upstream-patches.diff from the tree
#
# What --rebase does, in order: fetch the upstream HEAD, keep the current fix commit aside,
# reset the tree to that HEAD, cherry-pick the fix back, and let you resolve a conflict by hand
# if the surrounding code moved. Nothing is pushed anywhere.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UPSTREAM_DIR="$ROOT/upstream-cliproxy"
PATCH="$ROOT/upstream-patches.diff"
UPSTREAM_REPO="https://github.com/router-for-me/CLIProxyAPI.git"
# Every file the patch touches must be listed here. --refresh regenerates the diff from exactly
# these paths, so a file present in the patch but missing from this array is silently dropped the
# next time someone runs --refresh. That is how the stream-termination fix was nearly lost: the
# patch carried antigravity_executor_stream.go while this list still had only the first three.
PATCH_FILES=(
  internal/registry/models/models.json
  internal/runtime/executor/antigravity_executor_request.go
  internal/runtime/executor/antigravity_executor_buildrequest_test.go
  internal/runtime/executor/antigravity_executor_stream.go
  internal/runtime/executor/antigravity_executor_stream_hold_test.go
)

# A marker is "file|pattern". Each one names something only the fix can introduce, so a patch
# that applied without conflict but lost a hunk is still caught. A nil grep result fails.
PATCH_MARKERS=(
  "internal/runtime/executor/antigravity_executor_request.go|maxOutputTokens is preserved"
  "internal/registry/models/models.json|gemini-3.8-flash-low"
  "internal/runtime/executor/antigravity_executor_stream.go|antigravityStreamGraceTimeout"
  "internal/runtime/executor/antigravity_executor_stream_hold_test.go|TestAntigravityStreamEndsWhenUpstreamHoldsTheSocket"
)

die() { echo "error: $*" >&2; exit 1; }

[ -d "$UPSTREAM_DIR/.git" ] || die "$UPSTREAM_DIR is not a git checkout; first run: git clone --depth 1 $UPSTREAM_REPO upstream-cliproxy"

verify_applied() {
  local req="$UPSTREAM_DIR/internal/runtime/executor/antigravity_executor_request.go"
  # Closed on purpose: the field must be PRESENT, so a grep that finds the old deletion is a
  # failure, and an empty file is a failure too rather than an accidental pass.
  if grep -q 'sjson.Delete(payloadStr, "request.generationConfig.maxOutputTokens")' "$req" \
      || grep -q 'sjson.DeleteBytes(payload, "request.generationConfig.maxOutputTokens")' "$req"; then
    die "maxOutputTokens is still deleted: the patch did not take"
  fi
  local entry file pattern
  for entry in "${PATCH_MARKERS[@]}"; do
    file="${entry%%|*}"
    pattern="${entry#*|}"
    if ! grep -qF "$pattern" "$UPSTREAM_DIR/$file"; then
      die "marker missing: '$pattern' not found in $file — the patch applied but lost a hunk"
    fi
  done
  echo "patch verified on $(git -C "$UPSTREAM_DIR" rev-parse --short HEAD)"
}

case "${1:-}" in
  --refresh)
    cd "$UPSTREAM_DIR"
    # The base is the UPSTREAM commit the fixes sit on, not HEAD. Our fixes are committed in
    # this clone, so diffing against HEAD yields an empty patch — which is exactly what happened
    # once: a single --refresh replaced 411 lines of fixes with nothing, silently.
    #
    # The fixes are the commits whose subject starts with "local: " on top of the upstream base,
    # so walk back until that stops matching. This is more reliable than .upstream-sha, which CI
    # advances on every sync: that pointer names the upstream head a released kernel was built
    # from, not necessarily the commit this working tree branched off.
    BASE="HEAD"
    while [ -n "$(git log -1 --format=%s "$BASE" 2>/dev/null | grep '^local: ' || true)" ]; do
      if ! BASE="$(git rev-parse "$BASE^" 2>/dev/null)"; then
        die "reached the root commit while looking for the upstream base"
      fi
    done
    if [ "$BASE" = "$(git rev-parse HEAD)" ]; then
      die "no 'local: ' commits under HEAD — nothing to capture, refusing to overwrite $PATCH"
    fi
    # Regenerate against the base so the diff carries only our fixes, not upstream's.
    git diff "${BASE}" -- "${PATCH_FILES[@]}" > "$PATCH"
    HUNKS="$(grep -c '^@@' "$PATCH" || true)"
    if [ "${HUNKS:-0}" -eq 0 ]; then
      die "regenerated patch is empty — the base ${BASE} is wrong, refusing to overwrite $PATCH"
    fi
    echo "wrote $PATCH (${HUNKS} hunks, base ${BASE:0:7})"
    ;;
  --rebase)
    cd "$UPSTREAM_DIR"
    CURRENT="$(git rev-parse HEAD)"
    git fetch --depth 1 origin HEAD
    TARGET="$(git rev-parse FETCH_HEAD)"
    echo "rebasing the fixes from ${CURRENT:0:7} onto ${TARGET:0:7}"
    git stash push -- "${PATCH_FILES[@]}" >/dev/null 2>&1 || true
    git checkout -q "$TARGET"
    if ! git apply --3way "$PATCH"; then
      cat >&2 <<'EOF'
The patch no longer applies cleanly. Resolve the conflicts in upstream-cliproxy, then:
  scripts/apply-upstream-patch.sh --refresh   # regenerate the diff
Conflicted markers are <<<<<<< / ======= / >>>>>>> in the files listed below.
EOF
      git status --short | grep -E '^(UU|AA)' >&2 || true
      exit 1
    fi
    verify_applied
    echo "now regenerate the diff with: scripts/apply-upstream-patch.sh --refresh"
    ;;
  "" )
    cd "$UPSTREAM_DIR"
    if git apply --check "$PATCH" >/dev/null 2>&1; then
      git apply "$PATCH"
      echo "applied $PATCH"
    elif git apply --check --reverse "$PATCH" >/dev/null 2>&1; then
      echo "already applied"
    else
      git apply --3way "$PATCH"
      echo "applied $PATCH with a 3-way merge"
    fi
    verify_applied
    ;;
  *)
    die "unknown argument ${1}; see the header of this script"
    ;;
esac
