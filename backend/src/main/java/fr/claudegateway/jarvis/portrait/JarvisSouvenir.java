package fr.claudegateway.jarvis.portrait;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <b>Un souvenir</b> (F-241 / SF-241-01, D2) — une phrase courte, datée, retenue avec le consentement du PO. Le texte
 * est <b>scellé</b> ; toute lecture filtre sur {@link #userId}.
 */
@Entity
@Table(name = "jarvis_souvenirs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JarvisSouvenir {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "text_enc", nullable = false, length = 4000)
    private String textEnc;

    @Column(name = "sensitive", nullable = false)
    private boolean sensitive;

    @Column(name = "noted_on", nullable = false)
    private LocalDate notedOn;

    /** {@code VOIX}, {@code ECRAN} ou {@code ENTRETIEN}. */
    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
