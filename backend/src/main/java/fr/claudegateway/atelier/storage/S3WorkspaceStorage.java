package fr.claudegateway.atelier.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.AtelierProperties;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Stockage de workspace sur <b>S3</b> (cluster). Sélectionné quand {@code app.atelier.storage=s3}.
 * Credentials via la chaîne par défaut (IRSA) — aucun secret en dur. Confiné ici : le domaine ne
 * dépend que de {@link WorkspaceStorage}.
 */
@Component
@ConditionalOnProperty(prefix = "app.atelier", name = "storage", havingValue = "s3")
public class S3WorkspaceStorage implements WorkspaceStorage {

    private final S3Client s3Client;
    private final String bucket;

    public S3WorkspaceStorage(AtelierProperties properties) {
        this.s3Client = S3Client.builder().build();
        this.bucket = properties.bucket();
    }

    @Override
    public void putFile(String key, byte[] content, String contentType) {
        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                RequestBody.fromBytes(content));
    }

    @Override
    public Optional<byte[]> getFile(String key) {
        try {
            ResponseBytes<?> bytes = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            return Optional.of(bytes.asByteArray());
        } catch (NoSuchKeyException ex) {
            return Optional.empty();
        }
    }

    @Override
    public List<String> listKeys(String prefix) {
        List<String> keys = new ArrayList<>();
        String continuationToken = null;
        do {
            ListObjectsV2Response response = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(prefix).continuationToken(continuationToken).build());
            for (S3Object object : response.contents()) {
                keys.add(object.key());
            }
            continuationToken = Boolean.TRUE.equals(response.isTruncated())
                    ? response.nextContinuationToken() : null;
        } while (continuationToken != null);
        return keys;
    }

    @Override
    public void deleteFile(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public void deletePrefix(String prefix) {
        List<String> keys = listKeys(prefix);
        if (keys.isEmpty()) {
            return;
        }
        List<ObjectIdentifier> ids = keys.stream()
                .map(key -> ObjectIdentifier.builder().key(key).build())
                .toList();
        s3Client.deleteObjects(DeleteObjectsRequest.builder()
                .bucket(bucket)
                .delete(Delete.builder().objects(ids).build())
                .build());
    }
}
