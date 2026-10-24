package fr.claudegateway.videos;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Les retours à la seconde</b> (F-214 / SF-214-01) : les poser, les envoyer à l'agent, les clore à la
 * republication — toujours pour leur propriétaire.
 *
 * <h2>L'isolation</h2>
 *
 * <p>Toute opération passe d'abord par {@link VideoService#require} (la vidéo du compte, sinon introuvable),
 * puis lit le retour par {@code (id, user_id, video_id)} : le retour d'un autre compte — ou d'une autre vidéo —
 * est indiscernable d'un retour qui n'existe pas.</p>
 *
 * <h2>Les transitions</h2>
 *
 * <p>L'écran pose ({@code OUVERT}), envoie ({@code OUVERT → ENVOYE}) et peut rouvrir ({@code → OUVERT}) ; seul
 * l'agent clôt ({@code OUVERT | ENVOYE → TRAITE | REJETE}), en republiant la vidéo.</p>
 */
@Service
public class VideoCommentService {

    /** Cadence retenue pour l'image d'un instant : F-213 ne range pas la cadence du conteneur. */
    public static final int FRAMES_PER_SECOND = 30;
    /** Longueur maximale d'un retour. */
    public static final int MAX_TEXT_CHARS = 1000;
    /** Longueur maximale d'un motif de clôture. */
    public static final int MAX_RESOLUTION_CHARS = 300;
    /** Nombre maximal de retours par vidéo (toutes versions). */
    public static final int MAX_PER_VIDEO = 300;
    /** Instant maximal (24 h). */
    public static final long MAX_MS = 24L * 3600 * 1000;

    private final VideoService videoService;
    private final VideoCommentRepository comments;

    public VideoCommentService(VideoService videoService, VideoCommentRepository comments) {
        this.videoService = videoService;
        this.comments = comments;
    }

    // ------------------------------------------------------------------ écran

    /** Les retours d'une vidéo du compte : d'une version, ou de toutes (par version puis instant). */
    @Transactional(readOnly = true)
    public List<VideoComment> list(UUID userId, UUID videoId, Integer version) {
        Video video = videoService.require(userId, videoId);
        return version == null || version <= 0
                ? comments.findByUserIdAndVideoIdOrderByVersionAscStartMsAscCreatedAtAsc(userId, video.getId())
                : comments.findByUserIdAndVideoIdAndVersionOrderByStartMsAscCreatedAtAsc(userId, video.getId(), version);
    }

    /**
     * Pose un retour sur une version conservée (la courante si {@code version} est absente).
     *
     * @throws VideoNotFoundException  vidéo ou version introuvable pour ce compte
     * @throws VideoRejectedException  contrainte non tenue
     */
    @Transactional
    public VideoComment create(UUID userId, UUID videoId, Integer version, Long startMs, Long endMs, String text) {
        VideoVersion served = videoService.requireVersion(userId, videoId, version);
        if (startMs == null || startMs < 0 || startMs > MAX_MS) {
            throw new VideoRejectedException("Instant invalide : de 0 à 24 h.");
        }
        if (endMs != null && (endMs <= startMs || endMs > MAX_MS)) {
            throw new VideoRejectedException("Fin de plage invalide : après le début, 24 h au plus.");
        }
        String clean = cleanText(text);
        if (comments.countByUserIdAndVideoId(userId, served.getVideoId()) >= MAX_PER_VIDEO) {
            throw new VideoRejectedException("Trop de retours sur cette vidéo : " + MAX_PER_VIDEO + " au plus.");
        }
        return comments.saveAndFlush(VideoComment.builder().id(UUID.randomUUID()).userId(userId)
                .videoId(served.getVideoId()).version(served.getVersion()).startMs(startMs).endMs(endMs)
                .frame(frame(startMs)).text(clean).status(VideoCommentStatus.OUVERT).build());
    }

    /**
     * Modifie un retour : son texte (tant qu'il est {@code OUVERT}) et/ou son statut — l'écran ne peut que
     * rouvrir ({@code OUVERT}).
     */
    @Transactional
    public VideoComment update(UUID userId, UUID videoId, UUID commentId, String text, String status) {
        VideoComment comment = requireOwned(userId, videoId, commentId);
        if (status != null && !status.isBlank()) {
            VideoCommentStatus wanted = parseStatus(status);
            if (wanted != VideoCommentStatus.OUVERT) {
                throw new VideoRejectedException("Seul l'agent clôt un retour ; l'écran peut seulement le rouvrir (OUVERT).");
            }
            comment.setStatus(VideoCommentStatus.OUVERT);
            comment.setResolution(null);
            comment.setResolvedVersion(null);
            comment.setSentAt(null);
        }
        if (text != null) {
            if (comment.getStatus() != VideoCommentStatus.OUVERT) {
                throw new VideoRejectedException("Un retour envoyé ou clos ne se modifie plus ; rouvre-le d'abord.");
            }
            comment.setText(cleanText(text));
        }
        return comments.saveAndFlush(comment);
    }

