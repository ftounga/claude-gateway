package fr.claudegateway.jarvis.calendar;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <b>Un parcours OAuth en cours</b> (F-243 / SF-243-03, D3) — anti-CSRF : le paramètre {@code state} (aléatoire,
 * 256 bits) n'est gardé que par son empreinte SHA-256, lié au {@code user_id} qui l'a demandé <b>et</b> au navigateur
 * (empreinte d'un témoin {@code HttpOnly} posé au départ). Usage unique, 10 minutes. Le vérificateur PKCE est scellé.
 */
@Entity
@Table(name = "jarvis_calendar_oauth_states")
@Getter
@Setter
@NoArgsConstructor
public class JarvisCalendarOAuthState {

    @Id
    @Column(name = "state_hash", nullable = false, length = 64, updatable = false)
    private String stateHash;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "browser_hash", nullable = false, length = 64, updatable = false)
    private String browserHash;

    /** Le vérificateur PKCE, scellé ({@code dataKey.iv.ciphertext}). */
    @Column(name = "verifier_sealed", nullable = false, length = 4000, updatable = false)
    private String verifierSealed;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private OffsetDateTime expiresAt;
}
