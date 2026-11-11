package fr.claudegateway.jarvis.voicestream;

import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserService;
import io.jsonwebtoken.Claims;

/**
 * <b>Le ticket de session vocale</b> (F-257 / SF-257-02, ADR-021) : la passerelle l'émet à l'écran (utilisateur connecté,
 * sujet vérifié sous son {@code user_id}) ; le service {@code jarvis-voice} le présente ensuite pour agir <b>au nom de
 * cet utilisateur</b>, sur la seule liste fermée {@link fr.claudegateway.auth.VoiceTicketScope}. Le service ne détient
 * aucun secret : il vérifie le ticket par {@code GET /jarvis/voice-session/me}. Le ticket n'est jamais journalisé.
 */
@Service
public class JarvisVoiceSessionService {

    /** Fréquence d'échantillonnage attendue du micro (PCM 16 bits mono). */
    public static final int SAMPLE_RATE = 16_000;

    private final JarvisVoiceStreamProperties properties;
    private final JwtService jwt;
    private final UserService users;
    private final WorkspaceRepository workspaces;
    private final Clock clock;

    public JarvisVoiceSessionService(JarvisVoiceStreamProperties properties, JwtService jwt, UserService users,
            WorkspaceRepository workspaces, Clock clock) {
        this.properties = properties;
        this.jwt = jwt;
        this.users = users;
        this.workspaces = workspaces;
        this.clock = clock;
    }

    /**
     * Émet un ticket pour {@code userId}.
     *
     * @throws VoiceStreamDisabledException coupe-circuit fermé
     * @throws NoSuchElementException       sujet inconnu ou d'un autre utilisateur
     */
    @Transactional(readOnly = true)
    public VoiceTicket issue(UUID userId, UUID workspaceId) {
        if (!properties.isEnabled()) {
            throw new VoiceStreamDisabledException();
        }
        UUID subject = null;
        if (workspaceId != null) {
            subject = workspaces.findByIdAndUserId(workspaceId, userId).map(w -> w.getId())
                    .orElseThrow(() -> new NoSuchElementException("Sujet inconnu."));
        }
        User user = users.findByIdOrThrow(userId);
        Instant expiresAt = clock.instant().plus(properties.ticketTtl());
        String ticket = jwt.generateVoiceTicket(user, subject, expiresAt);
        return new VoiceTicket(ticket, properties.publicUrl(), expiresAt, SAMPLE_RATE);
    }

    /**
     * Ce que dit un ticket déjà authentifié par le filtre : {@code null} si le jeton présenté n'est pas un ticket de
     * session vocale.
     */
    public VoiceIdentity identify(String bearer) {
        if (bearer == null) {
            return null;
        }
        Claims claims;
        try {
            claims = jwt.parseClaims(bearer);
        } catch (RuntimeException e) {
            return null;
        }
        if (!JwtService.SCOPE_JARVIS_VOICE.equals(claims.get(JwtService.CLAIM_SCOPE, String.class))) {
            return null;
        }
        String ws = claims.get(JwtService.CLAIM_VOICE_WORKSPACE, String.class);
        return new VoiceIdentity(UUID.fromString(claims.getSubject()), ws == null ? null : UUID.fromString(ws),
                claims.getExpiration() == null ? null : claims.getExpiration().toInstant());
    }

    /** Le ticket émis à l'écran. */
    public record VoiceTicket(String ticket, String url, Instant expiresAt, int sampleRate) {
    }

    /** L'identité portée par un ticket, rendue au service vocal. */
    public record VoiceIdentity(UUID userId, UUID workspaceId, Instant expiresAt) {
    }

    /** Le coupe-circuit {@code APP_JARVIS_VOICE_STREAM_ENABLED} est fermé. */
    public static class VoiceStreamDisabledException extends RuntimeException {
        public VoiceStreamDisabledException() {
            super("La boucle vocale en flux n'est pas activée.");
        }
    }
}
