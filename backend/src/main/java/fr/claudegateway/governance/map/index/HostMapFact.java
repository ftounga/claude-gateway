package fr.claudegateway.governance.map.index;

import java.time.LocalDate;
import java.util.UUID;

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
 * <b>Un fait de la carte</b> : une ligne porteuse d'une section (F-174 / SF-174-02).
 *
 * <p>Le texte est celui de la carte, mot pour mot : l'index ne reformule rien (D1). Ce qu'il ajoute
 * est la nature du fait (piège, échéance), sa date de constat, son échéance et ses identifiants
 * exacts.</p>
 *
 * <p>La colonne {@code embedding} (PostgreSQL seulement, SF-174-03) n'est pas mappée : elle se lit
 * et s'écrit en SQL natif.</p>
 */
@Entity
@Table(name = "host_map_facts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HostMapFact {

    public static final String FAIT = "FAIT";
    public static final String PIEGE = "PIEGE";
    public static final String ECHEANCE = "ECHEANCE";

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(name = "section_id", nullable = false, updatable = false)
    private UUID sectionId;

    @Column(name = "path", nullable = false, length = 500, updatable = false)
    private String path;

    @Column(name = "heading", length = 500)
    private String heading;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "text", nullable = false, columnDefinition = "text")
    private String text;

    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "due_on")
    private LocalDate dueOn;

    @Column(name = "observed_on")
    private LocalDate observedOn;

    @Column(name = "identifiers", columnDefinition = "text")
    private String identifiers;
}
