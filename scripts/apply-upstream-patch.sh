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
PATCH_FILES=(
  internal/registry/models/models.json
  internal/runtime/executor/antigravity_executor_request.go
  internal/runtime/executor/antigravity_executor_buildrequest_test.go
)

die() { echo "error: $*" >&2; exit 1; }

[ -d "$UPSTREAM_DIR/.git" ] || die "$UPSTREAM_DIR is not a git checkout; run scripts/fetch-upstream.sh first"

verify_applied() {
  local req="$UPSTREAM_DIR/internal/runtime/executor/antigravity_executor_request.go"
  local models="$UPSTREAM_DIR/internal/registry/models/models.json"
  if grep -q 'sjson.Delete(payloadStr, "request.generationConfig.maxOutputTokens")' "$req" \
      || grep -q 'sjson.DeleteBytes(payload, "request.generationConfig.maxOutputTokens")' "$req"; then
    die "maxOutputTokens is still deleted: the patch did not take"
  fi
  grep -q 'gemini-3.8-flash-low' "$models" || die "gemini-3.8-flash-low is missing from the catalog"
  echo "patch verified on $(git -C "$UPSTREAM_DIR" rev-parse --short HEAD)"
}

case "${1:-}" in
  --refresh)
    cd "$UPSTREAM_DIR"
    BASE="$(git rev-parse --short HEAD)"
    # Regenerate against the merge base so the diff carries only our fixes, not upstream's.
    git diff "$BASE" -- "${PATCH_FILES[@]}" > "$PATCH"
    echo "wrote $PATCH ($(grep -c '^@@' "$PATCH" || true) hunks)"
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
