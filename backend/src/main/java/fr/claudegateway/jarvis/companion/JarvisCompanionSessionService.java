package fr.claudegateway.jarvis.companion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserService;

/**
 * <b>La session Jarvis du compagnon</b> (F-232 / SF-232-05, D7 de l'amendement du 2026-11-04). Le compagnon échange son
 * <b>jeton dédié</b> (qui ne quitte jamais le code natif) contre un JWT court à portée {@code jarvis-compagnon}, que la
 * WebView reçoit par le pont natif. La session porte le compagnon (révocable avec lui) et l'état verrouillé du
 * téléphone à l'émission (SF-232-06) ; chaque émission est tracée pour la mesure J+7 ({@link JarvisCompanionOpening}).
 */
@Service
public class JarvisCompanionSessionService {

    private static final Logger log = LoggerFactory.getLogger(JarvisCompanionSessionService.class);

    private final JarvisCompanionService companions;
    private final UserService users;
    private final JwtService jwt;
    private final JarvisCompanionOpeningRepository openings;
    private final Clock clock;
    private final Duration ttl;

    public JarvisCompanionSessionService(JarvisCompanionService companions, UserService users, JwtService jwt,
            JarvisCompanionOpeningRepository openings, Clock clock,
            @Value("${app.jarvis.companion.session-ttl:PT30M}") Duration ttl) {
        this.companions = companions;
        this.users = users;
        this.jwt = jwt;
        this.openings = openings;
        this.clock = clock;
        this.ttl = ttl;
    }

    /**
     * Une session pour le compagnon de ce jeton ; vide si le jeton est refusé (inconnu, révoqué, expiré) ou le compte
     * introuvable. {@code locked} absent vaut <b>verrouillé</b> (défaut sûr).
     */
    @Transactional
    public Optional<Session> open(String rawCompanionToken, Boolean locked, JarvisCompanionOpening.Purpose purpose) {
        Optional<JarvisCompanion> companion = companions.authenticate(rawCompanionToken);
        if (companion.isEmpty()) {
            return Optional.empty();
        }
        JarvisCompanion c = companion.get();
        Optional<User> user = users.findById(c.getUserId());
        if (user.isEmpty()) {
            return Optional.empty();
        }
        boolean isLocked = locked == null || locked;
        JarvisCompanionOpening.Purpose why = purpose == null ? JarvisCompanionOpening.Purpose.OPEN : purpose;
        Instant expiresAt = clock.instant().plus(ttl);
        String token = jwt.generateCompanionToken(user.get(), c.getId(), isLocked, expiresAt);
        openings.save(JarvisCompanionOpening.builder().userId(c.getUserId()).companionId(c.getId()).purpose(why)
                .locked(isLocked).build());
        // Mesure J+7 (aussi dans CloudWatch) : jamais le jeton, jamais de contenu.
        log.info("jarvis_companion_session purpose={} locked={} companion={}", why, isLocked, c.getId());
        return Optional.of(new Session(token, expiresAt, JwtService.SCOPE_JARVIS_COMPANION, isLocked));
    }

    /** La session rendue au compagnon : le JWT, son échéance, sa portée, l'état verrouillé qu'elle porte. */
    public record Session(String token, Instant expiresAt, String scope, boolean locked) {
    }
}
