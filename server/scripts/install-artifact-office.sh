#!/usr/bin/env bash
# Install an existing, trusted LibreOffice distribution for FRIDAY without root.
# Usage: server/scripts/install-artifact-office.sh /path/to/libreoffice/program/soffice
# Keeps the distribution's licenses and support files. Never downloads software or replaces an install.
set -euo pipefail
SOURCE_EXECUTABLE="$(realpath "${1:?Pass the path to a trusted LibreOffice program/soffice}")"
SOURCE_PROGRAM="$(dirname "$SOURCE_EXECUTABLE")"
[[ "$(basename "$SOURCE_PROGRAM")" == program && -x "$SOURCE_EXECUTABLE" ]] || { echo 'Expected an executable LibreOffice program/soffice' >&2; exit 1; }
SOURCE_ROOT="$(dirname "$SOURCE_PROGRAM")"
DESTINATION_PARENT="${XDG_DATA_HOME:-$HOME/.local/share}/friday"
DESTINATION="$DESTINATION_PARENT/libreoffice"
if [ -x "$DESTINATION/program/soffice" ]; then echo 'FRIDAY Office runtime is already installed.'; exit 0; fi
[ ! -e "$DESTINATION" ] || { echo 'Destination already exists; inspect it before installing.' >&2; exit 1; }
mkdir -p "$DESTINATION_PARENT"
STAGING="$(mktemp -d "$DESTINATION_PARENT/.office-install.XXXXXXXX")"
trap 'rm -rf -- "$STAGING"' EXIT
rsync -a "$SOURCE_ROOT/" "$STAGING/"
[ -x "$STAGING/program/soffice" ]
mv "$STAGING" "$DESTINATION"
trap - EXIT
echo 'Installed the local Office runtime for isolated artifact rendering.'
