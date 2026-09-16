package fr.claudegateway.activity;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Le <b>réglage de suivi d'activité</b> d'un utilisateur (F-124 / SF-124-01) : au plus une ligne par
 * {@code user_id}. Aujourd'hui, un seul champ — le <b>mois de départ</b> du cumul de revenu.
 *
 * <p>Le mois de départ est tenu en {@code 'YYYY-MM'} ({@link #startMonth}). Absente, la ligne vaut
 * le défaut applicatif {@code 2025-09} : le cumul se lit toujours, même avant tout réglage.</p>
 */
@Entity
@Table(name = "activity_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActivitySettings {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire (= {@code users.id}). Filtre d'isolation obligatoire. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Mois de départ du cumul, {@code 'YYYY-MM'}. */
    @Column(name = "start_month", nullable = false, length = 7)
    private String startMonth;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
