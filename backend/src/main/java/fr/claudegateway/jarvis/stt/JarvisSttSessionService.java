package fr.claudegateway.jarvis.stt;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import fr.claudegateway.jarvis.JarvisUsageService;
import fr.claudegateway.jarvis.JarvisVoiceBudgetException;
import fr.claudegateway.jarvis.JarvisVoiceService;
import fr.claudegateway.stt.stream.StreamingTranscriptionProvider;
import fr.claudegateway.teams.meeting.stt.TranscriptionProviderUnavailableException;

/**
 * <b>Les sessions d'écoute en flux de Jarvis</b> (F-233 / SF-233-04, D5, D13).
 *
 * <p>Émet, pour le compte connecté, une session chez le fournisseur retenu (jeton éphémère : la clé ne quitte jamais
 * le serveur), après avoir vérifié le plafond. La session est <b>comptée à la durée</b> : à sa clôture par l'écran
 * (durée déclarée, bornée par le temps réellement écoulé et par le maximum), ou par le balayage si l'écran ne l'a
 * jamais close (comptée au maximum). Rien de ce qui est dit ne passe par la passerelle.</p>
 */
@Service
public class JarvisSttSessionService {

    private static final Logger log = LoggerFactory.getLogger(JarvisSttSessionService.class);

    /** Marge accordée entre l'émission et la clôture (réseau, horloges). */
    static final Duration SLACK = Duration.ofSeconds(5);
    /** Au-delà de la durée maximale + ce délai, une session non close est comptée par le balayage. */
    static final Duration SWEEP_AFTER = Duration.ofSeconds(60);

    private final List<StreamingTranscriptionProvider> providers;
    private final JarvisSttStreamProperties properties;
    private final JarvisVoiceService voice;
    private final JarvisUsageService usage;
    private final Clock clock;
    private final Map<UUID, OpenSession> open = new ConcurrentHashMap<>();
    /** F-239 / SF-239-04 — le vocabulaire du compte, aussi donné à l'écoute en flux (facultatif). */
    private JarvisVocabulary vocabulary;

    public JarvisSttSessionService(List<StreamingTranscriptionProvider> providers, JarvisSttStreamProperties properties,
            JarvisVoiceService voice, JarvisUsageService usage, Clock clock) {
        this.providers = providers;
        this.properties = properties;
        this.voice = voice;
        this.usage = usage;
        this.clock = clock;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setVocabulary(JarvisVocabulary vocabulary) {
        this.vocabulary = vocabulary;
    }

    /**
     * Ouvre une session d'écoute en flux pour le compte.
     *
     * @throws TranscriptionProviderUnavailableException flux éteint ou non configuré (aucun appel sortant)
     * @throws JarvisVoiceBudgetException                plafond du mois atteint (aucun appel sortant)
     * @throws fr.claudegateway.teams.meeting.stt.TranscriptionProviderException échec du fournisseur
     */
    public Ticket open(UUID userId) {
        StreamingTranscriptionProvider provider = provider();
        if (voice.status(userId).budgetReached()) {
            throw new JarvisVoiceBudgetException();
        }
        StreamingTranscriptionProvider.StreamingSession session =
                provider.openStreamingSession(properties.language(), properties.vadSilenceSeconds(),
                        vocabulary == null ? List.of() : vocabulary.of(userId));
        UUID id = UUID.randomUUID();
        open.put(id, new OpenSession(userId, clock.instant()));
        return new Ticket(id, session.provider(), session.url(), session.token(), session.expiresAt(),
                session.sampleRate(), properties.maxSessionSeconds());
    }

    /**
     * Clôt la session et la compte : {@code min(secondes déclarées, écoulé + marge, maximum)}.
     *
     * @throws NoSuchElementException session inconnue, déjà close, ou d'un autre compte
     */
    public void close(UUID userId, UUID sessionId, Long seconds) {
        OpenSession session = sessionId == null ? null : open.get(sessionId);
        if (session == null || !session.userId().equals(userId) || !open.remove(sessionId, session)) {
            throw new NoSuchElementException("Session inconnue.");
        }
        long elapsed = Duration.between(session.openedAt(), clock.instant()).plus(SLACK).toSeconds();
        account(userId, billedSeconds(seconds, elapsed, properties.maxSessionSeconds()));
    }

    /** Compte au maximum les sessions jamais closes (écran fermé, réseau coupé). */
    @Scheduled(fixedDelayString = "${app.jarvis.stt-stream.sweep-ms:60000}")
    public void sweep() {
        Instant limit = clock.instant().minusSeconds(properties.maxSessionSeconds()).minus(SWEEP_AFTER);
        open.forEach((id, session) -> {
            if (session.openedAt().isBefore(limit) && open.remove(id, session)) {
                account(session.userId(), properties.maxSessionSeconds());
            }
        });
    }

    /** Nombre de sessions ouvertes (supervision, tests). */
    int openCount() {
        return open.size();
    }

    /** La durée comptée : déclarée, bornée par 0, l'écoulé et le maximum. */
    static long billedSeconds(Long declared, long elapsed, int max) {
        long value = declared == null ? 0L : declared;
        return Math.max(0L, Math.min(value, Math.min(elapsed, max)));
    }

    /** Le coût en dollars de {@code seconds} secondes de session. */
    BigDecimal costUsd(long seconds) {
        return properties.costUsdPerMinute().multiply(BigDecimal.valueOf(seconds))
                .divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP);
    }

    private void account(UUID userId, long seconds) {
        usage.addStream(userId, seconds, costUsd(seconds));
        log.debug("Écoute en flux comptée ({} s)", seconds);
    }

    private StreamingTranscriptionProvider provider() {
        if (!properties.enabled()) {
            throw new TranscriptionProviderUnavailableException("L'écoute en flux est éteinte.");
        }
        return providers.stream()
                .filter(p -> properties.provider().equals(p.name()) && p.isConfigured())
                .findFirst()
                .orElseThrow(() -> new TranscriptionProviderUnavailableException(
                        "L'écoute en flux n'est pas configurée."));
    }

    private record OpenSession(UUID userId, Instant openedAt) {
    }

    /** Ce que l'écran reçoit : de quoi parler directement au fournisseur. Jamais la clé. */
    public record Ticket(UUID sessionId, String provider, String url, String token, Instant expiresAt, int sampleRate,
            int maxSeconds) {
    }
}
