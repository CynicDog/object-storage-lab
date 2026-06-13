#!/usr/bin/env bash
set -euo pipefail

AZURITE_HOST="${AZURITE_HOST:-azurite:10000}"
MOUNT_POINT="${MOUNT_POINT:-/mnt/adls}"
CONTAINER="${CONTAINER:-demo}"
CACHE_TTL="${CACHE_TTL:-3600}"

JAR=/workspace/build/libs/adls-mount.jar

echo "[boot] Waiting for Azurite at ${AZURITE_HOST} ..."
until curl -s --max-time 2 "http://${AZURITE_HOST}/devstoreaccount1" > /dev/null 2>&1; do
    sleep 1
done
echo "[boot] Azurite is ready."

echo "[boot] Running setup ..."
AZURITE_HOST="${AZURITE_HOST}" java -jar "${JAR}" setup

mkdir -p ~/.config/rclone "${MOUNT_POINT}"

cat > ~/.config/rclone/rclone.conf << CONF
[azurite]
type = azureblob
account = devstoreaccount1
key = Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==
endpoint = http://${AZURITE_HOST}/devstoreaccount1
CONF

echo "[boot] Mounting container '${CONTAINER}' via rclone at ${MOUNT_POINT} ..."
rclone mount "azurite:${CONTAINER}" "${MOUNT_POINT}" \
    --allow-other \
    --dir-cache-time "${CACHE_TTL}s" \
    --vfs-cache-mode off \
    --log-level ERROR \
    --daemon

until ls "${MOUNT_POINT}" > /dev/null 2>&1; do sleep 0.5; done
echo "[boot] Mount ready."

echo ""
AZURITE_HOST="${AZURITE_HOST}" java -jar "${JAR}" demo "${MOUNT_POINT}"

fusermount3 -u "${MOUNT_POINT}" 2>/dev/null || umount "${MOUNT_POINT}" 2>/dev/null || true
