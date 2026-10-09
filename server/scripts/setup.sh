#!/usr/bin/env bash
# Set up FRIDAY's PC server from this checkout.
# Usage: server/scripts/setup.sh
# Installs to ~/assistant-server (override with SERVER_DIR). Safe to re-run.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
APP_REPO="$(cd "$HERE/.." && pwd)"
DEST="${SERVER_DIR:-$HOME/assistant-server}"
mkdir -p "$DEST/api" "$DEST/searxng/config" "$DEST/maintenance" "$DEST/llama" "$DEST/slot-cache" "$HOME/.config/systemd/user"

# Gateway core from server/; feature modules (agent, memory, workspace, imagegen) from desktop/.
cp "$HERE"/api/{app.py,prompt_cache.py,requirements.txt} "$DEST/api/"
for module in agent memory workspace imagegen; do
  rsync -a --delete --exclude tests --exclude __pycache__ --exclude config.json "$APP_REPO/desktop/$module/" "$DEST/api/$module/"
done
cp "$HERE"/compose.yml "$HERE"/test.sh "$DEST/"
cp "$HERE"/maintenance/memory-backup.py "$DEST/maintenance/"
cp "$HERE"/llama/start-server.sh "$DEST/llama/"
[ -f "$DEST/llama/llama.env" ] || cp "$HERE/llama/llama.env.example" "$DEST/llama/llama.env"
[ -f "$DEST/api/imagegen/config.json" ] || cp "$HERE/imagegen/config.example.json" "$DEST/api/imagegen/config.json"

# Secrets: generated once, never committed.
if [ ! -f "$DEST/.env" ]; then
  cp "$HERE/.env.example" "$DEST/.env"; chmod 600 "$DEST/.env"
  sed -i "s|^ASSISTANT_API_TOKEN=$|ASSISTANT_API_TOKEN=$(openssl rand -hex 32)|; s|^CRAWL4AI_API_TOKEN=$|CRAWL4AI_API_TOKEN=$(openssl rand -hex 32)|" "$DEST/.env"
fi
if [ ! -f "$DEST/searxng/config/settings.yml" ]; then
  sed "s|secret_key: \"CHANGE_ME\".*|secret_key: \"$(openssl rand -hex 32)\"|" "$HERE/searxng/settings.yml" > "$DEST/searxng/config/settings.yml"
fi

python3 -m venv "$DEST/api/.venv"
"$DEST/api/.venv/bin/pip" install -q -r "$DEST/api/requirements.txt"
[ -f "$APP_REPO/desktop/agent/requirements-vault.txt" ] && "$DEST/api/.venv/bin/pip" install -q -r "$APP_REPO/desktop/agent/requirements-vault.txt"

# Units are only added, never replaced: an existing install may have customised them (paths, schedules).
for unit in "$HERE"/systemd/*.service "$HERE"/systemd/*.timer; do
  [ -e "$HOME/.config/systemd/user/$(basename "$unit")" ] || cp "$unit" "$HOME/.config/systemd/user/"
done
systemctl --user daemon-reload
echo "Installed to $DEST. To pick up code changes on a running install: systemctl --user restart assistant-api."
echo "First install only: Next: fill llama/llama.env and api/imagegen/config.json, set VOICE_MODELS in .env,"
echo "then: (cd $DEST && docker compose up -d) and systemctl --user enable --now llama-qwen38-mtp assistant-api friday-memory-backup.timer"
