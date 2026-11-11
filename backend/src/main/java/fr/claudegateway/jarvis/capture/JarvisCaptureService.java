package fr.claudegateway.jarvis.capture;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.storage.WorkspaceStorage;
import fr.claudegateway.jarvis.preferences.JarvisPreferenceService;

/**
 * <b>La capture réelle des énoncés de Jarvis</b> (F-239 / SF-239-03) — pour le banc d'écoute.
 *
 * <p><b>Exception explicite</b> au régime « rien n'est conservé » de la voix (F-145, D8 F-231) : seulement pour un
 * <b>ADMIN</b> qui a allumé l'interrupteur pour lui-même ; au plus {@value #MAX_PER_USER} énoncés, {@link #RETENTION}
 * au plus ; éteindre efface tout. L'audio va dans le stockage objet sous {@code atelier/{userId}/jarvis-captures/},
 * jamais dans un journal.</p>
 */
@Service
public class JarvisCaptureService {

    private static final Logger log = LoggerFactory.getLogger(JarvisCaptureService.class);

    static final int MAX_PER_USER = 200;
    static final Duration RETENTION = Duration.ofDays(7);
    static final int MAX_TEXT = 2_000;
    static final Set<String> MODES = Set.of("BATCH", "STREAM", "CONV_BATCH", "CONV_STREAM", "BARGE_IN");

    private final JarvisAudioCaptureRepository repository;
    private final WorkspaceStorage storage;
    private final JarvisPreferenceService preferences;
    private final Clock clock;

    public JarvisCaptureService(JarvisAudioCaptureRepository repository, WorkspaceStorage storage,
            JarvisPreferenceService preferences, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.preferences = preferences;
        this.clock = clock;
    }

    /** Ce que la liste montre (sans audio). */
    public record CaptureView(UUID id, OffsetDateTime createdAt, Integer seconds, String listenMode, String provider,
            String streamText, String finalText, String correctedText) {
    }

    /** Ce que l'écran envoie avec l'audio. */
    public record Incoming(Double seconds, String listenMode, String provider, String streamText, String finalText,
            String turnId, String contentType) {

        public Incoming(Double seconds, String listenMode, String provider, String streamText, String finalText) {
            this(seconds, listenMode, provider, streamText, finalText, null, null);
        }

        public Incoming(Double seconds, String listenMode, String provider, String streamText, String finalText,
                String turnId) {
            this(seconds, listenMode, provider, streamText, finalText, turnId, null);
        }
    }

    /** F-255 / SF-255-04 — l'audio d'une capture et son type ({@code audio/wav} ou {@code audio/webm}). */
    public record AudioFile(byte[] bytes, String contentType) {
    }

    static final String WAV = "audio/wav";
    static final String WEBM = "audio/webm";

    /** F-255 / SF-255-04 — la dictée de repli (WebM) garde son type ; tout le reste est rangé comme WAV (avant). */
    static String typeOf(String declared) {
        return declared != null && declared.toLowerCase(java.util.Locale.ROOT).startsWith(WEBM) ? WEBM : WAV;
    }

    /**
     * Garde l'énoncé si le compte est ADMIN et a allumé l'interrupteur ; sinon ne fait rien. Rend vrai si gardé.
     * Un échec du stockage n'interrompt jamais l'écoute : l'énoncé n'est pas gardé.
     */
    public boolean record(UUID userId, boolean admin, byte[] audio, Incoming meta) {
        if (!admin || audio == null || audio.length == 0 || !preferences.captureAudio(userId)) {
            return false;
        }
        UUID id = UUID.randomUUID();
        String type = typeOf(meta == null ? null : meta.contentType());
        String key = "atelier/" + userId + "/jarvis-captures/" + id + (WEBM.equals(type) ? ".webm" : ".wav");
        try {
            storage.putFile(key, audio, type);
        } catch (RuntimeException e) {
            log.warn("Capture d'énoncé non gardée : stockage en échec ({})", e.getClass().getSimpleName());
            return false;
        }
        repository.save(JarvisAudioCapture.builder().id(id).userId(userId).createdAt(OffsetDateTime.now(clock))
                .storageKey(key)
                .seconds(meta == null || meta.seconds() == null ? null
                        : (int) Math.max(0, Math.min(600, Math.round(meta.seconds()))))
                .listenMode(meta == null ? null : mode(meta.listenMode()))
                .provider(meta == null ? null : cut(meta.provider(), 32))
                .streamText(meta == null ? null : cut(meta.streamText(), MAX_TEXT))
                .finalText(meta == null ? null : cut(meta.finalText(), MAX_TEXT))
                // F-255 / SF-255-01 — le tour vocal de l'énoncé.
                .turnId(meta == null ? null : fr.claudegateway.jarvis.trace.JarvisTurnIds.clean(meta.turnId()))
                .build());
        trim(userId);
        return true;
    }

