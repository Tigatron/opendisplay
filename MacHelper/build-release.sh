#!/bin/zsh
# Ad-hoc Release build → MacHelper/dist/OpenDisplay USB Helper.app
set -euo pipefail
cd "$(dirname "$0")"
./generate.sh
xcodebuild -project OpenDisplayUSBHelper.xcodeproj \
  -scheme OpenDisplayUSBHelper \
  -configuration Release \
  -derivedDataPath build \
  build
APP="build/Build/Products/Release/OpenDisplay USB Helper.app"
if [[ ! -d "$APP" ]]; then
  echo "error: expected app at $APP" >&2
  exit 1
fi
mkdir -p dist
rm -rf "dist/OpenDisplay USB Helper.app"
ditto "$APP" "dist/OpenDisplay USB Helper.app"
echo "Release app: $(pwd)/dist/OpenDisplay USB Helper.app"
