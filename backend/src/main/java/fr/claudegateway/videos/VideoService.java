package fr.claudegateway.videos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import fr.claudegateway.billing.AdministratorEntitlement;

/**
 * <b>Ranger une vidéo</b> (F-213 / SF-213-01) : la préparer (contrôles, plafonds, quota), puis l'inscrire une
 * fois ses octets envoyés — toujours pour son propriétaire.
 *
 * <h2>Deux temps, et pourquoi</h2>
 *
 * <p>Une vidéo de 300 Mo met une minute à remonter du poste. {@link #prepare} décide <b>avant</b> tout
 * transfert (taille connue dès la première tranche) ; {@link #commit} inscrit les lignes en une transaction
 * courte, une fois l'objet envoyé. Aucune connexion de base n'est tenue pendant le transfert.</p>
 *
 * <h2>L'isolation</h2>
 *
 * <p>Toute lecture passe par {@code findByIdAndUserId} : la vidéo d'un autre compte est <b>introuvable</b>,
 * indiscernable d'une vidéo qui n'existe pas — la remplacer aussi.</p>
 */
@Service
public class VideoService {

    /** Longueur maximale d'un titre. */
    public static final int MAX_TITLE_CHARS = 120;
    /** Longueur maximale d'une description. */
    public static final int MAX_DESCRIPTION_CHARS = 300;
    /** Longueur maximale d'un libellé de piste. */
    public static final int MAX_LABEL_CHARS = 80;
    /** Rang maximal d'un épisode. */
    public static final int MAX_EPISODE = 999;

    private final VideoRepository videos;
    private final VideoVersionRepository versions;
    private final VideoSeriesRepository series;
    private final VideoStore store;
    private final VideoLimits limits;
    private final AdministratorEntitlement administrators;
    private final TransactionTemplate transactions;

