package fr.claudegateway.jarvis.pieces;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.besoin.AppRequestNotFoundException;
import fr.claudegateway.atelier.besoin.AppRequestService;
import fr.claudegateway.atelier.proof.ProofCapture;
import fr.claudegateway.atelier.proof.ProofCaptureRepository;
import fr.claudegateway.atelier.proof.ProofCaptureService;
import fr.claudegateway.images.GeneratedImageRepository;
import fr.claudegateway.images.GeneratedImageStore;
import fr.claudegateway.mail.ClientEmail;
import fr.claudegateway.mail.ClientEmailRepository;
import fr.claudegateway.presentations.Presentation;
import fr.claudegateway.presentations.PresentationNotFoundException;
import fr.claudegateway.presentations.PresentationRepository;
import fr.claudegateway.presentations.PresentationService;
import fr.claudegateway.videos.Video;
import fr.claudegateway.videos.VideoRepository;
import fr.claudegateway.videos.VideoStore;
import fr.claudegateway.videos.VideoVersion;
import fr.claudegateway.videos.VideoVersionRepository;

/**
 * <b>Les contenus que l'index désigne et qu'aucune lecture ne servait</b> (F-237 / SF-237-02, cadrage §4.3) :
 * vignettes réduites, corps <b>texte</b> d'un courriel, pièces d'un besoin pour leur propriétaire.
 *
 * <p>Chaque lecture part d'une ligne trouvée par ({@code id}, {@code user_id}) : une pièce d'un autre compte est
 * <b>introuvable</b> — 404, indiscernable d'une pièce inexistante. Aucune URL de stockage ne sort.</p>
 */
@Service
public class JarvisPieceContentService {

    /** Vignettes gardées en mémoire au plus. */
    private static final int THUMB_CACHE = 200;

    /** Une pièce introuvable pour ce compte (404). */
    public static class PieceNotFoundException extends RuntimeException {
        public PieceNotFoundException() {
            super("Pièce introuvable.");
        }
    }

    /** Un contenu servi : octets, type, nom. */
    public record Content(byte[] bytes, String contentType, String name) {
    }

    private record ThumbKey(UUID userId, String pieceId) {
    }

    private final ProofCaptureRepository proofs;
    private final ProofCaptureService proofService;
    private final GeneratedImageRepository images;
    private final GeneratedImageStore imageStore;
    private final PresentationRepository presentations;
    private final PresentationService presentationService;
    private final VideoRepository videos;
    private final VideoVersionRepository videoVersions;
    private final VideoStore videoStore;
    private final ClientEmailRepository emails;
    private final AppRequestService requests;
    private final Map<ThumbKey, byte[]> thumbs = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ThumbKey, byte[]> eldest) {
            return size() > THUMB_CACHE;
        }
    };

    @SuppressWarnings("java:S107") // une vignette se lit à la source de chaque type de pièce
    public JarvisPieceContentService(ProofCaptureRepository proofs, ProofCaptureService proofService,
            GeneratedImageRepository images, GeneratedImageStore imageStore, PresentationRepository presentations,
            PresentationService presentationService, VideoRepository videos, VideoVersionRepository videoVersions,
            VideoStore videoStore, ClientEmailRepository emails, AppRequestService requests) {
        this.proofs = proofs;
        this.proofService = proofService;
        this.images = images;
        this.imageStore = imageStore;
        this.presentations = presentations;
        this.presentationService = presentationService;
        this.videos = videos;
        this.videoVersions = videoVersions;
        this.videoStore = videoStore;
        this.emails = emails;
        this.requests = requests;
    }

    /**
     * La vignette PNG (≤ 320 px) d'une pièce : capture de preuve, image générée, diapo 1 d'une présentation,
     * couverture d'une vidéo. 404 pour tout le reste (dépôts sur poste : jamais de vignette, l'original s'ouvre).
     */
    @Transactional(readOnly = true)
    public byte[] thumb(UUID userId, String rawId) {
        PieceId id = PieceId.parse(rawId).orElseThrow(PieceNotFoundException::new);
        ThumbKey key = new ThumbKey(userId, rawId.strip());
        synchronized (thumbs) {
            byte[] hit = thumbs.get(key);
            if (hit != null) {
                return hit;
            }
        }
        byte[] source = source(userId, id).orElseThrow(PieceNotFoundException::new);
        byte[] thumb = PieceThumbnails.of(source).orElse(source);
        synchronized (thumbs) {
            thumbs.put(key, thumb);
        }
        return thumb;
    }

    private Optional<byte[]> source(UUID userId, PieceId id) {
        return switch (id.prefix()) {
            case "proof" -> proofs.findByIdAndUserId(id.id(), userId)
                    .flatMap(c -> proofService.image(userId, c.getWorkspaceId(), hex(c)));
            case "img" -> images.findByIdAndUserId(id.id(), userId)
                    .flatMap(i -> imageStore.image(userId, i.getId()));
            case "slides" -> presentations.findByIdAndUserId(id.id(), userId).flatMap(p -> firstSlide(userId, p));
            case "video" -> videos.findByIdAndUserId(id.id(), userId).flatMap(v -> poster(userId, v, id.version()));
            default -> Optional.empty();
        };
    }

    private Optional<byte[]> firstSlide(UUID userId, Presentation p) {
        try {
            return Optional.of(presentationService.slide(userId, p.getId(), 1));
        } catch (PresentationNotFoundException e) {
            return Optional.empty(); // pas (encore) de diapos rendues
        }
    }

    private Optional<byte[]> poster(UUID userId, Video video, Integer version) {
        int wanted = version == null ? video.getCurrentVersion() : version;
        return videoVersions.findByVideoIdAndUserIdAndVersion(video.getId(), userId, wanted)
                .map(VideoVersion::getPosterKey)
                .flatMap(posterKey -> posterKey == null ? Optional.empty() : videoStore.get(posterKey));
    }

    /** Le corps <b>texte</b> d'un courriel du compte — jamais le HTML (aucune injection). */
    @Transactional(readOnly = true)
    public String mailText(UUID userId, String rawId) {
        PieceId id = PieceId.parse(rawId).filter(p -> "mail".equals(p.prefix()))
                .orElseThrow(PieceNotFoundException::new);
        ClientEmail email = emails.findByIdAndUserId(id.id(), userId).orElseThrow(PieceNotFoundException::new);
        return email.getBodyText() == null ? "" : email.getBodyText();
    }

    /** La pièce n° {@code position} (base 1) d'un besoin du compte. */
    @Transactional(readOnly = true)
    public Content attachment(UUID userId, String rawId, int position) {
        PieceId id = PieceId.parse(rawId).filter(p -> "req".equals(p.prefix()))
                .orElseThrow(PieceNotFoundException::new);
        try {
            AppRequestService.AttachmentContent content = requests.attachment(userId, id.id(), position);
            return new Content(content.bytes(), content.contentType(), content.name());
        } catch (AppRequestNotFoundException e) {
            throw new PieceNotFoundException();
        }
    }

    private static String hex(ProofCapture capture) {
        return capture.getId().toString().replace("-", "");
    }
}
