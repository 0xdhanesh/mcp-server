#!/usr/bin/env bash
# Fail when a commit contains secrets, local account paths, or files that
# should never be checked in. GitHub Actions runs this on every push.
# The pre-commit hook runs the same check before a commit is created.
set -euo pipefail

HOME_PATH_RE='/(Users|home)/[A-Za-z0-9._-]+/'
WINDOWS_HOME_RE='[A-Za-z]:\\Users\\[A-Za-z0-9._-]+'
PRIVATE_KEY_RE='-----BEGIN [A-Z ]*PRIVATE KEY-----'
TOKEN_RE='(AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9-]{10,})'
ALLOWED_HOME='/(Users|home)/(runner|ubuntu|linuxbrew|Shared)/'

is_unwanted_path() {
  local path="$1"
  local base="${path##*/}"

  case "$base" in
    .env | .env.* | .envrc | .npmrc | .pypirc | credentials.json | secrets.json | local.properties | .DS_Store | Thumbs.db | id_rsa | id_rsa.pub | id_dsa | id_ecdsa | id_ed25519 | *.pem | *.key | *.p12 | *.pfx | *.jks | *.keystore | *.ppk | *.iml | *.iws | *.ipr | *.log | *.swp | *.swo | *.bak | *.orig | *~ | *.class)
      return 0
      ;;
  esac

  case "$path" in
    .idea/* | */.idea/* | .vscode/* | */.vscode/* | .pentest/* | */.pentest/* | .claude/* | */.claude/* | .codex/* | */.codex/*)
      return 0
      ;;
    build/* | */build/*)
      case "$path" in
        src/main/* | src/test/*) return 1 ;;
      esac
      return 0
      ;;
  esac

  return 1
}

scan() {
  local root
  root=$(git rev-parse --show-toplevel)
  (
    cd "$root" || exit 1
    failed=0

    while IFS= read -r -d '' path; do
      if is_unwanted_path "$path"; then
        printf 'Unwanted file: %s\n' "$path" >&2
        failed=1
      fi
    done < <(git ls-files -z)

    while IFS= read -r line; do
      [[ -z "$line" ]] && continue
      stripped=$(printf '%s\n' "$line" | sed -E "s#${ALLOWED_HOME}##g")
      if [[ "$stripped" =~ $HOME_PATH_RE ]]; then
        printf 'Exposed username: %s\n' "$line" >&2
        failed=1
      fi
    done < <(git grep -I -n -E -e "$HOME_PATH_RE" -- . || true)

    while IFS= read -r line; do
      [[ -z "$line" ]] && continue
      printf 'Exposed username: %s\n' "$line" >&2
      failed=1
    done < <(git grep -I -n -E -e "$WINDOWS_HOME_RE" -- . || true)

    while IFS= read -r line; do
      [[ -z "$line" ]] && continue
      printf 'Exposed secret: %s\n' "$line" >&2
      failed=1
    done < <(git grep -I -n -E -e "$PRIVATE_KEY_RE" -e "$TOKEN_RE" -- . || true)

    if [[ "$failed" -ne 0 ]]; then
      printf '\nRemove the files and lines above before committing.\n' >&2
      exit 1
    fi

    printf 'No unwanted files or exposed usernames found.\n'
  )
}

expect_failure() {
  local dir="$1"
  local reason="$2"
  local log
  log=$(mktemp)
  if (cd "$dir" && scan) >"$log" 2>&1; then
    printf 'Self-test expected a failure for %s.\n' "$reason" >&2
    cat "$log" >&2
    rm -f "$log"
    exit 1
  fi
  rm -f "$log"
}

self_test() {
  SELF_TEST_TMP=$(mktemp -d)
  trap 'rm -rf "$SELF_TEST_TMP"' EXIT

  local leak="$SELF_TEST_TMP/leak"
  git init -q -b main "$leak"
  printf 'path=/%s/%s/work\n' Users ada > "$leak/Leak.kt"
  git -C "$leak" add -f Leak.kt
  expect_failure "$leak" "a home-directory username"

  local windows="$SELF_TEST_TMP/windows"
  git init -q -b main "$windows"
  printf 'path=%s:\\%s\\%s\\work\n' C Users ada > "$windows/Leak.kt"
  git -C "$windows" add -f Leak.kt
  expect_failure "$windows" "a Windows profile username"

  local secret_dir="$SELF_TEST_TMP/secret"
  git init -q -b main "$secret_dir"
  printf 'token\n' > "$secret_dir/.env"
  printf -- '-----BEGIN %s-----\n' "OPENSSH PRIVATE KEY" > "$secret_dir/Key.kt"
  git -C "$secret_dir" add -A -f
  expect_failure "$secret_dir" "an unwanted file and a private key"

  local clean="$SELF_TEST_TMP/clean"
  git init -q -b main "$clean"
  mkdir -p "$clean/src"
  printf -- '- Vibed by 0xdhanesh || linkedin\nhttps://linkedin.com/in/dhanesh-sivasamy\n' > "$clean/src/Credit.kt"
  printf 'path=/home/runner/work\n' > "$clean/src/Runner.kt"
  cp "${BASH_SOURCE[0]}" "$clean/check-unwanted.sh"
  git -C "$clean" add -A -f
  (cd "$clean" && scan) >/dev/null

  printf 'check-unwanted self-test passed.\n'
}

if [[ "${1:-}" == "--self-test" ]]; then
  self_test
else
  scan
fi
