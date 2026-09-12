package fr.claudegateway.atelier.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.AtelierProperties;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Stockage de workspace sur <b>S3</b> (cluster). Sélectionné quand {@code app.atelier.storage=s3}.
 * Credentials via la chaîne par défaut (IRSA) — aucun secret en dur. Confiné ici : le domaine ne
 * dépend que de {@link WorkspaceStorage}.
 */
@Component
@ConditionalOnProperty(prefix = "app.atelier", name = "storage", havingValue = "s3")
public class S3WorkspaceStorage implements WorkspaceStorage {

    private static final Logger log = LoggerFactory.getLogger(S3WorkspaceStorage.class);

    /**
     * Plafond dur de S3 sur {@code DeleteObjects} : <b>1 000 clés par appel</b>. Ce n'est pas un
     * réglage — au-delà, S3 refuse la requête <b>entière</b>, avec un message qui ne parle même pas
     * du plafond (« The XML you provided was not well-formed »). C'est exactement ce qui rendait un
     * projet de 7 288 objets indéracinable le 2026-09-12 (F-79).
     */
    static final int DELETE_BATCH_SIZE = 1000;

    /** Nombre de clés en échec citées dans le journal — un échantillon, pas un déversement. */
    private static final int FAILED_KEYS_LOGGED = 5;

    private final S3Client s3Client;
    private final String bucket;

    /**
     * Constructeur de production. L'annotation n'est <b>pas</b> décorative : depuis que SF-79-01 a
     * ajouté le constructeur d'injection ci-dessous, la classe en a <b>deux</b>. Spring ne choisit
     * tout seul que lorsqu'il n'y en a qu'un ; avec deux, il cherche un constructeur <b>sans
     * argument</b>, n'en trouve pas, et le contexte entier échoue au démarrage —
     * {@code NoSuchMethodException: S3WorkspaceStorage.<init>()}. Le 2026-09-12, cela a empêché
     * tout démarrage du backend en production ; seule la bascule progressive de Kubernetes, qui a
     * gardé l'ancien pod, a évité la panne.
     *
     * <p>Aucun test ne l'avait vu : les tests d'intégration utilisent le stockage en mémoire, et ce
     * bean n'est donc jamais construit par Spring — il ne l'est qu'en production.</p>
     */
    @Autowired
    public S3WorkspaceStorage(AtelierProperties properties) {
        this(S3Client.builder().build(), properties.bucket());
    }

    /** Visible pour les tests : injecte un client bouchonné, sans réseau ni credentials. */
    S3WorkspaceStorage(S3Client s3Client, String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
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

    /**
     * Liste <b>toutes</b> les clés du préfixe. {@code ListObjectsV2} n'en rend que 1 000 par page :
     * la boucle suit le jeton de continuation tant que la réponse est tronquée. Sans elle, un projet
     * de plus de 1 000 fichiers ne serait ni listé, ni exporté, ni supprimé en entier.
     */
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

    /**
     * Supprime toutes les clés du préfixe, <b>par lots de {@value #DELETE_BATCH_SIZE} au plus</b>, et
     * ne rend la main qu'une fois le dernier lot traité (F-79 / SF-79-01).
     *
     * <p>Deux façons d'échouer, traitées différemment :</p>
     * <ul>
     *   <li><b>erreurs par clé</b> — {@code DeleteObjects} les rend dans sa <i>réponse</i>, sans
     *       lever d'exception (elles étaient jusqu'ici ignorées en silence). Elles sont locales à
     *       une clé : les lots suivants sont donc <b>quand même</b> envoyés, puis les échecs sont
     *       rendus ensemble ;</li>
     *   <li><b>exception sur un lot</b> — droits, réseau, bucket : c'est systémique. On
     *       <b>arrête</b>, parce qu'insister enverrait N requêtes vouées au même sort.</li>
     * </ul>
     *
     * <p>Dans les deux cas une {@link WorkspaceStorageDeletionException} est levée, portant le
     * nombre de clés effacées et le nombre restantes. Les clés en échec ne sortent que dans le
     * journal serveur, échantillonnées — jamais vers le client.</p>
     */
    @Override
    public void deletePrefix(String prefix) {
        List<String> keys = listKeys(prefix);
        if (keys.isEmpty()) {
            return;
        }
        int deleted = 0;
        List<String> failedKeys = new ArrayList<>();
        SdkException fatal = null;

        for (int from = 0; from < keys.size(); from += DELETE_BATCH_SIZE) {
            List<String> batch = keys.subList(from, Math.min(from + DELETE_BATCH_SIZE, keys.size()));
            try {
                DeleteObjectsResponse response = deleteBatch(batch);
                deleted += batch.size() - response.errors().size();
                for (S3Error error : response.errors()) {
                    failedKeys.add(error.key() + " (" + error.code() + ")");
                }
            } catch (SdkException ex) {
                fatal = ex;
                break;
            }
        }

        if (fatal == null && failedKeys.isEmpty()) {
            return;
        }
        int remaining = keys.size() - deleted;
        log.warn("Suppression du préfixe de stockage incomplète : {} clé(s) effacée(s) sur {}, {}"
                        + " restante(s). Premières clés en échec : {}{}",
                deleted, keys.size(), remaining,
                failedKeys.subList(0, Math.min(FAILED_KEYS_LOGGED, failedKeys.size())),
                fatal == null ? "" : " — interrompu par une erreur de stockage",
                fatal);
        throw new WorkspaceStorageDeletionException(deleted, remaining, fatal);
    }

    private DeleteObjectsResponse deleteBatch(List<String> batch) {
        List<ObjectIdentifier> ids = batch.stream()
                .map(key -> ObjectIdentifier.builder().key(key).build())
                .toList();
        return s3Client.deleteObjects(DeleteObjectsRequest.builder()
                .bucket(bucket)
                .delete(Delete.builder().objects(ids).build())
                .build());
    }
}
