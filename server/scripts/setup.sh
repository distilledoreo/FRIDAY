#!/usr/bin/env bash
# Set up FRIDAY's PC server from this checkout.
# Usage: server/scripts/setup.sh            (llama.cpp model server, the default)
#        FRIDAY_LLM_BACKEND=strata server/scripts/setup.sh   (Strata; see desktop/model_server/README.md)
# Installs to ~/assistant-server (override with SERVER_DIR). Safe to re-run.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
APP_REPO="$(cd "$HERE/.." && pwd)"
DEST="${SERVER_DIR:-$HOME/assistant-server}"
BACKEND="${FRIDAY_LLM_BACKEND:-llama}"
case "$BACKEND" in llama|strata) ;; *) echo "FRIDAY_LLM_BACKEND must be llama or strata" >&2; exit 1 ;; esac
mkdir -p "$DEST/api" "$DEST/searxng/config" "$DEST/maintenance" "$DEST/llama" "$DEST/slot-cache" "$HOME/.config/systemd/user"

# Gateway core from server/; feature modules (agent, memory, workspace, imagegen, model_server) from desktop/.
cp "$HERE"/api/{app.py,prompt_cache.py,requirements.txt} "$DEST/api/"
for module in agent memory workspace imagegen model_server; do
  rsync -a --delete --exclude tests --exclude __pycache__ --exclude config.json "$APP_REPO/desktop/$module/" "$DEST/api/$module/"
done
cp "$HERE"/compose.yml "$HERE"/test.sh "$DEST/"
cp "$HERE"/maintenance/memory-backup.py "$DEST/maintenance/"
cp "$HERE"/llama/start-server.sh "$DEST/llama/"
mkdir -p "$DEST/code-lookup"
cp "$HERE/code-lookup/start-embeddings.sh" "$DEST/code-lookup/"
[ -f "$DEST/code-lookup/embeddings.env" ] || cp "$HERE/code-lookup/embeddings.env.example" "$DEST/code-lookup/embeddings.env"
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
if [ "$BACKEND" = strata ]; then
  # The Strata model service, and a drop-in that points the API at it (FRIDAY_LLM_BACKEND=strata, wait for the model).
  [ -e "$HOME/.config/systemd/user/assistant-strata.service" ] || cp "$HERE/systemd/strata/assistant-strata.service" "$HOME/.config/systemd/user/"
  mkdir -p "$HOME/.config/systemd/user/assistant-api.service.d"
  [ -e "$HOME/.config/systemd/user/assistant-api.service.d/20-strata.conf" ] || \
    cp "$HERE/systemd/strata/assistant-api.service.d/20-strata.conf" "$HOME/.config/systemd/user/assistant-api.service.d/"
fi
systemctl --user daemon-reload
echo "Installed to $DEST. To pick up code changes on a running install: systemctl --user restart assistant-api."
echo "First install only: Next: fill llama/llama.env and api/imagegen/config.json, set VOICE_MODELS in .env,"
MODEL_UNIT=llama-qwen38-mtp; [ "$BACKEND" = strata ] && MODEL_UNIT=assistant-strata
echo "then: (cd $DEST && docker compose up -d) and systemctl --user enable --now $MODEL_UNIT assistant-api friday-memory-backup.timer"
