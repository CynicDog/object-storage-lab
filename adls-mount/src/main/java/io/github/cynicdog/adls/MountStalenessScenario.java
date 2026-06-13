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
 * Demonstrates §6.3: blobfuse2's attr-cache + kernel negative/entry caches
 * serve stale directory listings long after storage has changed.
 *
 * Because readdir (ListBlobs) and stat (GetBlobProperties) are separate code
 * paths, a file written externally is invisible to Files.list() but immediately
 * visible to Files.isRegularFile() — exactly the production incident.
 *
 * Requires a blobfuse2 mount at the given mountPoint with a large cache TTL
 * (set by entrypoint.sh). The 9 "history/" blobs must already exist in storage
 * before the mount was established (done by Main's setup mode).
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
        banner("SCENARIO 2 — Mount Caching Staleness  (doc §6.3)");
        System.out.println("blobfuse2's attr-cache caches readdir results for TTL seconds.");
        System.out.println("Writing via SDK bypasses the mount, so the cache is never invalidated.");
        System.out.println("stat (GetBlobProperties) and readdir (ListBlobs) use different REST APIs");
        System.out.println("— they can disagree on the same directory at the same instant.\n");

        Path histDir = mountPoint.resolve(DIR);

        // Step 1: initial listing — populates attr-cache with 9 entries
        List<Path> initial = list(histDir);
        System.out.printf("[1] Files.list(\"%s\")\n", histDir);
        System.out.printf("    → %d entries  (cache now populated for TTL seconds)\n\n", initial.size());

        // Step 2: write a new blob via SDK, entirely bypassing the mount
        String newBlob = DIR + "/" + BASE_DATE.format(DATE_FMT) + ".parquet";
        byte[] dummy   = "sequence-data".getBytes();
        container.getBlobClient(newBlob).upload(new ByteArrayInputStream(dummy), dummy.length, true);
        System.out.printf("[2] SDK upload:  %s  (bypasses mount — cache not invalidated)\n\n", newBlob);

        // Step 3: list again immediately — cache says "9 entries", new file invisible
        List<Path> stale = list(histDir);
        System.out.printf("[3] Files.list(\"%s\")  [immediately after upload]\n", histDir);
        System.out.printf("    → %d entries  ← STALE: new file is in storage but mount does not see it\n\n",
                stale.size());

        // Step 4: stat the exact path — bypasses dir cache, hits GetBlobProperties
        Path exactPath = mountPoint.resolve(newBlob);
        boolean exists = Files.isRegularFile(exactPath);
        System.out.printf("[4] Files.isRegularFile(\"%s\")\n", exactPath);
        System.out.printf("    → %s  ← stat bypasses dir cache, queries GetBlobProperties directly\n\n",
                exists);

        // Step 5: show the production fix
        System.out.println("[5] FIX — probe backwards by exact deterministic path (no readdir at all):");
        for (int back = 0; back <= 9; back++) {
            Path candidate = mountPoint.resolve(DIR + "/" + BASE_DATE.minusDays(back).format(DATE_FMT) + ".parquet");
            if (Files.isRegularFile(candidate)) {
                System.out.printf("    Found: %s\n", candidate.getFileName());
                System.out.println("    (Each probe is a point-stat — robust against all three enumeration failure modes)");
                break;
            }
        }

        System.out.println("\n    Design rule §8 #1: if you can compute the key, don't list.");
        System.out.println("    Deterministic naming (yyyyMMdd.parquet) is a feature — exploit it.\n");
    }

    private static List<Path> list(Path dir) throws Exception {
        try (var stream = Files.list(dir)) {
            return stream.collect(Collectors.toList());
        }
    }

    private static void banner(String title) {
        String line = "─".repeat(60);
        System.out.println(line);
        System.out.println(title);
        System.out.println(line);
        System.out.println();
    }
}
