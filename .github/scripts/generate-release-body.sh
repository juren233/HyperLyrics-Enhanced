#!/usr/bin/env bash
set -euo pipefail

previous_tag="${1:-}"
release_commit="${2:-HEAD}"

git rev-parse --verify "${release_commit}^{commit}" >/dev/null

git log -1 --pretty=format:'%B' "$release_commit"
printf '\n'

history=""
if history_end=$(git rev-parse "${release_commit}^" 2>/dev/null); then
  if [[ -n "$previous_tag" ]]; then
    git rev-parse --verify "${previous_tag}^{commit}" >/dev/null
    history_range="${previous_tag}..${history_end}"
  else
    history_range="$history_end"
  fi

  history=$(git log "$history_range" --pretty=format:'- %s' --no-merges)
fi

if [[ -n "${history//[[:space:]]/}" ]]; then
  printf '\n<details>\n'
  printf '<summary>自上次 Release 以来的提交记录</summary>\n\n'
  printf '%s\n' "$history"
  printf '\n</details>\n'
fi

printf '\n## 下载说明\n\n'
printf '日常使用请选择 **Release** 包；**Debug** 包仅用于调试和查找问题。\n'
