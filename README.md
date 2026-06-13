# object-storage-lab

Containerized labs for reproducing and understanding the impedance mismatch between
object storage (Azure Blob / ADLS) and POSIX filesystem semantics.

Each sub-directory is a self-contained lab with its own `docker-compose.yml`.

| Lab | What it reproduces |
|---|---|
| [`adls-mount/`](adls-mount/) | `Files.list()` vs `Files.isRegularFile()` divergence on a blobfuse2 mount — the production incident where a stale listing cache caused a sequence counter to reset |

Reference: [tech-debt / cloud-storage-and-mount-drivers](https://github.com/CynicDog/tech-debt/blob/main/systems/cloud-storage-and-mount-drivers.md)
