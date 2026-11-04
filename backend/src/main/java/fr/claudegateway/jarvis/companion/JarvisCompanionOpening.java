package fr.claudegateway.jarvis.companion;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <b>Une session Jarvis émise au compagnon</b> (F-232 / SF-232-05, D9 de l'amendement du 2026-11-04) — la trace de la
 * mesure J+7 : quand l'écran Jarvis s'ouvre dans le compagnon (verrouillé ou non), quand il est déverrouillé pour un
 * « oui », quand la session est renouvelée. Aucun contenu, aucun jeton : le compte, le compagnon, le motif, l'état.
 */
@Entity
@Table(name = "jarvis_companion_openings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JarvisCompanionOpening {

    /** Pourquoi le compagnon a demandé une session. */
    public enum Purpose {
        /** Le mot « Jarvis » (ou une réponse à une annonce) vient d'ouvrir l'écran. */
        OPEN,
        /** Le téléphone vient d'être déverrouillé pour confirmer une action (SF-232-06). */
        UNLOCK,
        /** La session en cours arrive à échéance. */
        RENEW
    }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "companion_id", nullable = false, updatable = false)
    private UUID companionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 16)
    private Purpose purpose;

    @Column(name = "locked", nullable = false)
    private boolean locked;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
