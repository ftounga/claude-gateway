package fr.claudegateway.presentations;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>La logique des présentations</b> (F-129 / SF-129-02) : valider, ranger le {@code .pptx} dans le
 * stockage objet, tenir l'artefact, servir et supprimer — toujours sous l'isolation {@code user_id}.
 *
 * <p>Gateway-First : le backend range et sert un fichier produit ailleurs (le terminal). Aucun moteur
 * IA, aucune analyse du contenu du {@code .pptx} ici.</p>
 */
@Service
public class PresentationService {

    /** L'en-tête d'une archive ZIP (un .pptx en est une) : {@code PK\x03\x04}. */
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};

    private final PresentationRepository presentations;
    private final PresentationStore store;
    private final PresentationLimits limits;

    public PresentationService(PresentationRepository presentations, PresentationStore store,
            PresentationLimits limits) {
        this.presentations = presentations;
        this.store = store;
        this.limits = limits;
    }

    /**
     * Publie (ou republie) une présentation.
     *
     * @param place            lieu de rangement (utilisateur, espace, poste, projet)
     * @param presentationId   présentation à remplacer, ou {@code null} pour une nouvelle
     * @param title            titre court
     * @param description      description d'une phrase, ou {@code null}
     * @param pptx             octets du fichier {@code .pptx}
     * @return la présentation rangée
     */
    @Transactional
    public Presentation publish(PresentationPlace place, UUID presentationId, String title,
            String description, byte[] pptx) {
        String cleanTitle = title == null ? "" : title.strip();
        if (cleanTitle.isEmpty()) {
            throw new PresentationRejectedException("Le titre est obligatoire.");
        }
        if (cleanTitle.length() > Presentation.MAX_TITLE_LENGTH) {
            cleanTitle = cleanTitle.substring(0, Presentation.MAX_TITLE_LENGTH);
        }
        String cleanDescription = description == null || description.isBlank() ? null
                : description.strip();
        if (cleanDescription != null && cleanDescription.length() > Presentation.MAX_DESCRIPTION_LENGTH) {
            cleanDescription = cleanDescription.substring(0, Presentation.MAX_DESCRIPTION_LENGTH);
        }
        if (pptx == null || pptx.length == 0) {
            throw new PresentationRejectedException("Le fichier .pptx est vide.");
        }
        if (pptx.length > limits.maxPptxBytes()) {
            throw new PresentationRejectedException("Le .pptx dépasse la taille maximale de "
                    + (limits.maxPptxBytes() / (1024 * 1024)) + " Mo.");
        }
        if (!looksLikePptx(pptx)) {
            throw new PresentationRejectedException(
                    "Ce fichier n'est pas un .pptx (format PowerPoint attendu).");
        }

        Presentation presentation;
        if (presentationId != null) {
            presentation = presentations.findByIdAndUserId(presentationId, place.userId())
                    .orElseThrow(PresentationNotFoundException::new);
            presentation.setTitle(cleanTitle);
            presentation.setDescription(cleanDescription);
            // Un nouveau .pptx remplace l'ancien : le rendu par slides (SF-129-03) redevient à refaire.
            presentation.setSlideCount(null);
        } else {
            presentation = Presentation.builder()
                    .userId(place.userId())
                    .space(place.space())
                    .hostId(place.hostId())
                    .workspaceId(place.workspaceId())
                    .title(cleanTitle)
                    .description(cleanDescription)
                    // Clé provisoire : la clé définitive dérive de l'id, qui n'existe qu'après le
                    // premier save. La colonne est NOT NULL — on ne peut pas la laisser à null ici.
                    .pptxKey("pending")
                    .build();
        }
        presentation.setPptxBytes(pptx.length);
        // On sauve d'abord pour disposer de l'id (nouvelle présentation), puis on range le fichier
        // sous une clé dérivée de cet id.
        Presentation saved = presentations.save(presentation);
        saved.setPptxKey(PresentationStore.pptxKey(saved.getUserId(), saved.getId()));
        if (presentationId != null) {
            // Republication : on efface d'abord les anciennes slides (le rendu est à refaire).
            store.deleteSlides(saved.getUserId(), saved.getId());
        }
        store.putPptx(saved.getUserId(), saved.getId(), pptx);
        return presentations.save(saved);
    }

    /** Les présentations d'un lieu, la plus récente d'abord. */
    @Transactional(readOnly = true)
    public List<Presentation> list(UUID userId, UUID hostId, PresentationSpace space) {
        return presentations.findByUserIdAndHostIdAndSpaceOrderByUpdatedAtDesc(userId, hostId, space);
    }

    /** Une présentation, scellée par le propriétaire. */
    @Transactional(readOnly = true)
    public Presentation get(UUID userId, UUID presentationId) {
        return presentations.findByIdAndUserId(presentationId, userId)
                .orElseThrow(PresentationNotFoundException::new);
    }

    /** Les octets du {@code .pptx} d'une présentation, scellés par le propriétaire. */
    @Transactional(readOnly = true)
    public byte[] pptx(UUID userId, UUID presentationId) {
        Presentation presentation = get(userId, presentationId);
        return store.pptx(userId, presentation.getId()).orElseThrow(PresentationNotFoundException::new);
    }

    /** Supprime une présentation et tout son contenu objet. */
    @Transactional
    public void delete(UUID userId, UUID presentationId) {
        Presentation presentation = presentations.findByIdAndUserId(presentationId, userId)
                .orElseThrow(PresentationNotFoundException::new);
        presentations.delete(presentation);
        store.deletePresentation(userId, presentation.getId());
    }

    /** Vrai si les octets commencent par l'en-tête ZIP d'un fichier OpenXML. */
    private static boolean looksLikePptx(byte[] content) {
        if (content.length < ZIP_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < ZIP_MAGIC.length; i++) {
            if (content[i] != ZIP_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /** Résolution optionnelle pour les appelants qui tolèrent l'absence. */
    Optional<Presentation> find(UUID userId, UUID presentationId) {
        return presentations.findByIdAndUserId(presentationId, userId);
    }
}
