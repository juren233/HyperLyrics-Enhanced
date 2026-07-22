#!/usr/bin/env bash
set -euo pipefail

previous_tag="${1:-}"
release_commit="${2:-HEAD}"

git rev-parse --verify "${release_commit}^{commit}" >/dev/null

printf '## 最新提交\n'
git log -1 --pretty=format:'%B' "$release_commit"
printf '\n'

if ! history_end=$(git rev-parse "${release_commit}^" 2>/dev/null); then
  exit 0
fi

if [[ -n "$previous_tag" ]]; then
  git rev-parse --verify "${previous_tag}^{commit}" >/dev/null
  history_range="${previous_tag}..${history_end}"
else
  history_range="$history_end"
fi

history=$(git log "$history_range" --pretty=format:'- %s' --no-merges)
if [[ -z "${history//[[:space:]]/}" ]]; then
  exit 0
fi

printf '\n<details>\n'
printf '<summary>自上次 Release 以来的提交记录</summary>\n\n'
printf '%s\n' "$history"
printf '\n</details>\n'
