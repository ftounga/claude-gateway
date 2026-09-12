package fr.claudegateway.atelier.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.DeletedObject;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Ce que <b>supprimer un projet volumineux</b> envoie réellement à S3 (F-79 / SF-79-01).
 *
 * <p>Le 2026-09-12, un projet de <b>7 288 objets</b> est devenu indéracinable : {@code deletePrefix}
 * poussait toutes ses clés dans <b>un seul</b> {@code DeleteObjects}, or S3 plafonne cet appel à
 * <b>1 000 clés</b> et refuse alors la requête <b>entière</b> — en se plaignant d'un XML mal formé,
 * ce qui n'aide personne. Les tests ci-dessous dépassent franchement le plafond (2 500 clés) et
 * vérifient <b>le nombre d'appels</b> autant que l'effacement complet.</p>
 *
 * <p>Le client S3 est bouchonné : aucun réseau, aucun credential, aucun bucket réel.</p>
 */
class S3WorkspaceStorageDeletePrefixTest {

    private static final String PREFIX = "atelier/u-1/w-1/";
    private static final String OTHER_PREFIX = "atelier/u-2/w-9/";
    private static final int KEY_COUNT = 2500;

    private final FakeS3Client s3 = new FakeS3Client();
    private final S3WorkspaceStorage storage = new S3WorkspaceStorage(s3, "bucket-test");

    private void seed(int count) {
        for (int i = 0; i < count; i++) {
            s3.keys.add(PREFIX + String.format("f-%05d.txt", i));
        }
    }

