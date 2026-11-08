#!/usr/bin/env bash
set -euo pipefail

# Reproducible PRoot Android binary inputs. The archives are verified before packaging.
# Binary release: https://github.com/ahmed-alnassif/proot/releases/tag/v26.10.08-a179d3e
TAG="v26.10.08-a179d3e"
BASE="https://github.com/ahmed-alnassif/proot/releases/download/${TAG}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${ROOT}/app/src/main/jniLibs"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

# SHA-256 values published with the pinned upstream release assets.
declare -A SHA256=(
  [aarch64]="d621d71fb68bf07accbada37ffc4208cf4a963eed70916ee68f848cbd9555cc3"
  [arm]="0c382c0512c3d536dc0685f3f6264b326073c071ebdd961e3346799aae8dd5b3"
  [x86]="640da6a26dec3e86d3bdf4f3b9d8f6dd0466ba7be0de36b1ca6e508229610f36"
  [x86_64]="ef06aeb47f5bb9c96bb9019fe8efa320ed199151020428b2335af6035c05f166"
)

declare -A ABI=(
  [aarch64]="arm64-v8a"
  [arm]="armeabi-v7a"
  [x86]="x86"
  [x86_64]="x86_64"
)

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v unzip >/dev/null || { echo "unzip is required" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "sha256sum is required" >&2; exit 1; }

# Download one archive, trying the canonical GitHub URL first and then public
# mirrors. Every candidate is verified against the pinned SHA-256 above, so a
# mirror can never substitute different bytes — it can only fail.
fetch_one() {
  local arch="$1" zip="$2"
  local path="proot-${arch}.zip"
  local urls=(
    "${BASE}/${path}"
    "https://gh-proxy.com/${BASE}/${path}"
    "https://ghfast.top/${BASE}/${path}"
    "https://ghproxy.net/${BASE}/${path}"
  )
  local url round
  for round in 1 2 3 4; do
    for url in "${urls[@]}"; do
      if curl --fail --location --retry 1 --retry-all-errors --silent --show-error "${url}" -o "${zip}"; then
        if echo "${SHA256[$arch]}  ${zip}" | sha256sum --check --status; then
          echo "Fetched ${path} from ${url}"
          return 0
        fi
        echo "SHA-256 mismatch from ${url}; trying next source" >&2
      else
        echo "Download failed from ${url}; trying next source" >&2
      fi
    done
    echo "Round ${round} exhausted for ${path}; retrying all sources" >&2
    sleep 5
  done
  echo "All download sources failed for ${path}" >&2
  return 1
}

for arch in aarch64 arm x86 x86_64; do
  zip="${TMP}/proot-${arch}.zip"
  fetch_one "${arch}" "${zip}"

  unpack="${TMP}/${arch}"
  mkdir -p "${unpack}"
  unzip -q "${zip}" -d "${unpack}"
  test -s "${unpack}/proot" || { echo "Missing proot binary in ${arch} archive" >&2; exit 1; }
  test -s "${unpack}/loader" || { echo "Missing loader binary in ${arch} archive" >&2; exit 1; }

  target="${OUT}/${ABI[$arch]}"
  mkdir -p "${target}"
  install -m 0755 "${unpack}/proot" "${target}/libproot.so"
  install -m 0755 "${unpack}/loader" "${target}/libloader.so"
  if test -s "${unpack}/loader-m32"; then
    install -m 0755 "${unpack}/loader-m32" "${target}/libloader-m32.so"
  fi
  echo "Prepared ${ABI[$arch]}"
done
