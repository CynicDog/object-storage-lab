package io.github.cynicdog.adls;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.sas.BlobContainerSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;

import java.io.ByteArrayInputStream;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class Main {

    static final String CONTAINER = "demo";
    static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    static final LocalDate BASE_DATE = LocalDate.of(2026, 6, 10);

    public static void main(String[] args) throws Exception {
        String mode       = args.length > 0 ? args[0] : "demo";
        String mountPoint = args.length > 1 ? args[1] : "";

        String azuriteHost = System.getenv().getOrDefault("AZURITE_HOST", "localhost:10000");
        String connStr = "DefaultEndpointsProtocol=http;" +
                         "AccountName=devstoreaccount1;" +
                         "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;" +
                         "BlobEndpoint=http://" + azuriteHost + "/devstoreaccount1;";

        BlobServiceClient service = new BlobServiceClientBuilder()
                .connectionString(connStr)
                .buildClient();

        BlobContainerClient container = ensureContainer(service);

        if ("setup".equals(mode)) {
            setup(container);
        } else if ("sas".equals(mode)) {
            // Print a container-scoped SAS token that blobfuse2 can use.
            // The Java SDK computes it correctly against Azurite's path-based URLs;
            // blobfuse2 shared-key auth does not.
            BlobContainerSasPermission perm = new BlobContainerSasPermission()
                    .setReadPermission(true)
                    .setWritePermission(true)
                    .setDeletePermission(true)
                    .setListPermission(true)
                    .setCreatePermission(true);
            BlobServiceSasSignatureValues vals = new BlobServiceSasSignatureValues(
                    OffsetDateTime.now(ZoneOffset.UTC).plusHours(2), perm);
            System.out.print(container.generateSas(vals));
        } else {
            System.out.println("=== ADLS Mount Behavior Lab ===");
            System.out.println("Azurite: " + azuriteHost);
            System.out.println();

            new PaginationTruncationScenario(container).run();

            if (!mountPoint.isEmpty()) {
                new MountStalenessScenario(Paths.get(mountPoint), container).run();
            } else {
                System.out.println("[SCENARIO 2 SKIPPED] No mount point given.");
            }

            System.out.println("\n=== Done ===");
        }
    }

    private static BlobContainerClient ensureContainer(BlobServiceClient service) {
        try {
            return service.createBlobContainer(CONTAINER);
        } catch (Exception e) {
            return service.getBlobContainerClient(CONTAINER);
        }
    }

    static void setup(BlobContainerClient container) {
        System.out.println("[setup] Uploading 9 blobs to history/ (simulating prior daily outputs)");
        byte[] dummy = "parquet-placeholder".getBytes();
        for (int i = 9; i >= 1; i--) {
            String name = "history/" + BASE_DATE.minusDays(i).format(DATE_FMT) + ".parquet";
            container.getBlobClient(name).upload(new ByteArrayInputStream(dummy), dummy.length, true);
            System.out.println("  uploaded: " + name);
        }
        System.out.println("[setup] Done.\n");
    }
}
