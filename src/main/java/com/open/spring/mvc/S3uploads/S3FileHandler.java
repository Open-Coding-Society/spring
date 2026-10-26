package com.open.spring.mvc.S3uploads;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

// TODO: Change the BUCKET NAME from pages-assignments to something else

@Service
@Slf4j
// @ConditionalOnProperty(name = "file.storage-type", havingValue = "prod")
public class S3FileHandler implements FileHandler {

    @Value("${aws.s3.bucket-name}")
    private String bucketName;

    @Value("${aws.s3.access-key-id}")
    private String accessKey;

    @Value("${aws.s3.secret-access-key}")
    private String secretKey;

    @Value("${aws.s3.region}")
    private String region;

    /**
     * Where the local fallback keeps its objects when S3 is not configured.
     * See {@link #init()} for why that fallback exists.
     */
    @Value("${file.local-root:volumes/s3-local}")
    private String localRoot;

    private S3Client s3Client;

    @PostConstruct
    public void init() {
        if (isBlank(accessKey) || isBlank(secretKey) || isBlank(region) || isBlank(bucketName)) {
            // Fall back to the filesystem instead of doing nothing. Group chat, group
            // files and chat images all persist through this handler, so with no S3 and
            // no fallback a dev machine accepts a message, returns 200 and silently
            // drops it -- the group chat looks broken rather than unconfigured.
            // Production sets the AWS properties and never takes this path.
            log.warn("S3 is not configured; storing files on disk under '{}' instead. "
                    + "Set aws.s3.* to use S3.", localRoot);
            return;
        }

        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey.trim(), secretKey.trim());

        this.s3Client = S3Client.builder()
                .region(Region.of(region.trim()))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .build();
    }

    @Override
    public String uploadFile(String base64Data, String filename, String uid) {
        if (s3Client == null) {
            return localUpload(base64Data, filename, uid);
        }
        String key = generateKey(uid, filename);
        System.out.println("S3 Upload: " + key);

        try {
            byte[] fileData = Base64.getDecoder().decode(base64Data);

            PutObjectRequest putOb = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            s3Client.putObject(putOb, RequestBody.fromBytes(fileData));

            return filename;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public String decodeFile(String uid, String filename) {
        if (s3Client == null) {
            return localDecode(uid, filename);
        }
        String key = generateKey(uid, filename);
        System.out.println("S3 Download: " + key);

        try {
            GetObjectRequest getOb = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(getOb);
            byte[] data = objectBytes.asByteArray();

            return Base64.getEncoder().encodeToString(data);
        } catch (Exception e) {
            System.err.println("S3 Download Error for key: " + key);
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public boolean deleteFiles(String uid) {
        if (s3Client == null) {
            return localDeleteAll(uid);
        }
        String prefix = uid + "/";
        System.out.println("S3 Delete Prefix: " + prefix);

        try {
            // 1. List objects to delete
            ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(prefix)
                    .build();

            ListObjectsV2Response listRes = s3Client.listObjectsV2(listReq);

            if (listRes.contents().isEmpty()) {
                return true;
            }

            // 2. Prepare batch delete
            List<ObjectIdentifier> objectsToDelete = listRes.contents().stream()
                    .map(s3Object -> ObjectIdentifier.builder().key(s3Object.key()).build())
                    .collect(Collectors.toList());

            DeleteObjectsRequest deleteReq = DeleteObjectsRequest.builder()
                    .bucket(bucketName)
                    .delete(Delete.builder().objects(objectsToDelete).build())
                    .build();

            s3Client.deleteObjects(deleteReq);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public List<String> listFiles(String prefix) {
        if (s3Client == null) {
            return localList(prefix);
        }

        try {
            ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(prefix)
                    .build();

            ListObjectsV2Response listRes = s3Client.listObjectsV2(listReq);

            return listRes.contents().stream()
                    .map(s3Object -> s3Object.key())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    @Override
    public boolean fileExists(String uid, String filename) {
        if (s3Client == null) {
            Path target = localPathFor(generateKey(uid, filename));
            return target != null && Files.exists(target);
        }
        String key = generateKey(uid, filename);
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String generateKey(String uid, String filename) {
        return uid + "/" + filename;
    }

    // ---------------------------------------------------------------------------
    // Local filesystem fallback, used only when S3 is not configured.
    //
    // The object key maps straight onto a path under localRoot, so the layout on disk
    // mirrors the bucket and the two implementations stay easy to compare. A key
    // ending in "/" is S3's zero-byte folder placeholder (GroupChatService writes one
    // for shared-files/), and becomes a real directory here.
    // ---------------------------------------------------------------------------

    /**
     * Resolves an object key under {@link #localRoot}, or null if it escapes that
     * directory. Keys reach this from request bodies, so "../" must not be able to
     * read or overwrite files elsewhere on the machine.
     */
    private Path localPathFor(String key) {
        Path root = Paths.get(localRoot).toAbsolutePath().normalize();
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            log.warn("Rejected file key that escapes the local storage root: {}", key);
            return null;
        }
        return resolved;
    }

    private String localUpload(String base64Data, String filename, String uid) {
        String key = generateKey(uid, filename);
        Path target = localPathFor(key);
        if (target == null) return null;

        try {
            if (key.endsWith("/")) {
                Files.createDirectories(target);
                return filename;
            }
            Files.createDirectories(target.getParent());
            Files.write(target, Base64.getDecoder().decode(base64Data));
            return filename;
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Local file write failed for key {}", key, e);
            return null;
        }
    }

    private String localDecode(String uid, String filename) {
        Path source = localPathFor(generateKey(uid, filename));
        if (source == null || !Files.isRegularFile(source)) return null;
        try {
            return Base64.getEncoder().encodeToString(Files.readAllBytes(source));
        } catch (IOException e) {
            log.warn("Local file read failed for {}", source, e);
            return null;
        }
    }

    private boolean localDeleteAll(String uid) {
        Path dir = localPathFor(uid + "/");
        if (dir == null) return false;
        if (!Files.exists(dir)) return true;
        // Deepest first, so a directory is only removed once it is empty.
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    log.warn("Could not delete {}", path, e);
                }
            });
            return true;
        } catch (IOException e) {
            log.warn("Local delete failed for {}", dir, e);
            return false;
        }
    }

    /** Returns keys, not paths, so callers can strip a prefix exactly as they do for S3. */
    private List<String> localList(String prefix) {
        Path root = Paths.get(localRoot).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    // Always forward slashes: these are object keys, and on Windows
                    // relativize() would otherwise hand back backslashes and no prefix
                    // would ever match.
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .filter(key -> prefix == null || key.startsWith(prefix))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Local list failed under {}", root, e);
            return new ArrayList<>();
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
