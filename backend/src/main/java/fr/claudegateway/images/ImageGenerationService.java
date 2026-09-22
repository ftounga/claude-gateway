package fr.claudegateway.images;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>La logique des images générées</b> (F-142 / SF-142-04) : borner, relayer le fournisseur, ranger le
 * PNG dans le stockage objet, tenir l'artefact et son statut, servir et supprimer — toujours sous
 * l'isolation {@code user_id}.
 *
 * <h2>Gateway-First / Provider-First</h2>
 * <p>La gateway <b>relaie</b> un fournisseur d'images ({@link ImageProvider}) — le calcul lourd est chez
 * lui — puis range le résultat. Elle n'implémente aucun moteur d'images.</p>
 *
 * <h2>Async borné, statut lisible (règle CLAUDE.md)</h2>
 * <p>L'appel au fournisseur est un appel réseau sortant <b>borné par timeout</b> ({@link ImageProvider}
 * / {@link ImageGenerationProperties#timeout()}), exécuté dans le tour diffusé, <b>hors thread de requête
 * HTTP</b>, jamais dans une transaction longue (patron {@code IngestionService}). Chaque génération est
 * persistée avec un <b>statut lisible</b> : {@code PENDING} avant l'appel, puis {@code READY} ou
 * {@code FAILED}. La ligne {@code PENDING} est commitée <b>avant</b> l'appel ; l'issue est écrite dans une
 * transaction distincte, pour qu'un échec n'efface pas la trace.</p>
 */
@Service
public class ImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationService.class);

    private final GeneratedImageRepository images;
    private final GeneratedImageStore store;
    private final ImageProvider provider;
    private final ImageGenerationProperties properties;

    public ImageGenerationService(GeneratedImageRepository images, GeneratedImageStore store,
            ImageProvider provider, ImageGenerationProperties properties) {
        this.images = images;
        this.store = store;
        this.provider = provider;
        this.properties = properties;
    }

    /**
     * Génère une image décorative : borne, persiste, relaie le fournisseur, range le PNG, enregistre le
     * coût. La ligne reste {@code FAILED} et l'exception est relayée si le fournisseur échoue.
     *
     * @param place  lieu de rangement (propriétaire compris)
     * @param prompt la description (donnée de l'utilisateur)
     * @param size   la taille (liste blanche)
     * @return l'image rangée (READY) et ses octets, pour dépôt dans le projet
     * @throws ImageRejectedException            si le prompt est vide ou trop long, ou l'image trop lourde
     * @throws ImageQuotaExceededException       si le compte a atteint sa borne
     * @throws ImageProviderUnavailableException si le fournisseur n'est pas configuré (aucun appel)
     * @throws ImageProviderException            si l'appel au fournisseur échoue (ligne FAILED)
     */
    public Generated generate(ImagePlace place, String prompt, ImageSize size) {
        String cleanPrompt = prompt == null ? "" : prompt.strip();
        if (cleanPrompt.isEmpty()) {
            throw new ImageRejectedException("La description de l'image est vide.");
        }
        if (cleanPrompt.length() > properties.maxPromptChars()) {
            throw new ImageRejectedException("Description trop longue : " + properties.maxPromptChars()
                    + " caractères au plus.");
        }
        if (!properties.isConfigured()) {
            // Aucun appel, aucune trace : le tuyau est fermé tant que le secret n'est pas posé.
            throw new ImageProviderUnavailableException(
                    "Génération d'images non configurée : aucun fournisseur d'images n'est paramétré.");
        }
        long ready = images.countByUserIdAndStatus(place.userId(), GeneratedImageStatus.READY);
        if (ready >= properties.maxAccountImages()) {
            throw new ImageQuotaExceededException(properties.maxAccountImages());
        }

        ImageSize effective = size == null ? ImageSize.SQUARE : size;
        // La ligne PENDING, commitée AVANT l'appel : le statut est visible pendant la génération.
        GeneratedImage pending = savePending(place, cleanPrompt, effective);

        ImageProvider.GeneratedImageData data;
        try {
            data = provider.generate(cleanPrompt, effective);
        } catch (RuntimeException e) {
            fail(pending.getId(), place.userId(), e.getMessage());
            throw e;
        }
        if (data.bytes() == null || data.bytes().length == 0) {
            fail(pending.getId(), place.userId(), "Le fournisseur d'images a renvoyé une image vide.");
            throw new ImageProviderException("Le fournisseur d'images a renvoyé une image vide.");
        }
        if (data.bytes().length > properties.maxImageBytes()) {
            fail(pending.getId(), place.userId(), "Image trop volumineuse.");
            throw new ImageRejectedException("Image générée trop volumineuse : "
                    + (properties.maxImageBytes() / (1024 * 1024)) + " Mo au plus.");
        }

        store.putImage(place.userId(), pending.getId(), data.bytes());
        GeneratedImage saved = markReady(pending.getId(), place.userId(), data.bytes().length);
        // Journal de coût : jamais le prompt ni la clé — l'identifiant, la taille, le coût.
        log.info("Image générée (SF-142-04) user={} image={} size={} bytes={} costEUR={}",
                place.userId(), saved.getId(), saved.getSize(), saved.getImageBytes(), saved.getCostEur());
        return new Generated(saved, data.bytes());
    }

    // Ni PENDING, ni READY, ni FAILED ne partagent une transaction longue : chaque écriture est commitée
    // par la transaction propre du repository Spring Data (patron IngestionService). L'appel réseau au
    // fournisseur, lent, ne tient donc jamais une transaction ouverte, et l'échec ne perd pas la trace.
    private GeneratedImage savePending(ImagePlace place, String prompt, ImageSize size) {
        return images.save(GeneratedImage.builder()
                .userId(place.userId())
                .space(place.space())
                .hostId(place.hostId())
                .workspaceId(place.workspaceId())
                .prompt(prompt.length() > GeneratedImage.MAX_PROMPT_LENGTH
                        ? prompt.substring(0, GeneratedImage.MAX_PROMPT_LENGTH) : prompt)
                .size(size.api())
                .status(GeneratedImageStatus.PENDING)
                .build());
    }

    private GeneratedImage markReady(UUID imageId, UUID userId, long bytes) {
        GeneratedImage image = images.findByIdAndUserId(imageId, userId)
                .orElseThrow(ImageNotFoundException::new);
        image.setStatus(GeneratedImageStatus.READY);
        image.setImageKey(GeneratedImageStore.imageKey(userId, imageId));
        image.setImageBytes(bytes);
        image.setCostEur(properties.costEurPerImage());
        image.setError(null);
        return images.save(image);
    }

    private void fail(UUID imageId, UUID userId, String reason) {
        images.findByIdAndUserId(imageId, userId).ifPresent(image -> {
            image.setStatus(GeneratedImageStatus.FAILED);
            image.setError(reason == null || reason.isBlank() ? "Échec de la génération." : shorten(reason));
            images.save(image);
        });
    }

    /** Les images d'un lieu, la plus récente d'abord. */
    @Transactional(readOnly = true)
    public List<GeneratedImage> list(UUID userId, UUID hostId, ImageSpace space) {
        return images.findByUserIdAndHostIdAndSpaceOrderByCreatedAtDesc(userId, hostId, space);
    }

    /** Une image, scellée par le propriétaire. */
    @Transactional(readOnly = true)
    public GeneratedImage get(UUID userId, UUID imageId) {
        return images.findByIdAndUserId(imageId, userId).orElseThrow(ImageNotFoundException::new);
    }

    /** Les octets du PNG d'une image, scellés par le propriétaire. */
    @Transactional(readOnly = true)
    public byte[] bytes(UUID userId, UUID imageId) {
        GeneratedImage image = get(userId, imageId);
        return store.image(userId, image.getId()).orElseThrow(ImageNotFoundException::new);
    }

    /** Supprime une image et son contenu objet. */
    @Transactional
    public void delete(UUID userId, UUID imageId) {
        GeneratedImage image = images.findByIdAndUserId(imageId, userId)
                .orElseThrow(ImageNotFoundException::new);
        images.delete(image);
        store.deleteImage(userId, image.getId());
    }

    private static String shorten(String reason) {
        return reason.length() > 500 ? reason.substring(0, 500) : reason;
    }

    /**
     * Le résultat d'une génération : l'image rangée et ses octets (pour dépôt dans le projet).
     *
     * @param image l'entité READY
     * @param bytes les octets du PNG
     */
    public record Generated(GeneratedImage image, byte[] bytes) {
    }
}
