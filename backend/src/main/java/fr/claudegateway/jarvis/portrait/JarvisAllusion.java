package fr.claudegateway.jarvis.portrait;

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
 * <b>Une allusion ou un trait d'humour déjà dit</b> (F-241 / SF-241-04, D11) — pour ne jamais répéter le même. Sujet et
 * phrase <b>scellés</b> ; toute lecture filtre sur {@link #userId} ; purge à 60 jours.
 */
@Entity
@Table(name = "jarvis_allusions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JarvisAllusion {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "at", nullable = false)
    private OffsetDateTime at;

    /** {@code ALLUSION} ou {@code HUMOUR}. */
    @Column(name = "kind", nullable = false, length = 8)
    private String kind;

    /** Vraie si Jarvis l'a amenée de lui-même (pas en réponse à ce que le PO a dit). */
    @Column(name = "spontaneous", nullable = false)
    private boolean spontaneous;

    @Column(name = "topic_enc", nullable = false, length = 1000)
    private String topicEnc;

    @Column(name = "phrase_enc", length = 2000)
    private String phraseEnc;
}
