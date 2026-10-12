# FRIDAY — notes for coding agents

FRIDAY is a local-first Android assistant plus the PC server it talks to. This folder is the project:
edit here, commit, push. Nothing else on the PC is source code.

## Layout

| Folder | What it is |
| --- | --- |
| `app/` | Android app (Kotlin, Jetpack Compose): UI, voice, phone tools, Android Auto |
| `core/` | Kotlin Multiplatform logic: shared models, presentation and interface runtime in `commonMain`; JVM/phone infrastructure in `androidMain`; JS platform implementations in `jsMain` (unit-tested) |
| `desktop/` | PC server feature modules: `agent` (FRIDAY tasks, PC access via OpenCode, accounts, daily brief), `memory`, `workspace`, `imagegen`, `model_server` (the Strata backend adapter), plus `search_service` |
| `web/` | Kotlin/JS + Compose client using the shared PC history, preferences and memory APIs; see `web/README.md` |
| `server/` | PC server core (`api/app.py`, `api/prompt_cache.py`), Docker compose, llama launcher, systemd units (`systemd/strata/` for the Strata backend), `scripts/setup.sh` |

## The running server is a separate copy

The live server runs from `~/assistant-server` (a link to the Storage drive), not from this repo. It holds
the user's data, `.env` tokens, Python venv and llama runtime. Rules:

- Never edit files under `~/assistant-server` directly, and never copy its data, `.env`, `llama.env` or
  `imagegen/config.json` into this repo.
- The model server is selected by `FRIDAY_LLM_BACKEND`: `llama` (llama.cpp, `llama-qwen38-mtp.service`, the default)
  or `strata` (`assistant-strata.service`, Strata installed in `~/AI/Strata`). The author's PC runs `strata`; see
  [desktop/model_server/README.md](desktop/model_server/README.md).
- To deploy code changes: run `FRIDAY_LLM_BACKEND=strata server/scripts/setup.sh` on that PC (copies `server/` and
  `desktop/` modules into the install; it never replaces existing secrets, configs or systemd units), then
  `systemctl --user restart assistant-api`. Restarting the model service reloads the model (~2-3 min of no chat).
- The app talks to the server's API; a change on one side often needs the matching change on the other.
  Keep both in the same commit.

## Build and test

- Android: `./gradlew :app:testDebugUnitTest :core:test` and `./gradlew :app:assembleDebug`
  (needs Android SDK 35 and JDK 17). Web/shared JS: `./gradlew :core:jsNodeTest :web:jsNodeTest :web:stageGateway`. The version is in `app/build.gradle.kts` (`versionCode`/`versionName`).
- Server modules: `python -m unittest desktop.agent.tests.<module>` from the repo root, using the server's
  venv (`~/assistant-server/api/.venv/bin/python`).

## Git

- `main` is protected: changes need a pull request, except for the owner, who can push directly.
- This repo is public. Never commit tokens, personal emails, tailnet hostnames or IPs, or `/home/<user>` /
  `/media/<user>` paths. Commit with the GitHub no-reply address.
