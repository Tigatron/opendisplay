#!/bin/zsh
# Install the Release app from dist/ into /Applications and launch it.
set -euo pipefail
cd "$(dirname "$0")"
SRC="dist/OpenDisplay USB Helper.app"
DEST="/Applications/OpenDisplay USB Helper.app"
if [[ ! -d "$SRC" ]]; then
  echo "error: missing $SRC — run ./build-release.sh first" >&2
  exit 1
fi
if [[ -d "$DEST" ]]; then
  echo -n "Overwrite $DEST? [y/N] "
  read -r answer
  if [[ "$answer" != "y" && "$answer" != "Y" ]]; then
    echo "Aborted."
    exit 1
  fi
fi
ditto "$SRC" "$DEST"
open "$DEST"
echo "Launched $DEST"
echo
echo "Permissions:"
echo "  • Local Network — allow OpenDisplay USB Helper if prompted (keyed to com.terrynamic.opendisplay.usbhelper)."
echo "  • Start at login — enable in Settings after the app is in /Applications (SMAppService requires a stable path)."