    @Test
    void deletePrefixSplitsIntoBatchesOfAtMostOneThousandAndErasesEverything() {
        seed(KEY_COUNT);

        storage.deletePrefix(PREFIX);

        // 2 500 clés = 3 appels, jamais un seul de 2 500 : c'est exactement ce que S3 refusait.
        assertThat(s3.deletedBatches).hasSize(3);
        assertThat(s3.deletedBatches).extracting(List::size).containsExactly(1000, 1000, 500);
        assertThat(s3.deletedBatches)
                .allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(1000));
        assertThat(s3.keys).isEmpty();
    }

    @Test
    void listKeysFollowsTheContinuationTokenToTheLastPage() {
        seed(KEY_COUNT);

        List<String> keys = storage.listKeys(PREFIX);

        // ListObjectsV2 ne rend que 1 000 clés par page : sans le jeton de continuation, un projet de
        // plus de 1 000 fichiers ne serait ni listé, ni exporté, ni supprimé en entier.
        assertThat(keys).hasSize(KEY_COUNT);
        assertThat(s3.listCalls).isEqualTo(3);
    }

    @Test
    void deletePrefixIgnoresKeysOutsideThePrefix() {
        seed(KEY_COUNT);
        s3.keys.add(OTHER_PREFIX + "secret.txt");

        storage.deletePrefix(PREFIX);

        // Isolation : le découpage en lots ne fait que répartir des clés déjà filtrées par le préfixe.
        assertThat(s3.keys).containsExactly(OTHER_PREFIX + "secret.txt");
        assertThat(s3.deletedBatches).flatExtracting(batch -> batch)
                .allSatisfy(key -> assertThat(key).startsWith(PREFIX));
    }

    @Test
    void deletePrefixIssuesNoDeleteCallWhenNothingIsStored() {
        storage.deletePrefix(PREFIX);

        assertThat(s3.deletedBatches).isEmpty();
    }

    @Test
    void perKeyErrorsAreReportedOnceEveryBatchHasBeenTried() {
        seed(KEY_COUNT);
        // Trois clés du 2e lot résistent (verrou, droit refusé) : S3 les rend dans sa RÉPONSE, sans
        // lever d'exception — c'est précisément ce que le code ignorait en silence.
        s3.perKeyErrors = Map.of(1, List.of(
                PREFIX + "f-01000.txt", PREFIX + "f-01001.txt", PREFIX + "f-01002.txt"));

        assertThatThrownBy(() -> storage.deletePrefix(PREFIX))
                .isInstanceOf(WorkspaceStorageDeletionException.class)
                .satisfies(thrown -> {
                    WorkspaceStorageDeletionException ex = (WorkspaceStorageDeletionException) thrown;
                    assertThat(ex.deletedCount()).isEqualTo(KEY_COUNT - 3);
                    assertThat(ex.remainingCount()).isEqualTo(3);
                })
                .hasMessageContaining("2497")
                .hasMessageContaining("3 restant");

        // Un refus par clé est local : les lots suivants partent quand même.
        assertThat(s3.deletedBatches).hasSize(3);
        assertThat(s3.keys).hasSize(3);
    }

    @Test
    void aStorageExceptionStopsTheLoopAndStillSaysWhatWasErased() {
        seed(KEY_COUNT);
        s3.throwOnBatch = 1; // droits, réseau, bucket : insister enverrait des requêtes pour rien.

        assertThatThrownBy(() -> storage.deletePrefix(PREFIX))
                .isInstanceOf(WorkspaceStorageDeletionException.class)
                .satisfies(thrown -> {
                    WorkspaceStorageDeletionException ex = (WorkspaceStorageDeletionException) thrown;
                    assertThat(ex.deletedCount()).isEqualTo(1000);
                    assertThat(ex.remainingCount()).isEqualTo(1500);
                    assertThat(ex.getCause()).isInstanceOf(SdkException.class);
                });

        // Deux lots tentés, le 2e refusé par le stockage : le 3e n'est jamais parti.
        assertThat(s3.deleteCalls).isEqualTo(2);
        assertThat(s3.deletedBatches).hasSize(1);
        assertThat(s3.keys).hasSize(1500);
    }

    /**
     * Bouchon de stockage : pagine le listage par 1 000 comme S3, enregistre chaque lot de
     * suppression, et sait rendre des erreurs par clé ou lever sur un lot donné.
     */
    private static final class FakeS3Client implements S3Client {

        private static final int PAGE_SIZE = 1000;

        private final NavigableSet<String> keys = new TreeSet<>();
        private final List<List<String>> deletedBatches = new ArrayList<>();
        private int listCalls;
        private int deleteCalls;
        private Map<Integer, List<String>> perKeyErrors = Map.of();
        private Integer throwOnBatch;

        @Override
        public ListObjectsV2Response listObjectsV2(ListObjectsV2Request request) {
            listCalls++;
            NavigableSet<String> matching = new TreeSet<>();
            for (String key : keys) {
                if (key.startsWith(request.prefix())) {
                    matching.add(key);
                }
            }
            NavigableSet<String> page = request.continuationToken() == null
                    ? matching : matching.tailSet(request.continuationToken(), false);
            List<S3Object> contents = new ArrayList<>();
            String last = null;
            for (String key : page) {
                if (contents.size() == PAGE_SIZE) {
                    break;
                }
                contents.add(S3Object.builder().key(key).build());
                last = key;
            }
            boolean truncated = last != null && !page.tailSet(last, false).isEmpty();
            return ListObjectsV2Response.builder()
                    .contents(contents)
                    .isTruncated(truncated)
                    .nextContinuationToken(truncated ? last : null)
                    .build();
        }

        @Override
        public DeleteObjectsResponse deleteObjects(DeleteObjectsRequest request) {
            int batchIndex = deleteCalls++;
            if (throwOnBatch != null && throwOnBatch == batchIndex) {
                throw SdkException.builder().message("Accès refusé au bucket").build();
            }
            List<String> requested = request.delete().objects().stream()
                    .map(ObjectIdentifier::key)
                    .toList();
            deletedBatches.add(requested);
            List<String> failing = perKeyErrors.getOrDefault(batchIndex, List.of());
            List<DeletedObject> deleted = new ArrayList<>();
            List<S3Error> errors = new ArrayList<>();
            for (String key : requested) {
                if (failing.contains(key)) {
                    errors.add(S3Error.builder().key(key).code("AccessDenied").build());
                } else {
                    keys.remove(key);
                    deleted.add(DeletedObject.builder().key(key).build());
                }
            }
            return DeleteObjectsResponse.builder().deleted(deleted).errors(errors).build();
        }

        @Override
        public String serviceName() {
            return S3Client.SERVICE_NAME;
        }

        @Override
        public void close() {
            // Rien à fermer : aucun canal réseau.
        }
    }
}
