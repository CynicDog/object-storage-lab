#!/usr/bin/env bash
set -euo pipefail

AZURITE_HOST="${AZURITE_HOST:-azurite:10000}"
MOUNT_POINT="${MOUNT_POINT:-/mnt/adls}"
CONTAINER="${CONTAINER:-demo}"
CACHE_TTL="${CACHE_TTL:-3600}"

JAR=/workspace/build/libs/adls-mount.jar

echo "[boot] Waiting for Azurite at ${AZURITE_HOST} ..."
until curl -sf --max-time 2 "http://${AZURITE_HOST}/" > /dev/null 2>&1 \
   || curl -s  --max-time 2 "http://${AZURITE_HOST}/devstoreaccount1" > /dev/null 2>&1; do
    sleep 1
done
echo "[boot] Azurite is ready."

echo "[boot] Running setup ..."
AZURITE_HOST="${AZURITE_HOST}" java -jar "${JAR}" setup

mkdir -p "${MOUNT_POINT}" /tmp/bf2-cache /tmp/bf2-log

cat > /tmp/blobfuse2.yaml << YAML
allow-other: true
logging:
  level: log_warning
  type: base
  file-path: /tmp/bf2-log/blobfuse2.log

components:
  - libfuse
  - file_cache
  - attr_cache
  - azstorage

libfuse:
  attribute-expiration-sec: ${CACHE_TTL}
  entry-expiration-sec: ${CACHE_TTL}
  negative-entry-expiration-sec: ${CACHE_TTL}

file_cache:
  path: /tmp/bf2-cache
  timeout-sec: ${CACHE_TTL}

attr_cache:
  timeout-sec: ${CACHE_TTL}

azstorage:
  type: block
  account-name: devstoreaccount1
  account-key: Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==
  endpoint: http://${AZURITE_HOST}/devstoreaccount1
  container: ${CONTAINER}
  virtual-directory: true
YAML

echo "[boot] Mounting container '${CONTAINER}' via blobfuse2 at ${MOUNT_POINT} ..."
blobfuse2 mount "${MOUNT_POINT}" --config-file=/tmp/blobfuse2.yaml
sleep 2
echo "[boot] Mount ready."

echo ""
AZURITE_HOST="${AZURITE_HOST}" java -jar "${JAR}" demo "${MOUNT_POINT}"

blobfuse2 unmount "${MOUNT_POINT}" 2>/dev/null || true
