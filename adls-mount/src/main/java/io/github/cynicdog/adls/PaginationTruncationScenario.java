package io.github.cynicdog.adls;

import com.azure.core.http.rest.PagedIterable;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.ListBlobsOptions;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static io.github.cynicdog.adls.Main.BASE_DATE;
import static io.github.cynicdog.adls.Main.DATE_FMT;

/**
 * Demonstrates §2.5: List Blobs is paginated. A layer that stops at page 1
 * without following the continuation token silently returns truncated results —
 * while GetBlobProperties still resolves every blob by exact key.
 *
 * This is the SDK-level analogue of what blobfuse2 does internally when its
 * pagination handling is broken: readdir truncates, stat still works.
 */
public class PaginationTruncationScenario {

    private static final String PREFIX = "pagination-test/";
    private static final int BLOB_COUNT = 15;
    private static final int PAGE_SIZE  = 3;  // stop after this many to simulate the bug

    private final BlobContainerClient container;

    public PaginationTruncationScenario(BlobContainerClient container) {
        this.container = container;
    }

    public void run() {
        banner("SCENARIO 1 — Pagination Truncation  (doc §2.5)");
        System.out.println("List Blobs is paginated. A layer that stops at page 1 without");
        System.out.println("following the continuation token silently drops the rest.");
        System.out.println("readdir (ListBlobs) and stat (GetBlobProperties) use different");
        System.out.println("code paths — they can disagree on what 'exists'.\n");

        uploadBlobs();

        listBroken();
        statMissingBlob();
        listFixed();

        System.out.println();
    }

    private void uploadBlobs() {
        byte[] dummy = "parquet-placeholder".getBytes();
        System.out.printf("[setup] Uploading %d blobs to '%s'\n", BLOB_COUNT, PREFIX);
        for (int i = 1; i <= BLOB_COUNT; i++) {
            String name = PREFIX + BASE_DATE.minusDays(BLOB_COUNT - i).format(DATE_FMT) + ".parquet";
            container.getBlobClient(name).upload(new ByteArrayInputStream(dummy), dummy.length, true);
        }
        System.out.printf("        %d blobs now in storage.\n\n", BLOB_COUNT);
    }

    private void listBroken() {
        System.out.printf("[broken] List '%s' — stop after first page of %d (simulates broken pagination):\n",
                PREFIX, PAGE_SIZE);

        ListBlobsOptions opts = new ListBlobsOptions().setPrefix(PREFIX).setMaxResultsPerPage(PAGE_SIZE);
        PagedIterable<BlobItem> paged = container.listBlobs(opts, null);

        List<BlobItem> firstPage = new ArrayList<>();
        paged.iterableByPage().iterator().next().getValue().forEach(firstPage::add);

        System.out.printf("         %d/%d blobs returned  ← %d blobs silently dropped\n\n",
                firstPage.size(), BLOB_COUNT, BLOB_COUNT - firstPage.size());
    }

    private void statMissingBlob() {
        // Pick a blob that wouldn't appear on the first page
        String invisible = PREFIX + BASE_DATE.format(DATE_FMT) + ".parquet";
        boolean exists = container.getBlobClient(invisible).exists();
        System.out.println("[stat]   GetBlobProperties on a blob absent from the truncated listing:");
        System.out.printf("         %-55s exists = %s\n\n", invisible, exists);
        System.out.println("         → stat bypasses listing entirely, resolves by exact key.");
        System.out.println("           This is what Files.isRegularFile() does through a blobfuse2 mount.\n");
    }

    private void listFixed() {
        System.out.printf("[fixed]  List '%s' — follow all continuation tokens:\n", PREFIX);

        ListBlobsOptions opts = new ListBlobsOptions().setPrefix(PREFIX).setMaxResultsPerPage(PAGE_SIZE);
        long count = container.listBlobs(opts, null).stream().count();

        System.out.printf("         %d/%d blobs returned  ← all visible when pagination is correct\n",
                count, BLOB_COUNT);
    }

    private static void banner(String title) {
        String line = "─".repeat(60);
        System.out.println(line);
        System.out.println(title);
        System.out.println(line);
        System.out.println();
    }
}
