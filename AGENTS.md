# FRIDAY — notes for coding agents

FRIDAY is a local-first Android assistant plus the PC server it talks to. This folder is the project:
edit here, commit, push. Nothing else on the PC is source code.

## Layout

| Folder | What it is |
| --- | --- |
| `app/` | Android app (Kotlin, Jetpack Compose): UI, voice, phone tools, Android Auto |
| `core/` | Plain-JVM logic the app uses: conversation engine, model client, tools, UI blocks (unit-tested) |
| `desktop/` | PC server feature modules: `agent` (FRIDAY tasks, PC access via OpenCode, accounts, daily brief), `memory`, `workspace`, `imagegen`, plus `search_service` |
| `server/` | PC server core (`api/app.py`, `api/prompt_cache.py`), Docker compose, llama launcher, systemd units, `scripts/setup.sh` |

## The running server is a separate copy

The live server runs from `~/assistant-server` (a link to the Storage drive), not from this repo. It holds
the user's data, `.env` tokens, Python venv and llama runtime. Rules:

- Never edit files under `~/assistant-server` directly, and never copy its data, `.env`, `llama.env` or
  `imagegen/config.json` into this repo.
- To deploy code changes: run `server/scripts/setup.sh` (copies `server/` and `desktop/` modules into the
  install; it never replaces existing secrets, configs or systemd units), then
  `systemctl --user restart assistant-api`. Restarting `llama-qwen38-mtp` reloads the model (~2 min of no chat).
- The app talks to the server's API; a change on one side often needs the matching change on the other.
  Keep both in the same commit.

## Build and test

- Android: `./gradlew :app:testDebugUnitTest :core:test` and `./gradlew :app:assembleDebug`
  (needs `ANDROID_HOME`). The version is in `app/build.gradle.kts` (`versionCode`/`versionName`).
- Server modules: `python -m unittest desktop.agent.tests.<module>` from the repo root, using the server's
  venv (`~/assistant-server/api/.venv/bin/python`).

## Git

- `main` is protected: changes need a pull request, except for the owner, who can push directly.
- This repo is public. Never commit tokens, personal emails, tailnet hostnames or IPs, or `/home/<user>` /
  `/media/<user>` paths. Commit with the GitHub no-reply address.
