#!/usr/bin/env bash
# Downloads the three dependencies of the "Hey Muse" detector (same versions and checksums as
# muse-gadget-sdk/linux/wakeword/build.sh), verifies them and unpacks them into
# third_party/wakeword/. Run once before the first build.
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/.." && pwd)
DEST="$ROOT/third_party/wakeword"
REGISTRY="https://components-file.espressif.com/components"
# component|version|archive|sha256
ARCHIVES=(
  "pytorch/executorch|1.6.0-dev.20261112|pytorch__executorch-v1.6.0-dev.20261112.zip|7885747a7a47fe81b72f52b52884c122439a3304b117d0774dc05af3e03ba312"
  "espressif/esp-nn|1.5.0|espressif__esp-nn-v1.5.0.zip|50bdc2b89b54c1fae5d308a5961bb7a261453bbcc4e384ff1c696dea7514e1ce"
  "esphome/esp-micro-speech-features|1.2.3|esphome__esp-micro-speech-features-v1.2.3.zip|052a8f32e7a82f49593bfdc067638a8cee6542443726d1a7e5b01b3bd9d67eac"
)
mkdir -p "$DEST/archives"
for entry in "${ARCHIVES[@]}"; do
  IFS='|' read -r name version file sha <<<"$entry"
  archive="$DEST/archives/$file"
  if [[ ! -f $archive ]]; then
    echo "Downloading $file"
    curl -fsSL --retry 3 --proto '=https' -o "$archive.part" "$REGISTRY/$name/$version/$file"
    mv -- "$archive.part" "$archive"
  fi
  actual=$(shasum -a 256 -- "$archive" | awk '{print $1}')
  if [[ $actual != "$sha" ]]; then
    rm -f -- "$archive"
    echo "Unexpected checksum for $file: $actual" >&2
    exit 1
  fi
  echo "Checksum verified: $file ($(wc -c <"$archive" | tr -d ' ') bytes)"
  target="$DEST/${name#*/}"
  rm -rf -- "$target"
  mkdir -p "$target"
  unzip -q "$archive" -d "$target"
done
