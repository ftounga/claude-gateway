package fr.claudegateway.atelier.actions;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
 * <b>Un interlocuteur d'un poste</b> (F-180 / SF-180-03, décision D2) : la personne dont on attend
 * quelque chose — normalisée, plutôt qu'un nom en texte libre écrit trois façons.
 *
 * <p><b>Isolation</b> : toute lecture filtre {@code (user_id, host_id)} ; un interlocuteur d'un autre
 * compte ou d'un autre poste est introuvable (404).</p>
 */
@Entity
@Table(name = "interlocuteurs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Interlocuteur {

    public static final int MAX_NAME = 120;
    public static final int MAX_VARIANTS = 20;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(name = "display_name", nullable = false, length = MAX_NAME)
    private String displayName;

    /** La forme normalisée du nom affiché : clé d'unicité dans le poste. */
    @Column(name = "normalized_name", nullable = false, length = MAX_NAME)
    private String normalizedName;

    /** Les autres formes reconnues, normalisées, une par ligne. */
    @Column(name = "variants", length = 2600)
    private String variants;

    @Column(name = "role", length = MAX_NAME)
    private String role;

    @Column(name = "team", length = MAX_NAME)
    private String team;

    /** La personne du Radar (F-99) quand il la connaît, sur le même poste. */
    @Column(name = "radar_person_id")
    private UUID radarPersonId;

    /** Les interlocuteurs déclarés « deux personnes » : la paire n'est plus proposée à la fusion. */
    @Column(name = "distinct_from", length = 2000)
    private String distinctFrom;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Les variantes, en liste. */
    public List<String> variantList() {
        if (variants == null || variants.isBlank()) {
            return List.of();
        }
        return Arrays.stream(variants.split("\n")).filter(v -> !v.isBlank()).toList();
    }

    /** Ajoute des variantes (sans doublon, sans le nom lui-même, bornées). */
    public void addVariants(List<String> more) {
        Set<String> all = new LinkedHashSet<>(variantList());
        for (String v : more) {
            if (v != null && !v.isBlank() && !v.equals(normalizedName)) {
                all.add(v);
            }
        }
        List<String> kept = new ArrayList<>(all);
        if (kept.size() > MAX_VARIANTS) {
            kept = kept.subList(kept.size() - MAX_VARIANTS, kept.size());
        }
        variants = kept.isEmpty() ? null : String.join("\n", kept);
    }

    /** Vrai si ce nom normalisé désigne cet interlocuteur. */
    public boolean answersTo(String normalized) {
        return normalized != null && (normalized.equals(normalizedName) || variantList().contains(normalized));
    }

    public Set<UUID> distinctSet() {
        Set<UUID> out = new LinkedHashSet<>();
        if (distinctFrom == null || distinctFrom.isBlank()) {
            return out;
        }
        for (String s : distinctFrom.split(",")) {
            try {
                out.add(UUID.fromString(s.strip()));
            } catch (IllegalArgumentException ignored) {
                // une valeur illisible ne doit pas casser la lecture
            }
        }
        return out;
    }

    public void addDistinct(UUID other) {
        Set<UUID> set = distinctSet();
        set.add(other);
        List<String> ids = set.stream().map(UUID::toString).toList();
        if (ids.size() > 50) {
            ids = ids.subList(ids.size() - 50, ids.size());
        }
        distinctFrom = String.join(",", ids);
    }
}