    /** Les captures du compte, les plus récentes d'abord. */
    public List<CaptureView> list(UUID userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(c -> new CaptureView(c.getId(), c.getCreatedAt(), c.getSeconds(), c.getListenMode(),
                        c.getProvider(), c.getStreamText(), c.getFinalText(), c.getCorrectedText()))
                .toList();
    }

    /** F-255 / SF-255-04 — l'audio d'une capture du compte et son type (selon l'extension rangée), ou vide. */
    public Optional<AudioFile> audioFile(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId).flatMap(c -> storage.getFile(c.getStorageKey())
                .map(bytes -> new AudioFile(bytes, c.getStorageKey().endsWith(".webm") ? WEBM : WAV)));
    }

    /** L'audio d'une capture du compte, ou vide (autrui, effacée). */
    public Optional<byte[]> audio(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId).flatMap(c -> storage.getFile(c.getStorageKey()));
    }

    /** F-239 / SF-239-06 — une correction ne vaut que pour la capture qu'elle suit de près. */
    static final Duration CORRECTION_WINDOW = Duration.ofMinutes(2);

    /**
     * F-239 / SF-239-06 — la correction du PO devient la référence du banc pour sa <b>dernière</b> capture, si elle date
     * de moins de deux minutes. Rend vrai si une capture a été corrigée.
     */
    public boolean correctLatest(UUID userId, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        List<JarvisAudioCapture> mine = repository.findByUserIdOrderByCreatedAtDesc(userId);
        if (mine.isEmpty() || mine.get(0).getCreatedAt().isBefore(OffsetDateTime.now(clock).minus(CORRECTION_WINDOW))) {
            return false;
        }
        JarvisAudioCapture latest = mine.get(0);
        latest.setCorrectedText(cut(text, MAX_TEXT));
        repository.save(latest);
        return true;
    }

    /** Efface toutes les captures du compte (extinction de l'interrupteur, suppression du compte). */
    public int purgeUser(UUID userId) {
        List<JarvisAudioCapture> all = repository.findByUserIdOrderByCreatedAtDesc(userId);
        all.forEach(this::delete);
        return all.size();
    }

    /** Toutes les heures : les captures de plus de 7 jours partent (objet puis ligne). */
    @Scheduled(fixedDelayString = "${app.jarvis.capture.purge-ms:3600000}", initialDelayString = "PT2M")
    public void purgeExpired() {
        OffsetDateTime limit = OffsetDateTime.now(clock).minus(RETENTION);
        List<JarvisAudioCapture> old = repository.findByCreatedAtBefore(limit);
        old.forEach(this::delete);
        if (!old.isEmpty()) {
            log.info("Captures d'énoncés expirées effacées : {}", old.size());
        }
    }

    private void trim(UUID userId) {
        List<JarvisAudioCapture> all = repository.findByUserIdOrderByCreatedAtDesc(userId);
        if (all.size() > MAX_PER_USER) {
            all.subList(MAX_PER_USER, all.size()).forEach(this::delete);
        }
    }

    private void delete(JarvisAudioCapture capture) {
        try {
            storage.deleteFile(capture.getStorageKey());
        } catch (RuntimeException e) {
            log.warn("Objet de capture non effacé ({}) : la ligne reste pour un nouvel essai",
                    e.getClass().getSimpleName());
            return;
        }
        repository.delete(capture);
    }

    private static String mode(String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip().toUpperCase(java.util.Locale.ROOT);
        return MODES.contains(v) ? v : null;
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        String v = value.strip();
        return v.length() <= max ? v : v.substring(0, max);
    }
}