    public VideoService(VideoRepository videos, VideoVersionRepository versions, VideoSeriesRepository series,
            VideoStore store, VideoLimits limits, AdministratorEntitlement administrators,
            PlatformTransactionManager transactionManager) {
        this.videos = videos;
        this.versions = versions;
        this.series = series;
        this.store = store;
        this.limits = limits;
        this.administrators = administrators;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ publication

    /**
     * Ce qu'on publie.
     *
     * @param place       lieu (propriétaire compris)
     * @param type        type de média admis
     * @param replaceId   vidéo à remplacer (nouvelle version), ou {@code null}
     * @param title       titre
     * @param description description, facultative
     * @param chapters    chapitres validés
     * @param seriesRef   série (identifiant ou titre), facultative
     * @param episode     rang dans la série, facultatif
     * @param durationS   durée annoncée, facultative
     * @param label       libellé de piste audio, facultatif
     * @param sources     fiche des sources validée (F-215 / SF-215-04), liste vide sinon
     * @param voice       voix à fixer pour la série (SF-215-04), facultative
     * @param voiceInstructions consignes de ton de la série, facultatives
     */
    public record PublishRequest(VideoPlace place, VideoMediaTypes.MediaType type, UUID replaceId, String title,
            String description, List<VideoChapter> chapters, String seriesRef, Integer episode, Integer durationS,
            String label, List<VideoSource> sources, String voice, String voiceInstructions) {

        /** La forme historique (F-213) : sans fiche des sources ni voix de série. */
        public PublishRequest(VideoPlace place, VideoMediaTypes.MediaType type, UUID replaceId, String title,
                String description, List<VideoChapter> chapters, String seriesRef, Integer episode, Integer durationS,
                String label) {
            this(place, type, replaceId, title, description, chapters, seriesRef, episode, durationS, label,
                    List.of(), null, null);
        }
    }

    /** Longueur maximale des consignes de ton d'une série. */
    public static final int MAX_VOICE_INSTRUCTIONS_CHARS = 1_000;

    private static final java.util.regex.Pattern VOICE = java.util.regex.Pattern.compile("[a-z0-9_-]{1,32}");

    /**
     * Une publication décidée, prête à recevoir ses octets.
     *
     * @param request    la demande nettoyée
     * @param videoId    identifiant de la vidéo (nouvelle ou remplacée)
     * @param isNew      vrai pour une nouvelle vidéo
     * @param version    version à créer
     * @param mediaKey   clé de l'objet média
     * @param purged     versions qui seront purgées
     */
    public record Prepared(PublishRequest request, UUID videoId, boolean isNew, int version, String mediaKey,
            List<VideoVersion> purged) {
    }

    /**
     * Prépare une publication : nettoie, contrôle les plafonds et le quota du compte (D1), résout la vidéo
     * remplacée. Rien n'est écrit.
     *
     * @param sizeBytes   taille du média (connue à la première tranche)
     * @param posterBytes taille de la couverture, {@code 0} sans couverture
     * @throws VideoRejectedException si une contrainte n'est pas tenue (message pour l'agent)
     */
    @Transactional(readOnly = true)
    public Prepared prepare(PublishRequest request, long sizeBytes, long posterBytes) {
        VideoKind kind = request.type().kind();
        PublishRequest clean = new PublishRequest(request.place(), request.type(), request.replaceId(),
                cleanTitle(request.title()), cleanDescription(request.description()),
                request.chapters() == null ? List.of() : request.chapters(), blankToNull(request.seriesRef()),
                request.episode(), request.durationS() == null || request.durationS() <= 0 ? null : request.durationS(),
                cleanLabel(request.label()), request.sources() == null ? List.of() : request.sources(),
                cleanVoice(request.voice()), cleanVoiceInstructions(request.voiceInstructions()));
        if (clean.episode() != null && (clean.episode() < 1 || clean.episode() > MAX_EPISODE)) {
            throw new VideoRejectedException("episode est un rang de 1 à " + MAX_EPISODE + ".");
        }
        if (kind == VideoKind.AUDIO && (clean.seriesRef() != null || clean.episode() != null)) {
            throw new VideoRejectedException("Un son ne se range pas dans une série.");
        }
        long max = limits.maxBytes(kind);
        if (sizeBytes > max) {
            throw new VideoRejectedException((kind == VideoKind.AUDIO ? "Son trop volumineux : " : "Vidéo trop volumineuse : ")
                    + megabytes(max) + " Mo au plus. Réduis la résolution ou le débit, ou découpe en épisodes.");
        }
        if (sizeBytes <= 0) {
            throw new VideoRejectedException("Le fichier est vide.");
        }
        if (posterBytes > limits.maxPosterBytes()) {
            throw new VideoRejectedException("Couverture trop volumineuse : " + megabytes(limits.maxPosterBytes())
                    + " Mo au plus.");
        }
        if (kind == VideoKind.AUDIO && !clean.sources().isEmpty()) {
            throw new VideoRejectedException("Un son ne porte pas de fiche des sources.");
        }
        UUID userId = clean.place().userId();
        if (clean.seriesRef() != null) {
            resolveSeriesRef(userId, clean.place().workspaceId(), null, clean.seriesRef(), false);
        }

        UUID videoId;
        boolean isNew;
        List<VideoVersion> kept;
        if (clean.replaceId() == null) {
            videoId = UUID.randomUUID();
            isNew = true;
            kept = List.of();
        } else {
            Video existing = videos.findByIdAndUserId(clean.replaceId(), userId)
                    .filter(video -> video.getKind() == kind)
                    .orElseThrow(() -> new VideoRejectedException("remplace inconnu : aucune "
                            + (kind == VideoKind.AUDIO ? "piste" : "vidéo")
                            + " de ce compte ne porte cet identifiant. Omets remplace pour en publier une nouvelle."));
            videoId = existing.getId();
            isNew = false;
            kept = versions.findByVideoIdAndUserIdOrderByVersionAsc(videoId, userId);
        }
        if ((clean.voice() != null || clean.voiceInstructions() != null) && clean.seriesRef() == null
                && (isNew || videos.findByIdAndUserId(videoId, userId).map(Video::getSeriesId).isEmpty())) {
            throw new VideoRejectedException("voix et consignes_voix fixent la voix d'une SÉRIE : donne aussi serie "
                    + "(ou publie une nouvelle version d'un épisode déjà rangé dans une série).");
        }
        int overflow = Math.max(0, kept.size() + 1 - limits.maxVersions());
        List<VideoVersion> purged = List.copyOf(kept.subList(0, Math.min(overflow, kept.size())));
        long freed = purged.stream().mapToLong(VideoVersion::getSizeBytes).sum();
        if (!administrators.isAdministrator(userId)) {
            long used = versions.sumSizeBytesByUserId(userId);
            if (used - freed + sizeBytes + posterBytes > limits.maxAccountBytes()) {
                throw new VideoRejectedException("Stockage vidéo plein : " + gigabytes(used) + " Go utilisés sur "
                        + gigabytes(limits.maxAccountBytes()) + " Go. Supprime des vidéos depuis la galerie, ou "
                        + "allège celle-ci.");
            }
        }
        int version = kept.isEmpty() ? 1 : kept.get(kept.size() - 1).getVersion() + 1;
        return new Prepared(clean, videoId, isNew, version,
                VideoStore.mediaKey(userId, videoId, version, clean.type()), purged);
    }

    /**
     * Inscrit une publication dont les octets sont rangés : la série (créée au besoin), la vidéo, la version ;
     * purge les versions au-delà de la borne.
     *
     * @param sizeBytes  taille réellement envoyée
     * @param posterKey  clé de la couverture rangée, ou {@code null}
     * @param posterMime type de la couverture, ou {@code null}
     */
    public PublishedVideo commit(Prepared prepared, long sizeBytes, String posterKey, String posterMime) {
        PublishedVideo published = transactions.execute(status -> doCommit(prepared, sizeBytes, posterKey, posterMime));
        UUID userId = prepared.request().place().userId();
        for (VideoVersion old : prepared.purged()) {
            store.deleteVersion(userId, prepared.videoId(), old.getVersion());
        }
        return published;
    }

    private PublishedVideo doCommit(Prepared prepared, long sizeBytes, String posterKey, String posterMime) {
        PublishRequest request = prepared.request();
        UUID userId = request.place().userId();
        Video video;
        if (prepared.isNew()) {
            video = Video.builder().id(prepared.videoId()).userId(userId).workspaceId(request.place().workspaceId())
                    .hostId(request.place().hostId()).kind(request.type().kind()).build();
        } else {
            video = videos.findByIdAndUserId(prepared.videoId(), userId).orElseThrow(VideoNotFoundException::new);
        }
        VideoSeries inSeries = null;
        if (request.seriesRef() != null) {
            inSeries = resolveSeriesRef(userId, request.place().workspaceId(), request.place().hostId(), request.seriesRef(), true);
            boolean sameSeries = inSeries.getId().equals(video.getSeriesId());
            Integer episode = request.episode() != null ? request.episode()
                    : sameSeries && video.getEpisode() != null ? video.getEpisode()
                    : videos.maxEpisode(userId, inSeries.getId()) + 1;
            video.setSeriesId(inSeries.getId());
            video.setEpisode(Math.min(episode, MAX_EPISODE));
        } else if (request.episode() != null && video.getSeriesId() != null) {
            video.setEpisode(request.episode());
        } else if (video.getSeriesId() != null) {
            inSeries = series.findByIdAndUserId(video.getSeriesId(), userId).orElse(null);
        }
        if (inSeries == null && video.getSeriesId() != null) {
            inSeries = series.findByIdAndUserId(video.getSeriesId(), userId).orElse(null);
        }
        if (inSeries != null && (request.voice() != null || request.voiceInstructions() != null)) {
            // SF-215-04 : la voix est fixée pour toute la série, et modifiable pour la série entière.
            if (request.voice() != null) {
                inSeries.setVoice(request.voice());
            }
            if (request.voiceInstructions() != null) {
                inSeries.setVoiceInstructions(request.voiceInstructions());
            }
            inSeries = series.saveAndFlush(inSeries);
        }
        video.setTitle(request.title());
        if (request.description() != null || prepared.isNew()) {
            video.setDescription(request.description());
        }
        if (request.durationS() != null) {
            video.setDurationS(request.durationS());
        }
        video.setCurrentVersion(prepared.version());
        Video saved = videos.saveAndFlush(video);
        VideoVersion created = versions.save(VideoVersion.builder().id(UUID.randomUUID()).videoId(saved.getId())
                .userId(userId).version(prepared.version()).storageKey(prepared.mediaKey()).sizeBytes(sizeBytes)
                .mime(request.type().mime()).chaptersJson(VideoChapter.toJson(request.chapters()))
                .sourcesJson(VideoSource.toJson(request.sources()))
                .posterKey(posterKey).posterMime(posterMime).label(request.label()).build());
        for (VideoVersion old : prepared.purged()) {
            versions.deleteById(old.getId());
        }
        return new PublishedVideo(saved, created, inSeries);
    }

    /**
     * La série d'une référence : un identifiant du compte, sinon un titre cherché dans le sujet (casse
     * ignorée) ; créée si {@code create} et introuvable.
     */
    private VideoSeries resolveSeriesRef(UUID userId, UUID workspaceId, UUID hostId, String ref, boolean create) {
        try {
            UUID id = UUID.fromString(ref.strip());
            return series.findByIdAndUserId(id, userId)
                    .orElseThrow(() -> new VideoRejectedException("serie inconnue : aucune série de ce compte ne "
                            + "porte cet identifiant. Donne plutôt son titre."));
        } catch (IllegalArgumentException notAnId) {
            String title = cleanSeriesTitle(ref);
            Optional<VideoSeries> found = series.findByUserIdAndWorkspaceId(userId, workspaceId).stream()
                    .filter(s -> s.getTitle().toLowerCase(Locale.ROOT).equals(title.toLowerCase(Locale.ROOT)))
                    .findFirst();
            if (found.isPresent() || !create) {
                return found.orElse(null);
            }
            return series.saveAndFlush(VideoSeries.builder().id(UUID.randomUUID()).userId(userId)
                    .workspaceId(workspaceId).hostId(hostId).title(title).build());
        }
    }

    // ------------------------------------------------------------------ lecture

    /** La vidéo, si elle appartient au compte. */
    @Transactional(readOnly = true)
    public Video require(UUID userId, UUID videoId) {
        return videos.findByIdAndUserId(videoId, userId).orElseThrow(VideoNotFoundException::new);
    }

    /** Une version conservée (la courante si {@code version} est nul ou ≤ 0). */
    @Transactional(readOnly = true)
    public VideoVersion requireVersion(UUID userId, UUID videoId, Integer version) {
        Video video = require(userId, videoId);
        int wanted = version == null || version <= 0 ? video.getCurrentVersion() : version;
        return versions.findByVideoIdAndUserIdAndVersion(video.getId(), userId, wanted)
                .orElseThrow(VideoNotFoundException::new);
    }

    /** Les versions conservées, la plus récente d'abord. */
    @Transactional(readOnly = true)
    public List<VideoVersion> versions(UUID userId, UUID videoId) {
        Video video = require(userId, videoId);
        List<VideoVersion> all = new ArrayList<>(versions.findByVideoIdAndUserIdOrderByVersionAsc(video.getId(), userId));
        Collections.reverse(all);
        return List.copyOf(all);
    }

    /** La série, si elle appartient au compte. */
    @Transactional(readOnly = true)
    public Optional<VideoSeries> series(UUID userId, UUID seriesId) {
        return seriesId == null ? Optional.empty() : series.findByIdAndUserId(seriesId, userId);
    }

    /**
     * La série d'une référence (identifiant ou titre) dans un sujet du compte, sans jamais la créer
     * (F-215 / SF-215-04 : {@code voix_off(serie)}). Une série d'un autre compte ou d'un autre sujet est
     * introuvable.
     */
    @Transactional(readOnly = true)
    public Optional<VideoSeries> findSeries(UUID userId, UUID workspaceId, String ref) {
        if (userId == null || workspaceId == null || ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(resolveSeriesRef(userId, workspaceId, null, ref, false))
                    .filter(found -> workspaceId.equals(found.getWorkspaceId()));
        } catch (VideoRejectedException e) {
            return Optional.empty();
        }
    }

    /** Le stockage vidéo + audio utilisé par le compte. */
    @Transactional(readOnly = true)
    public long usedBytes(UUID userId) {
        return versions.sumSizeBytesByUserId(userId);
    }

    // ------------------------------------------------------------------ galerie (SF-213-03)

    /**
     * La galerie d'un sujet ou d'un poste du compte : les séries (épisodes dans l'ordre) et les vidéos seules —
     * jamais les sons. Un sujet ou un poste d'un autre compte rend une galerie vide (filtre {@code user_id}).
     *
     * @param workspaceId sujet, ou {@code null}
     * @param hostId      poste, ou {@code null} (l'un des deux est requis)
     */
    @Transactional(readOnly = true)
    public Gallery gallery(UUID userId, UUID workspaceId, UUID hostId) {
        if (workspaceId == null && hostId == null) {
            throw new VideoRejectedException("Précise workspaceId (un sujet) ou hostId (un poste).");
        }
        List<Video> placed = workspaceId != null
                ? videos.findByUserIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(userId, workspaceId, VideoKind.VIDEO)
                : videos.findByUserIdAndHostIdAndKindOrderByCreatedAtDesc(userId, hostId, VideoKind.VIDEO);
        java.util.Map<UUID, VideoVersion> current = new java.util.HashMap<>();
        for (Video video : placed) {
            versions.findByVideoIdAndUserIdAndVersion(video.getId(), userId, video.getCurrentVersion())
                    .ifPresent(version -> current.put(video.getId(), version));
        }
        java.util.Map<UUID, List<Video>> bySeries = new java.util.LinkedHashMap<>();
        List<Video> standalone = new ArrayList<>();
        for (Video video : placed) {
            if (video.getSeriesId() == null) {
                standalone.add(video);
            } else {
                bySeries.computeIfAbsent(video.getSeriesId(), id -> new ArrayList<>()).add(video);
            }
        }
        List<SeriesEntry> entries = new ArrayList<>();
        for (java.util.Map.Entry<UUID, List<Video>> entry : bySeries.entrySet()) {
            Optional<VideoSeries> found = series.findByIdAndUserId(entry.getKey(), userId);
            if (found.isEmpty()) {
                standalone.addAll(entry.getValue());
                continue;
            }
            List<Video> episodes = new ArrayList<>(entry.getValue());
            episodes.sort(java.util.Comparator
                    .comparing((Video v) -> v.getEpisode() == null ? Integer.MAX_VALUE : v.getEpisode())
                    .thenComparing(Video::getCreatedAt, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
            entries.add(new SeriesEntry(found.get(), List.copyOf(episodes)));
        }
        return new Gallery(List.copyOf(entries), List.copyOf(standalone), java.util.Map.copyOf(current));
    }

    /**
     * Supprime une vidéo du compte : ses objets, puis sa ligne (versions en cascade). Une série devenue vide part
     * avec elle.
     */
    @Transactional
    public void delete(UUID userId, UUID videoId) {
        Video video = require(userId, videoId);
        videos.delete(video);
        videos.flush();
        // Les objets après les lignes : un stockage qui n'efface pas tout lève, et la suppression est annulée
        // — la vidéo reste listée, rien n'est perdu en silence (F-79).
        store.deleteVideo(userId, video.getId());
        if (video.getSeriesId() != null
                && videos.findByUserIdAndSeriesIdOrderByEpisodeAscCreatedAtAsc(userId, video.getSeriesId()).isEmpty()) {
            series.findByIdAndUserId(video.getSeriesId(), userId).ifPresent(series::delete);
        }
    }

    /** Supprime une série du compte et tous ses épisodes (objets compris). */
    @Transactional
    public void deleteSeries(UUID userId, UUID seriesId) {
        VideoSeries found = series.findByIdAndUserId(seriesId, userId).orElseThrow(VideoNotFoundException::new);
        List<Video> episodes = videos.findByUserIdAndSeriesIdOrderByEpisodeAscCreatedAtAsc(userId, seriesId);
        videos.deleteAll(episodes);
        videos.flush();
        series.delete(found);
        series.flush();
        for (Video video : episodes) {
            store.deleteVideo(userId, video.getId());
        }
    }

    /** Durée maximale admise (24 h). */
    public static final int MAX_DURATION_S = 24 * 3600;

    /**
     * La durée lue par le navigateur (métadonnées du conteneur) corrige celle annoncée par l'agent, si elle en
     * diffère de plus d'une seconde.
     */
    @Transactional
    public Video setDuration(UUID userId, UUID videoId, double seconds) {
        if (!(seconds >= 1) || seconds > MAX_DURATION_S) {
            throw new VideoRejectedException("Durée invalide : de 1 s à 24 h.");
        }
        Video video = require(userId, videoId);
        int rounded = (int) Math.round(seconds);
        if (video.getDurationS() == null || Math.abs(video.getDurationS() - rounded) > 1) {
            video.setDurationS(rounded);
            return videos.saveAndFlush(video);
        }
        return video;
    }

    /**
     * Une série et ses épisodes, dans l'ordre.
     *
     * @param series   la série
     * @param episodes ses vidéos, par rang
     */
    public record SeriesEntry(VideoSeries series, List<Video> episodes) {
    }

    /**
     * La galerie d'un lieu.
     *
     * @param series     les séries, de la plus récente à la plus ancienne
     * @param standalone les vidéos hors série
     * @param current    la version courante de chaque vidéo, par identifiant
     */
    public record Gallery(List<SeriesEntry> series, List<Video> standalone,
            java.util.Map<UUID, VideoVersion> current) {
    }

    // ------------------------------------------------------------------ effacement

    /**
     * Efface les vidéos et séries d'un sujet : objets puis lignes. Appelé à la suppression du sujet ; un
     * stockage qui n'efface pas tout lève, et la suppression du sujet est annulée (F-79).
     */
    @Transactional
    public void deleteWorkspace(UUID userId, UUID workspaceId) {
        List<Video> placed = videos.findByUserIdAndWorkspaceId(userId, workspaceId);
        for (Video video : placed) {
            store.deleteVideo(userId, video.getId());
        }
        videos.deleteAll(placed);
        series.deleteAll(series.findByUserIdAndWorkspaceId(userId, workspaceId));
    }

    /** Efface les objets de toutes les vidéos d'un compte ; les lignes tombent avec le compte. */
    public void purgeUser(UUID userId) {
        store.deleteAccount(userId);
    }

    // ------------------------------------------------------------------ nettoyage

    static String cleanTitle(String title) {
        String clean = title == null ? "" : title.replaceAll("\\s+", " ").strip();
        if (clean.isEmpty()) {
            throw new VideoRejectedException("Une vidéo a besoin d'un titre court et distinctif (titre).");
        }
        if (clean.length() > MAX_TITLE_CHARS) {
            throw new VideoRejectedException("Titre trop long : " + MAX_TITLE_CHARS + " caractères au plus.");
        }
        return clean;
    }

    private static String cleanSeriesTitle(String title) {
        String clean = title == null ? "" : title.replaceAll("\\s+", " ").strip();
        if (clean.isEmpty() || clean.length() > MAX_TITLE_CHARS) {
            throw new VideoRejectedException("Titre de série vide ou trop long (" + MAX_TITLE_CHARS
                    + " caractères au plus).");
        }
        return clean;
    }

    private static String cleanDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String clean = description.replaceAll("\\s+", " ").strip();
        if (clean.length() > MAX_DESCRIPTION_CHARS) {
            throw new VideoRejectedException("Description trop longue : " + MAX_DESCRIPTION_CHARS
                    + " caractères au plus.");
        }
        return clean;
    }

    private static String cleanLabel(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        String clean = label.replaceAll("\\s+", " ").strip();
        if (clean.length() > MAX_LABEL_CHARS) {
            throw new VideoRejectedException("Libellé de piste trop long : " + MAX_LABEL_CHARS + " caractères au plus.");
        }
        return clean;
    }

    private static String cleanVoice(String voice) {
        if (voice == null || voice.isBlank()) {
            return null;
        }
        String clean = voice.strip().toLowerCase(Locale.ROOT);
        if (!VOICE.matcher(clean).matches()) {
            throw new VideoRejectedException("voix invalide : un nom de voix du fournisseur (ex. onyx).");
        }
        return clean;
    }

    private static String cleanVoiceInstructions(String instructions) {
        if (instructions == null || instructions.isBlank()) {
            return null;
        }
        String clean = instructions.strip();
        if (clean.length() > MAX_VOICE_INSTRUCTIONS_CHARS) {
            throw new VideoRejectedException("consignes_voix trop longues : " + MAX_VOICE_INSTRUCTIONS_CHARS
                    + " caractères au plus.");
        }
        return clean;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static long megabytes(long bytes) {
        return bytes / (1024 * 1024);
    }

    private static String gigabytes(long bytes) {
        return String.format(Locale.FRANCE, "%.1f", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * Une vidéo publiée.
     *
     * @param video   la vidéo, à jour
     * @param version la version créée
     * @param series  sa série, ou {@code null}
     */
    public record PublishedVideo(Video video, VideoVersion version, VideoSeries series) {
    }
}