    /** Supprime un retour du compte. */
    @Transactional
    public void delete(UUID userId, UUID videoId, UUID commentId) {
        comments.delete(requireOwned(userId, videoId, commentId));
    }

    /**
     * « Envoyer à l'agent » (ou « Copier ») : compose le message structuré des retours {@code OUVERT} de la
     * version (la courante si absente) et les passe {@code ENVOYE}. Rien n'est envoyé à l'agent ici : l'écran
     * place le message dans la saisie, l'utilisateur garde la main.
     *
     * @throws VideoRejectedException aucun retour à envoyer
     */
    @Transactional
    public Sent send(UUID userId, UUID videoId, Integer version) {
        Video video = videoService.require(userId, videoId);
        VideoVersion served = videoService.requireVersion(userId, videoId, version);
        List<VideoComment> open = comments
                .findByUserIdAndVideoIdAndVersionOrderByStartMsAscCreatedAtAsc(userId, video.getId(), served.getVersion())
                .stream().filter(c -> c.getStatus() == VideoCommentStatus.OUVERT).toList();
        if (open.isEmpty()) {
            throw new VideoRejectedException("Aucun retour ouvert à envoyer sur la version " + served.getVersion() + ".");
        }
        StringBuilder message = new StringBuilder("Retours sur « ").append(video.getTitle()).append(" » v")
                .append(served.getVersion()).append(" (").append(video.getId()).append(") :");
        OffsetDateTime now = OffsetDateTime.now();
        List<VideoComment> sent = new ArrayList<>();
        for (VideoComment comment : open) {
            message.append('\n').append(line(comment));
            comment.setStatus(VideoCommentStatus.ENVOYE);
            comment.setSentAt(now);
            sent.add(comments.save(comment));
        }
        comments.flush();
        return new Sent(message.toString(), List.copyOf(sent));
    }

    // ------------------------------------------------------------------ agent

    /**
     * Le texte rendu par l'outil {@code video_retours} : la vidéo, puis une ligne par retour (identifiant,
     * version, instant, image, statut, motif).
     *
     * @throws VideoNotFoundException vidéo inconnue pour ce compte
     */
    @Transactional(readOnly = true)
    public String describeForAgent(UUID userId, UUID videoId, Integer version) {
        Video video = videoService.require(userId, videoId);
        List<VideoComment> found = list(userId, videoId, version);
        StringBuilder out = new StringBuilder("Vidéo « ").append(video.getTitle()).append(" » (").append(video.getId())
                .append(") — version courante v").append(video.getCurrentVersion());
        if (version != null && version > 0) {
            out.append(" ; retours de la v").append(version);
        }
        out.append(" : ").append(found.size()).append(found.size() > 1 ? " retours." : " retour.");
        for (VideoComment comment : found) {
            out.append("\n- [").append(comment.getId()).append("] v").append(comment.getVersion()).append(' ')
                    .append(timing(comment)).append(" [").append(comment.getStatus().name()).append("] — ")
                    .append(comment.getText());
            if (comment.getResolvedVersion() != null) {
                out.append(" (clos en v").append(comment.getResolvedVersion());
                if (comment.getResolution() != null) {
                    out.append(" : ").append(comment.getResolution());
                }
                out.append(')');
            }
        }
        return out.toString();
    }

    /**
     * Clôt les retours indiqués par l'agent à la republication ({@code video_publier(remplace,
     * retours_traites)}). Seuls les retours de <b>cette</b> vidéo, de ce compte, encore {@code OUVERT} ou
     * {@code ENVOYE} sont clos ; les autres identifiants sont ignorés et rendus.
     *
     * @param newVersion la version qui vient d'être publiée
     */
    @Transactional
    public Resolved resolve(UUID userId, UUID videoId, int newVersion, List<Resolution> resolutions) {
        int treated = 0;
        int rejected = 0;
        List<String> ignored = new ArrayList<>();
        for (Resolution resolution : resolutions) {
            VideoComment comment = comments.findByIdAndUserIdAndVideoId(resolution.id(), userId, videoId)
                    .filter(c -> c.getStatus().isPending()).orElse(null);
            if (comment == null) {
                ignored.add(resolution.id().toString());
                continue;
            }
            comment.setStatus(resolution.status());
            comment.setResolvedVersion(newVersion);
            comment.setResolution(resolution.reason());
            comments.save(comment);
            if (resolution.status() == VideoCommentStatus.TRAITE) {
                treated++;
            } else {
                rejected++;
            }
        }
        comments.flush();
        return new Resolved(treated, rejected, List.copyOf(ignored));
    }

