#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
if ! command -v xcodegen >/dev/null 2>&1; then
  echo 'Installa XcodeGen con Homebrew: brew install xcodegen'
  exit 1
fi
xcodegen generate
open SIBSmartHub.xcodeproj
