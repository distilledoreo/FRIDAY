FROM python:3.12-slim@sha256:05cda9777409a9c3ffddd94a4c476b79f0769a0b4857f0c7ed9226b6800b0d6f
RUN apt-get update && apt-get install -y --no-install-recommends chromium fonts-liberation \
    && rm -rf /var/lib/apt/lists/* \
    && pip install --no-cache-dir playwright==1.56.0
COPY opencode /usr/local/bin/opencode
COPY container_bridge.py /opt/friday/container_bridge.py
WORKDIR /work
ENTRYPOINT ["python", "-I", "/opt/friday/container_bridge.py"]
