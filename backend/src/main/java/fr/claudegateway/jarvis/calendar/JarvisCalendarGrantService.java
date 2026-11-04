package fr.claudegateway.jarvis.calendar;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.byok.ByokKeyCipher;
import fr.claudegateway.byok.EncryptedKey;
import fr.claudegateway.jarvis.portrait.PortraitSeal;

/**
 * <b>Relier et révoquer l'agenda pro</b> (F-243 / SF-243-03, D3) — le parcours OAuth du client Internal.
 *
 * <p><b>Anti-CSRF</b> : le {@code state} (256 bits aléatoires) n'est gardé que par son empreinte, lié au {@code user_id}
 * qui l'a demandé <b>et</b> au navigateur (empreinte d'un témoin {@code HttpOnly} posé au départ) ; usage unique,
 * 10 minutes ; PKCE S256. Un {@code state} d'un autre utilisateur, d'un autre navigateur, expiré ou déjà servi est
 * refusé. Le jeton de rafraîchissement est chiffré ({@link ByokKeyCipher}), jamais renvoyé ni journalisé.</p>
 */
@Service
public class JarvisCalendarGrantService {

    private static final Logger log = LoggerFactory.getLogger(JarvisCalendarGrantService.class);

    static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Le départ du parcours : l'adresse de consentement et le témoin à poser dans le navigateur. */
    public record Start(String url, String browserNonce) {
    }

    /** La vue d'un droit : jamais le jeton. */
    public record View(UUID id, String account, String clientKind, OffsetDateTime grantedAt) {
    }

    /** Le parcours OAuth est refusé (state inconnu, autre navigateur, expiré, Google a refusé). */
    public static class OAuthRefusedException extends RuntimeException {
        public OAuthRefusedException(String message) {
            super(message);
        }
    }

    /** Le client OAuth n'est pas configuré (action du PO dans la console Google Cloud). */
    public static class NotConfiguredException extends RuntimeException {
        public NotConfiguredException() {
            super("L'écriture dans l'agenda pro n'est pas encore configurée.");
        }
    }

    /** Aucun agenda pro relié (ou droit révoqué chez Google). */
    public static class NoGrantException extends RuntimeException {
        public NoGrantException() {
            super("Agenda pro non relié.");
        }
    }

    private record CachedAccess(String token, OffsetDateTime until) {
    }

    private final JarvisCalendarGrantRepository grants;
    private final JarvisCalendarOAuthStateRepository states;
    private final JarvisCalendarActionRepository actions;
    private final GoogleCalendarApi google;
    private final JarvisCalendarOAuthProperties properties;
    private final ByokKeyCipher cipher;
    private final PortraitSeal seal;
    private final Clock clock;
    private final Map<UUID, CachedAccess> accessCache = new ConcurrentHashMap<>();

    public JarvisCalendarGrantService(JarvisCalendarGrantRepository grants, JarvisCalendarOAuthStateRepository states,
            JarvisCalendarActionRepository actions, GoogleCalendarApi google, JarvisCalendarOAuthProperties properties,
            ByokKeyCipher cipher, PortraitSeal seal, Clock clock) {
        this.grants = grants;
        this.states = states;
        this.actions = actions;
        this.google = google;
        this.properties = properties;
        this.cipher = cipher;
        this.seal = seal;
        this.clock = clock;
    }

    public boolean configured() {
        return properties.configured();
    }

