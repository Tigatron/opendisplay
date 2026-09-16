#!/bin/zsh
# Regenerate OpenDisplayUSBHelper.xcodeproj from project.yml.
set -euo pipefail
cd "$(dirname "$0")"
exec xcodegen generate
