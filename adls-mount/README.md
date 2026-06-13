# adls-mount-lab

A containerized reproduction of the `Files.list()` vs `Files.isRegularFile()` divergence
that occurred in production when an app read from an ADLS container mounted via blobfuse2
over a Kubernetes PVC.

Reference write-up:
[tech-debt/systems/cloud-storage-and-mount-drivers.md](../tech-debt/systems/cloud-storage-and-mount-drivers.md)

---

## The production bug (appendix)

A daily Java batch derived a sequence number from the previous day's output:

```
Files.isRegularFile(".../20260609.parquet")  →  true   ✓  worked
Files.list("history/")                       →  []     ✗  returned 0 entries
```

Because the listing was empty the batch reset its counter to seed (`1`) and emitted
duplicate keys downstream.

**Root causes** (three failure modes, any one sufficient):

| # | Cause | Doc section |
|---|---|---|
| 1 | blobfuse2 stopped at the first `List Blobs` page without following the continuation token | §2.5 |
| 2 | Flat-namespace directory had no marker blob; blobfuse2 couldn't find the directory | §3, §6.2 |
| 3 | attr-cache / kernel negative cache served stale "empty" listing | §6.3 |

`stat` (`GetBlobProperties`) and `readdir` (`List Blobs`) hit **different REST APIs**.
Only `readdir` is affected by pagination, markers, and listing caches.

---

## What this lab reproduces

### Scenario 1 — Pagination Truncation (§2.5)

Pure SDK demo, no mount needed. Uploads 15 blobs, then shows what happens when a
consumer stops after the first page of results instead of following all continuation
tokens. A blob missing from the truncated listing is still immediately found by
`GetBlobProperties`.

### Scenario 2 — Mount Caching Staleness (§6.3)

Requires the blobfuse2 mount (handled by `entrypoint.sh`). Nine historical blobs are
uploaded **before** the mount starts. The mount's attr-cache is primed with a 3600 s
TTL. A tenth blob is then written via the Azure SDK (bypassing the mount). A second
`Files.list()` returns the stale 9-entry cache. `Files.isRegularFile()` on the exact
path of the new blob returns `true` — stat bypasses the dir cache.

The fix (§8 design rule #1) is demonstrated inline: probe backwards by exact
deterministic path, never enumerate.

---

## Architecture

The `demo` container:
1. Waits for Azurite to be healthy
2. Runs `java ... setup` — uploads the 9 historical blobs via SDK
3. Mounts the Azurite container via blobfuse2 with a 3600 s cache TTL
4. Runs `java ... demo /mnt/adls` — executes both scenarios
5. Unmounts cleanly

`privileged: true` + `/dev/fuse` device are required for FUSE inside a container.
Docker Desktop on macOS routes `/dev/fuse` through its Linux VM, so this works
without any changes to your macOS security settings.

---

## Running

```bash
docker compose up --build
```

First run takes ~3–4 minutes (Gradle dependency download + blobfuse2 install).
Subsequent runs rebuild only if sources changed.

To watch just the demo output:

```bash
docker compose up --build 2>&1 | grep -v "^azurite"
```

To reset and re-run from scratch:

```bash
docker compose down -v && docker compose up --build
```

---

## Expected output

```
[boot] Waiting for Azurite at azurite:10000 ...
[boot] Azurite is ready.
[boot] Running setup ...
[setup] Uploading 9 blobs to history/ ...
[boot] Mounting container 'demo' via blobfuse2 at /mnt/adls ...
[boot] Mount ready.

=== ADLS Mount Behavior Lab ===

────────────────────────────────────────────────────────────
SCENARIO 1 — Pagination Truncation  (doc §2.5)
────────────────────────────────────────────────────────────

[setup]  15 blobs uploaded to 'pagination-test/'
[broken] 3/15 blobs returned  ← 12 blobs silently dropped
[stat]   pagination-test/20260610.parquet   exists = true
[fixed]  15/15 blobs returned

────────────────────────────────────────────────────────────
SCENARIO 2 — Mount Caching Staleness  (doc §6.3)
────────────────────────────────────────────────────────────

[1] Files.list("/mnt/adls/history")
    → 9 entries  (cache now populated for TTL seconds)
[2] SDK upload: history/20260610.parquet  (bypasses mount)
[3] Files.list("/mnt/adls/history")  [immediately after upload]
    → 9 entries  ← STALE: new file is in storage but mount does not see it
[4] Files.isRegularFile("/mnt/adls/history/20260610.parquet")
    → true  ← stat bypasses dir cache, queries GetBlobProperties directly
[5] FIX — probe backwards by exact deterministic path:
    Found: 20260610.parquet
```

---

## Project layout

```
adls-mount-lab/
├── docker-compose.yml
├── Dockerfile                   Ubuntu 22.04 + blobfuse2 + Java 21 + Gradle
├── entrypoint.sh                mount + run orchestration
├── build.gradle.kts
├── settings.gradle.kts
└── src/main/java/io/github/cynicdog/adls/
    ├── Main.java                setup / demo dispatch; SDK client setup
    ├── PaginationTruncationScenario.java   scenario 1
    └── MountStalenessScenario.java         scenario 2
```
