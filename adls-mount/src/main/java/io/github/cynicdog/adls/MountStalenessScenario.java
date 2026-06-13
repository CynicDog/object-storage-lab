package io.github.cynicdog.adls;

import com.azure.storage.blob.BlobContainerClient;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static io.github.cynicdog.adls.Main.BASE_DATE;
import static io.github.cynicdog.adls.Main.DATE_FMT;

/**
 * Demonstrates doc sec.6.3: mount-layer caches serve stale directory listings
 * long after storage has changed.
 *
 * rclone's --dir-cache-time plays the same role as blobfuse2's attr-cache: once
 * a directory is listed, the result is frozen for TTL seconds. Writes that bypass
 * the mount (via SDK) are invisible to subsequent Files.list() calls.
 *
 * Note on stat vs blobfuse2: in blobfuse2, getattr calls GetBlobProperties
 * directly and can surface a file that readdir misses. rclone's stat also consults
 * the dir cache, so both Files.list() and Files.isRegularFile() return stale
 * results for a file written externally. The SDK check (BlobClient.exists()) is
 * the only call that always reflects storage truth -- and that is the point.
 *
 * Requires an rclone mount at mountPoint with a large --dir-cache-time (set by
 * entrypoint.sh). The 9 history/ blobs must already exist before the mount starts
 * (done by Main's setup mode).
 */
public class MountStalenessScenario {

    private static final String DIR = "history";
    private final Path mountPoint;
    private final BlobContainerClient container;

    public MountStalenessScenario(Path mountPoint, BlobContainerClient container) {
        this.mountPoint = mountPoint;
        this.container  = container;
    }

    public void run() throws Exception {
        banner("SCENARIO 2 - Mount Caching Staleness  (doc sec.6.3)");
        System.out.println("The mount's dir cache freezes directory listings for TTL seconds.");
        System.out.println("Writes via SDK bypass the mount entirely -- the cache is never");
        System.out.println("invalidated. Both Files.list() and Files.isRegularFile() through");
        System.out.println("the mount serve stale results; only the SDK reflects storage truth.\n");

        Path histDir = mountPoint.resolve(DIR);

        List<Path> initial = list(histDir);
        System.out.printf("[1] Files.list(\"%s\")\n", histDir);
        System.out.printf("    -> %d entries  (cache populated for TTL seconds)\n\n", initial.size());

        String newBlob = DIR + "/" + BASE_DATE.format(DATE_FMT) + ".parquet";
        byte[] dummy   = "sequence-data".getBytes();
        container.getBlobClient(newBlob).upload(new ByteArrayInputStream(dummy), dummy.length, true);
        System.out.printf("[2] SDK upload: %s  (bypasses mount -- cache not invalidated)\n\n", newBlob);

        List<Path> stale = list(histDir);
        System.out.printf("[3] Files.list(\"%s\")  [immediately after upload]\n", histDir);
        System.out.printf("    -> %d entries  <- STALE: new file is in storage but mount does not see it\n\n",
                stale.size());

        Path exactPath = mountPoint.resolve(newBlob);
        boolean viaMount = Files.isRegularFile(exactPath);
        System.out.printf("[4] Files.isRegularFile(\"%s\")\n", exactPath);
        System.out.printf("    -> %s  <- mount's dir cache answers stat too; same stale view\n\n", viaMount);

        boolean viaSdk = container.getBlobClient(newBlob).exists();
        System.out.printf("[4b] BlobClient.exists(\"%s\")  [SDK direct, no mount involved]\n", newBlob);
        System.out.printf("     -> %s  <- GetBlobProperties: storage is always consistent\n\n", viaSdk);

        System.out.println("[5] FIX -- probe backwards by exact deterministic path (no readdir):");
        for (int back = 0; back <= 9; back++) {
            Path candidate = mountPoint.resolve(DIR + "/" + BASE_DATE.minusDays(back).format(DATE_FMT) + ".parquet");
            if (Files.isRegularFile(candidate)) {
                System.out.printf("    Found: %s\n", candidate.getFileName());
                System.out.println("    (files visible to the mount since before it started are in its cache;");
                System.out.println("     probing backwards finds the last known-good output without readdir)\n");
                break;
            }
        }

        System.out.println("    Design rule sec.8 #1: if you can compute the key, don't list.");
        System.out.println("    Deterministic naming (yyyyMMdd.parquet) is a feature -- exploit it.\n");
    }

    private static List<Path> list(Path dir) throws Exception {
        try (var stream = Files.list(dir)) {
            return stream.collect(Collectors.toList());
        }
    }

    private static void banner(String title) {
        String line = "-".repeat(60);
        System.out.println(line);
        System.out.println(title);
        System.out.println(line);
        System.out.println();
    }
}