    /** Démarre le parcours pour {@code userId} : rend l'adresse de consentement et le témoin du navigateur. */
    @Transactional
    public Start start(UUID userId) {
        if (!configured()) {
            throw new NotConfiguredException();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        states.purgeExpired(now);
        String state = random();
        String browser = random();
        String verifier = random() + random();
        JarvisCalendarOAuthState s = new JarvisCalendarOAuthState();
        s.setStateHash(sha256(state));
        s.setUserId(userId);
        s.setBrowserHash(sha256(browser));
        s.setVerifierSealed(seal.seal(verifier));
        s.setCreatedAt(now);
        s.setExpiresAt(now.plus(STATE_TTL));
        states.save(s);
        return new Start(google.authorizationUrl(state, challenge(verifier)), browser);
    }

    /**
     * Le retour de Google : vérifie le {@code state} (empreinte connue, même navigateur, non expiré, usage unique),
     * échange le code, chiffre le jeton de rafraîchissement. Rend le {@code user_id} relié. <b>Sans transaction
     * englobante</b> : la consommation du {@code state} est définitive même quand la suite échoue (aucun rejeu).
     */
    public UUID callback(String rawState, String code, String error, String browserNonce) {
        if (rawState == null || rawState.isBlank()) {
            throw new OAuthRefusedException("Parcours inconnu.");
        }
        String hash = sha256(rawState);
        JarvisCalendarOAuthState s = states.findById(hash).orElse(null);
        // Usage unique : consommé avant tout autre contrôle (un rejeu ne trouve plus rien).
        if (s == null || states.consume(hash) != 1) {
            throw new OAuthRefusedException("Parcours inconnu ou déjà servi.");
        }
        if (!s.getExpiresAt().isAfter(OffsetDateTime.now(clock))) {
            throw new OAuthRefusedException("Parcours expiré.");
        }
        if (browserNonce == null || !MessageDigest.isEqual(sha256(browserNonce).getBytes(StandardCharsets.UTF_8),
                s.getBrowserHash().getBytes(StandardCharsets.UTF_8))) {
            throw new OAuthRefusedException("Parcours commencé dans un autre navigateur.");
        }
        if (error != null && !error.isBlank()) {
            throw new OAuthRefusedException("Google n'a pas donné l'accord.");
        }
        if (code == null || code.isBlank()) {
            throw new OAuthRefusedException("Google n'a pas rendu de code.");
        }
        GoogleCalendarApi.Tokens tokens = google.exchange(code, seal.open(s.getVerifierSealed()));
        if (!tokens.scope().contains("calendar.events")) {
            google.revoke(tokens.refreshToken());
            throw new OAuthRefusedException("L'accès à l'agenda n'a pas été accordé.");
        }
        UUID userId = s.getUserId();
        String account = tokens.email().isBlank() ? "agenda pro" : tokens.email().toLowerCase(Locale.ROOT);
        JarvisCalendarGrant g = grants.findByUserIdAndAccount(userId, account).orElseGet(JarvisCalendarGrant::new);
        EncryptedKey e = cipher.encrypt(tokens.refreshToken());
        g.setUserId(userId);
        g.setAccount(account);
        g.setClientKind(JarvisCalendarGrant.ClientKind.INTERNAL);
        g.setEncryptedDataKey(e.encryptedDataKey());
        g.setCipherIv(e.iv());
        g.setCiphertext(e.ciphertext());
        g.setScope(tokens.scope().length() <= 500 ? tokens.scope() : tokens.scope().substring(0, 500));
        g.setGrantedAt(OffsetDateTime.now(clock));
        g.setRevokedAt(null);
        JarvisCalendarGrant saved = grants.save(g);
        accessCache.put(saved.getId(), new CachedAccess(tokens.accessToken(),
                OffsetDateTime.now(clock).plusSeconds(Math.max(0, tokens.expiresIn() - 60))));
        log.info("Agenda : agenda pro relié pour l'utilisateur {}", userId);
        return userId;
    }

    @Transactional(readOnly = true)
    public List<View> list(UUID userId) {
        return grants.findByUserIdAndRevokedAtIsNullOrderByGrantedAtAsc(userId).stream()
                .map(g -> new View(g.getId(), g.getAccount(), g.getClientKind().name(), g.getGrantedAt())).toList();
    }

    /** Le droit actif du compte (le premier relié), s'il existe. */
    @Transactional(readOnly = true)
    public Optional<JarvisCalendarGrant> active(UUID userId) {
        return grants.findByUserIdAndRevokedAtIsNullOrderByGrantedAtAsc(userId).stream().findFirst();
    }

    /** Révoque : chez Google d'abord (au mieux), puis efface le jeton et les actions en attente. */
    @Transactional
    public void revoke(UUID userId, UUID grantId) {
        JarvisCalendarGrant g = grants.findByIdAndUserId(grantId, userId)
                .orElseThrow(JarvisCalendarExceptions.FeedNotFoundException::new);
        try {
            google.revoke(refreshToken(g));
        } catch (RuntimeException e) {
            log.info("Agenda : révocation Google non confirmée ({})", e.getClass().getSimpleName());
        }
        accessCache.remove(g.getId());
        actions.purgeGrant(userId, g.getId());
        grants.delete(g);
        log.info("Agenda : agenda pro révoqué pour l'utilisateur {}", userId);
    }

    /**
     * Exécute {@code call} avec un jeton d'accès valable du droit {@code grant} : un 401 rafraîchit le jeton puis
     * réessaie une fois ; un droit révoqué chez Google est effacé et lève {@link NoGrantException}.
     */
    public <T> T withAccess(JarvisCalendarGrant grant, Function<String, T> call) {
        try {
            return call.apply(access(grant, false));
        } catch (GoogleCalendarApi.UnauthorizedException e) {
            return call.apply(access(grant, true));
        }
    }

    private String access(JarvisCalendarGrant grant, boolean force) {
        CachedAccess cached = accessCache.get(grant.getId());
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!force && cached != null && cached.until().isAfter(now)) {
            return cached.token();
        }
        try {
            GoogleCalendarApi.Access fresh = google.refresh(refreshToken(grant));
            accessCache.put(grant.getId(), new CachedAccess(fresh.accessToken(),
                    now.plusSeconds(Math.max(0, fresh.expiresIn() - 60))));
            return fresh.accessToken();
        } catch (GoogleCalendarApi.GrantRevokedException e) {
            accessCache.remove(grant.getId());
            grants.findByIdAndUserId(grant.getId(), grant.getUserId()).ifPresent(grants::delete);
            throw new NoGrantException();
        }
    }

    @Transactional
    public void purgeUser(UUID userId) {
        grants.findByUserIdAndRevokedAtIsNullOrderByGrantedAtAsc(userId).forEach(g -> accessCache.remove(g.getId()));
        actions.purgeUser(userId);
        states.purgeUser(userId);
        grants.purgeUser(userId);
    }

    /** Tests : oublie les jetons d'accès gardés en mémoire (le prochain appel rafraîchit). */
    void purgeCacheForTests() {
        accessCache.clear();
    }

    private String refreshToken(JarvisCalendarGrant g) {
        return cipher.decrypt(new EncryptedKey(g.getEncryptedDataKey(), g.getCipherIv(), g.getCiphertext()));
    }

    static String random() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(String s) {
        return JarvisIcsParser.sha256(s.getBytes(StandardCharsets.UTF_8));
    }
}
