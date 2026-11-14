#!/usr/bin/env bash
# Runs only inside an isolated checkout with read-only repository credentials.
set -euo pipefail
git config user.name 'Mika sync'
git config user.email 'mika-sync@users.noreply.github.com'
if ! git remote get-url upstream >/dev/null 2>&1; then
  git remote add upstream https://github.com/wangzan101/Mika.git
fi
git fetch --no-tags upstream main
# The PR branch must contain both main and upstream, including merge parents.
# At most one merge may be left in progress for Codex at a time.
target="$1"
if git merge-base --is-ancestor "$target" HEAD; then exit 0; fi
if ! git merge --no-edit --no-ff "$target"; then
  git rev-parse -q --verify MERGE_HEAD >/dev/null
  test -n "$(git diff --name-only --diff-filter=U)"
fi
