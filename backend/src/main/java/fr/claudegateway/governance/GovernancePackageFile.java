package fr.claudegateway.governance;

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
 * Un fichier apporté par un paquet (F-51 / SF-51-01) : un skill ou un gabarit, avec le chemin
 * <b>relatif au projet</b> où il sera déposé.
 *
 * <p>Le chemin est validé à la publication ({@link GovernancePath}) : c'est la seule donnée d'un
 * paquet qui décide <i>où</i> quelque chose sera écrit sur la machine de quelqu'un d'autre, et il
 * vaut mieux la refuser une fois, tôt, que la voir échouer chez chaque utilisateur.</p>
 *
 * <p>{@link #position} fige l'ordre de rédaction : l'annonce faite avant l'activation doit lister les
 * fichiers dans le même ordre à chaque affichage, sinon elle se lit comme si elle avait changé.</p>
 */
@Entity
@Table(name = "governance_package_files")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GovernancePackageFile {

    /**
     * Taille maximale du contenu d'un fichier apporté. Un skill ou un gabarit se lit ; au-delà, ce
     * n'est plus un gabarit mais un livrable, et il n'a pas à voyager dans un paquet.
     */
    public static final int MAX_CONTENT_LENGTH = 64_000;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Paquet propriétaire. Pas de FK JPA : la relation est lue en bloc, jamais naviguée. */
    @Column(name = "package_id", nullable = false, updatable = false)
    private UUID packageId;

    /**
     * Rang d'affichage et de dépôt, à partir de 0. La colonne s'appelle {@code sort_order} :
     * {@code POSITION} est une fonction SQL standard, donc un mot réservé pour H2.
     */
    @Column(name = "sort_order", nullable = false)
    private int position;

    /** Chemin relatif au projet, déjà canonique ({@code .claude/skills/explique.md}). */
    @Column(name = "path", nullable = false, length = GovernancePath.MAX_LENGTH)
    private String path;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private GovernanceFileKind kind;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
