#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ -z "${APPLE_TEAM_ID:-}" ]]; then
  echo 'Imposta APPLE_TEAM_ID con il Team ID del tuo account Apple Developer.'
  exit 1
fi
xcodegen generate
mkdir -p build
xcodebuild -project SIBSmartHub.xcodeproj -scheme SIBSmartHub \
  -configuration Release -destination 'generic/platform=iOS' \
  -archivePath build/SIBSmartHub.xcarchive \
  DEVELOPMENT_TEAM="$APPLE_TEAM_ID" -allowProvisioningUpdates archive
/usr/libexec/PlistBuddy -c Clear build/ExportOptions.plist
/usr/libexec/PlistBuddy -c 'Add :method string release-testing' build/ExportOptions.plist
/usr/libexec/PlistBuddy -c 'Add :signingStyle string automatic' build/ExportOptions.plist
/usr/libexec/PlistBuddy -c "Add :teamID string $APPLE_TEAM_ID" build/ExportOptions.plist
xcodebuild -exportArchive -archivePath build/SIBSmartHub.xcarchive \
  -exportPath build/privata -exportOptionsPlist build/ExportOptions.plist -allowProvisioningUpdates
echo 'Pacchetto firmato disponibile nella cartella build/privata.'
