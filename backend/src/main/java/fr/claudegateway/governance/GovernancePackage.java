package fr.claudegateway.governance;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
 * Un <b>paquet de gouvernance</b> (F-51 / SF-51-01) : ce que l'admin publie, et ce qu'un utilisateur
 * retient puis active sur ses projets.
 *
 * <p>Un paquet apporte quatre choses, et rien d'autre : des <b>règles</b> (texte ajouté à la consigne
 * système), des <b>contrôles</b> (identifiants de composants du serveur, branchés sur les crochets de
 * F-50), des <b>gabarits</b> et des <b>skills</b> — ces deux derniers étant des fichiers déposés dans
 * le projet, portés par {@link GovernancePackageFile}.</p>
 *
 * <p><b>Pas de {@code user_id}, et c'est voulu.</b> Un paquet est un contenu <i>produit</i>, comme un
 * plan tarifaire : il n'appartient au dossier de personne, l'écriture est réservée à l'admin, et la
 * lecture publique est bornée aux paquets {@link #published}. Tout ce qui appartient réellement à un
 * utilisateur — sa sélection, ses activations — vit dans d'autres tables et porte {@code user_id} sur
 * chaque lecture.</p>
 *
 * <p>{@link #version} est incrémenté à chaque modification du contenu. Il sert à dire « ce projet
 * applique la v2, le paquet est en v3 » sans imposer de mise à jour automatique à personne
 * (décision D5 du cadrage).</p>
 */
@Entity
@Table(name = "governance_packages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GovernancePackage {

    /** Longueur maximale de l'identifiant lisible et stable d'un paquet. */
    public static final int MAX_SLUG_LENGTH = 64;

    /** Longueur minimale du même identifiant : en dessous, il ne désigne rien. */
    public static final int MIN_SLUG_LENGTH = 3;

    /** Longueur maximale du nom affiché. */
    public static final int MAX_NAME_LENGTH = 120;

    /** Longueur maximale du résumé — une ou deux phrases, pas une notice. */
    public static final int MAX_SUMMARY_LENGTH = 500;

    /**
     * Longueur maximale des règles. Ce texte part dans la <b>consigne système</b> de chaque tour :
     * au-delà, il coûterait à chaque appel et noierait les conventions du projet lui-même.
     */
    public static final int MAX_RULES_LENGTH = 8_000;

    /** Longueur de stockage de la liste d'identifiants de contrôles, séparés par des virgules. */
    public static final int MAX_CONTROL_IDS_LENGTH = 1_000;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Identifiant lisible et <b>immuable</b> ({@code gouvernance-livrables}). Immuable parce qu'il
     * est ce qui permet de reconnaître un paquet d'une version à l'autre, y compris à l'œil nu dans
     * un journal.
     */
    @Column(name = "slug", nullable = false, unique = true, length = MAX_SLUG_LENGTH, updatable = false)
    private String slug;

    @Column(name = "name", nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    @Column(name = "summary", length = MAX_SUMMARY_LENGTH)
    private String summary;

    /** Les règles, telles qu'elles seront ajoutées à la consigne système (SF-51-04). */
    @Column(name = "rules", columnDefinition = "text")
    private String rules;

    /**
     * Identifiants de contrôles, séparés par des virgules. Stockés à plat : la liste est courte,
     * bornée, lue en bloc et jamais interrogée par élément — une table de jointure n'apporterait
     * qu'une lecture de plus.
     */
    @Column(name = "control_ids", length = MAX_CONTROL_IDS_LENGTH)
    private String controlIds;

    /** Version du contenu, à partir de 1, incrémentée à chaque modification. */
    @Column(name = "version", nullable = false)
    private int version;

    /** Publié = visible du catalogue de tous. Non publié = n'existe pour personne d'autre que l'admin. */
    @Column(name = "published", nullable = false)
    private boolean published;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Les identifiants de contrôles, éclatés — jamais {@code null}, sans doublon, dans l'ordre. */
    public List<String> controlIdList() {
        return splitControlIds(controlIds);
    }

    /** Recompose la colonne à plat à partir d'une liste. */
    public void setControlIdList(List<String> ids) {
        this.controlIds = joinControlIds(ids);
    }

    /** Éclate une liste stockée à plat ; tolère {@code null}, les blancs et les doublons. */
    public static List<String> splitControlIds(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (String part : stored.split(",")) {
            String id = part.trim();
            if (!id.isEmpty() && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    /** Recompose la forme stockée ; {@code null} si la liste ne porte rien. */
    public static String joinControlIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(ids));
        unique.removeIf(id -> id == null || id.isBlank());
        return unique.isEmpty() ? null : String.join(",", unique);
    }
}
