#!/usr/bin/env bash
# EmbeddingGemma 2 text-only on the RX580. Never select CUDA/the P100.
set -euo pipefail
: "${FRIDAY_EMBEDDING_BINARY:?Set an EmbeddingGemma-2-capable llama-server path}"
: "${FRIDAY_EMBEDDING_MODEL:?Set the text-only EmbeddingGemma 2 Q8 GGUF path}"
DEVICE="${FRIDAY_EMBEDDING_DEVICE:-Vulkan0}"
[[ "$DEVICE" =~ ^Vulkan[0-9]+$ ]] || { echo 'Embedding service requires one RX580 Vulkan device' >&2; exit 1; }
DEVICES="$("$FRIDAY_EMBEDDING_BINARY" --list-devices 2>&1)"
if ! printf '%s\n' "$DEVICES" | grep -E "^[[:space:]]*$DEVICE:.*(RX[[:space:]_-]*580|POLARIS10)" >/dev/null; then
  echo 'Selected device is not the RX580; refusing to load' >&2; exit 1
fi
exec "$FRIDAY_EMBEDDING_BINARY" --model "$FRIDAY_EMBEDDING_MODEL" --alias embeddinggemma-2-Q8_0 \
  --host 127.0.0.1 --port "${FRIDAY_EMBEDDING_PORT:-8093}" --device "$DEVICE" -ngl 99 \
  --embeddings --pooling mean -c 8192 -b 8192 -ub 8192 -t 2 -tb 2 --parallel 1
