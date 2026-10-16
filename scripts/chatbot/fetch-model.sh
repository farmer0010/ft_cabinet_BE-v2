#!/usr/bin/env bash
set -euo pipefail

LOCK="chatbot-models.lock"
MODE="strict"
ID=""
OUT=""
BASE_URL="${CHATBOT_MODEL_BASE_URL:-https://huggingface.co}"

usage() { sed -n '2,15p' "$0"; exit 2; }
while [ $# -gt 0 ]; do
  case "$1" in
    --id) ID="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --lock) LOCK="$2"; shift 2 ;;
    --mode) MODE="$2"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "알 수 없는 옵션: $1" >&2; usage ;;
  esac
done
[ -n "$ID" ] && [ -n "$OUT" ] || usage
case "$MODE" in strict|resolve) ;; *) echo "mode 는 strict 또는 resolve 여야 합니다." >&2; exit 2 ;; esac
[ -f "$LOCK" ] || { echo "lock 파일이 없습니다: $LOCK" >&2; exit 1; }

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

REPO=""; REVISION=""; PREFIX=""; MAX_TOKENS="128"
FILES=()
while IFS='|' read -r kind a b c d e; do
  case "$kind" in
    meta) if [ "$a" = "$ID" ]; then REPO="$b"; REVISION="$c"; PREFIX="$d"; MAX_TOKENS="${e:-128}"; fi ;;
    file) if [ "$a" = "$ID" ]; then FILES+=("$b|$c|$d"); fi ;;
  esac
done < <(grep -v '^[[:space:]]*#' "$LOCK" | grep -v '^[[:space:]]*$')

[ -n "$REPO" ] || { echo "lock 파일에 모델 '$ID' 가 없습니다." >&2; exit 1; }
[ "${#FILES[@]}" -gt 0 ] || { echo "lock 파일에 모델 '$ID' 의 file 항목이 없습니다." >&2; exit 1; }

if [ "$REVISION" = "PENDING" ]; then
  if [ "$MODE" = "strict" ]; then
    echo "모델 '$ID' 의 revision 이 고정되지 않았습니다(PENDING). 먼저 '모델 고정' 작업으로 값을 확정해 lock 파일에 커밋하세요." >&2
    exit 1
  fi
  echo "revision 이 PENDING 이라 저장소의 현재 커밋을 조회합니다: $REPO" >&2
  REVISION="$(curl --fail --silent --show-error --location --retry 3 "$BASE_URL/api/models/$REPO" \
    | grep -o '"sha":"[0-9a-f]\{40\}"' | head -1 | cut -d'"' -f4)"
  [ -n "$REVISION" ] || { echo "revision 을 조회하지 못했습니다." >&2; exit 1; }
fi

mkdir -p "$OUT"
PIN_LINES=()
for entry in "${FILES[@]}"; do
  IFS='|' read -r kind path expected <<<"$entry"
  case "$kind" in model) target="$OUT/model.onnx" ;; tokenizer) target="$OUT/tokenizer.json" ;;
    *) echo "알 수 없는 file 종류: $kind" >&2; exit 1 ;; esac

  if [ "$expected" = "PENDING" ] && [ "$MODE" = "strict" ]; then
    echo "모델 '$ID' 의 $path SHA256 이 고정되지 않았습니다(PENDING)." >&2
    exit 1
  fi

  url="$BASE_URL/$REPO/resolve/$REVISION/$path"
  echo "내려받는 중: $url" >&2
  if ! curl --fail --silent --show-error --location --retry 3 --output "$target.part" "$url"; then
    echo "내려받기에 실패했습니다: $url" >&2
    echo "이 저장소에서 쓸 수 있는 .onnx 파일 후보:" >&2
    curl --silent --location "$BASE_URL/api/models/$REPO" 2>/dev/null \
      | grep -o '"rfilename":"[^"]*onnx[^"]*"' | cut -d'"' -f4 | sed 's/^/  - /' >&2 || true
    rm -f "$target.part"
    exit 1
  fi
  actual="$(sha256_of "$target.part")"

  if [ "$expected" != "PENDING" ] && [ "$actual" != "$expected" ]; then
    echo "SHA256 불일치: $path" >&2
    echo "  기대: $expected" >&2
    echo "  실제: $actual" >&2
    rm -f "$target.part"
    exit 1
  fi
  mv "$target.part" "$target"
  size="$(wc -c < "$target" | tr -d ' ')"
  echo "확인됨: $path (${size} bytes, sha256=$actual)" >&2
  PIN_LINES+=("file|$ID|$kind|$path|$actual")
done

{
  echo "modelId=$ID"
  echo "repo=$REPO"
  echo "revision=$REVISION"
  echo "prefix=$PREFIX"
  echo "maxTokens=$MAX_TOKENS"
} > "$OUT/model.properties"

if [ "$MODE" = "resolve" ]; then
  echo
  echo "# lock 파일에 아래처럼 고정하세요 (모델 '$ID')"
  echo "meta|$ID|$REPO|$REVISION|$PREFIX|$MAX_TOKENS"
  printf '%s\n' "${PIN_LINES[@]}"
fi
echo "완료: $OUT" >&2