    /**
     * Lit {@code retours_traites} : {@code [{id, statut: traite|rejete, motif?}]}. Validé <b>avant</b> tout
     * transfert de vidéo.
     *
     * @throws VideoRejectedException élément mal formé
     */
    public static List<Resolution> parseResolutions(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new VideoRejectedException("retours_traites est une liste [{id, statut: traite|rejete, motif?}].");
        }
        if (node.size() > MAX_PER_VIDEO) {
            throw new VideoRejectedException("retours_traites : " + MAX_PER_VIDEO + " éléments au plus.");
        }
        List<Resolution> out = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode item : node) {
            String rawId = item == null ? "" : item.path("id").asText("").strip();
            UUID id;
            try {
                id = UUID.fromString(rawId);
            } catch (IllegalArgumentException e) {
                throw new VideoRejectedException("retours_traites : « " + rawId + " » n'est pas un identifiant de "
                        + "retour (lis-les avec video_retours).");
            }
            String rawStatus = item.path("statut").asText("").strip().toLowerCase(Locale.ROOT);
            VideoCommentStatus status = switch (rawStatus) {
                case "traite", "traité" -> VideoCommentStatus.TRAITE;
                case "rejete", "rejeté" -> VideoCommentStatus.REJETE;
                default -> throw new VideoRejectedException("retours_traites : statut « " + rawStatus
                        + " » inconnu (traite ou rejete).");
            };
            String reason = item.path("motif").asText("").replaceAll("\\s+", " ").strip();
            if (reason.length() > MAX_RESOLUTION_CHARS) {
                reason = reason.substring(0, MAX_RESOLUTION_CHARS);
            }
            out.add(new Resolution(id, status, reason.isEmpty() ? null : reason));
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ format

    /** L'image d'un instant, à {@value #FRAMES_PER_SECOND} i/s. */
    public static int frame(long ms) {
        return (int) Math.round(ms * FRAMES_PER_SECOND / 1000.0);
    }

    /** {@code mm:ss}, ou {@code h:mm:ss} au-delà d'une heure. */
    public static String timecode(long ms) {
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.ROOT, "%02d:%02d", m, s);
    }

    /** {@code 00:10 (image 304)} ou {@code 01:20–01:35 (images 2400–2850)}. */
    static String timing(VideoComment comment) {
        if (comment.getEndMs() == null) {
            return timecode(comment.getStartMs()) + " (image " + comment.getFrame() + ")";
        }
        return timecode(comment.getStartMs()) + "–" + timecode(comment.getEndMs()) + " (images " + comment.getFrame()
                + "–" + frame(comment.getEndMs()) + ")";
    }

    /** Une ligne du message envoyé : {@code 00:10 (image 304) — texte}. */
    static String line(VideoComment comment) {
        return timing(comment) + " — " + comment.getText();
    }

    private VideoComment requireOwned(UUID userId, UUID videoId, UUID commentId) {
        Video video = videoService.require(userId, videoId);
        return comments.findByIdAndUserIdAndVideoId(commentId, userId, video.getId())
                .orElseThrow(VideoNotFoundException::new);
    }

    private static String cleanText(String text) {
        String clean = text == null ? "" : text.replaceAll("[\\r\\n\\t]+", " ").replaceAll(" {2,}", " ").strip();
        if (clean.isEmpty()) {
            throw new VideoRejectedException("Un retour a besoin d'un texte.");
        }
        if (clean.length() > MAX_TEXT_CHARS) {
            throw new VideoRejectedException("Retour trop long : " + MAX_TEXT_CHARS + " caractères au plus.");
        }
        return clean;
    }

    private static VideoCommentStatus parseStatus(String raw) {
        try {
            return VideoCommentStatus.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new VideoRejectedException("Statut inconnu : OUVERT, ENVOYE, TRAITE ou REJETE.");
        }
    }

    /**
     * Le résultat d'un envoi.
     *
     * @param message  le message à placer dans la saisie du terminal
     * @param comments les retours passés {@code ENVOYE}
     */
    public record Sent(String message, List<VideoComment> comments) {
    }

    /** Une clôture demandée par l'agent. */
    public record Resolution(UUID id, VideoCommentStatus status, String reason) {
    }

    /** Le bilan d'une clôture. */
    public record Resolved(int treated, int rejected, List<String> ignored) {

        /** La phrase ajoutée au résultat de {@code video_publier}. */
        public String summary() {
            StringBuilder out = new StringBuilder(" Retours : ").append(treated).append(treated > 1 ? " traités, " : " traité, ")
                    .append(rejected).append(rejected > 1 ? " rejetés." : " rejeté.");
            if (!ignored.isEmpty()) {
                out.append(" Ignorés (inconnus ou déjà clos) : ").append(String.join(", ", ignored)).append('.');
            }
            return out.toString();
        }
    }
}
