#!/usr/bin/env bash
set -euo pipefail

version_file="${VERSION_FILE:-app/build.gradle.kts}"

source_name="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=.*"([^"]+)".*/\1/p' "$version_file" | head -n1)"
source_code="$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=*[[:space:]]*([0-9]+).*/\1/p' "$version_file" | head -n1)"

if [[ -z "$source_name" ]]; then
  echo "Unable to read versionName from $version_file" >&2
  exit 1
fi
if [[ ! "$source_code" =~ ^[1-9][0-9]*$ ]]; then
  echo "versionCode must be a positive integer: $source_code" >&2
  exit 1
fi

if [[ "$source_name" =~ ^([0-9]+\.[0-9]+\.[0-9]+)(-(beta|canary))?$ ]]; then
  base_version="${BASH_REMATCH[1]}"
  channel="${BASH_REMATCH[3]:-stable}"
else
  echo "versionName must use X.X.X, X.X.X-beta, or X.X.X-canary: $source_name" >&2
  exit 1
fi

current_commit="$(git rev-parse HEAD)"
full_version="$base_version"

if [[ "$channel" != "stable" ]]; then
  base_regex="${base_version//./\\.}"
  tag_regex="^v${base_regex}-${channel}\.([0-9]+)$"
  highest_number=0
  tag_on_current_commit=""

  while IFS= read -r tag; do
    if [[ "$tag" =~ $tag_regex ]]; then
      number="${BASH_REMATCH[1]}"
      if (( number > highest_number )); then
        highest_number="$number"
      fi
      tag_commit="$(git rev-parse "${tag}^{commit}" 2>/dev/null || true)"
      if [[ "$tag_commit" == "$current_commit" ]]; then
        tag_on_current_commit="${tag#v}"
      fi
    fi
  done < <(git tag --list "v${base_version}-${channel}.*")

  if [[ -n "$tag_on_current_commit" ]]; then
    full_version="$tag_on_current_commit"
  else
    full_version="${base_version}-${channel}.$((highest_number + 1))"
  fi
fi

previous_stable_tag=""
if parent_commit="$(git rev-parse HEAD^ 2>/dev/null)"; then
  while IFS= read -r tag; do
    if [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
      previous_stable_tag="$tag"
    fi
  done < <(git tag --merged "$parent_commit" | sort -V)
fi

printf 'source_name=%s\n' "$source_name"
printf 'code=%s\n' "$source_code"
printf 'base=%s\n' "$base_version"
printf 'channel=%s\n' "$channel"
printf 'full_name=%s\n' "$full_version"
printf 'full_tag=v%s\n' "$full_version"
printf 'prev_stable_tag=%s\n' "$previous_stable_tag"
