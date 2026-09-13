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

    /**
     * Vrai si ce fichier est un <b>artefact généré</b> : le produit l'a écrit, et le paquet peut le
     * <b>mettre à jour</b> tant qu'il est resté exactement celui qui a été déposé (F-96 / SF-96-01).
     *
     * <p><b>C'est le paquet qui déclare</b>, pas le genre qui décide : un skill et un gabarit sont
     * des artefacts — personne ne modifie un skill à la main dans un projet client, et un gabarit
     * encore vierge est, bit pour bit, ce que le produit a déposé. Un paquet qui veut poser un
     * fichier <b>une fois</b> puis ne plus jamais y toucher le déclare à {@code false}.</p>
     *
     * <p><b>Le drapeau n'ouvre aucune porte à lui seul.</b> Un artefact <b>modifié localement</b>
     * redevient du contenu utilisateur : il n'est plus jamais écrasé, et l'annonce le dit
     * ({@code KEEP_LOCAL}). Le défaut est donc {@code true} sans danger.</p>
     */
    @Builder.Default
    @Column(name = "generated", nullable = false)
    private boolean generated = true;

    /**
     * Les empreintes des contenus <b>antérieurs</b> publiés à ce chemin, une par ligne, les plus
     * récentes d'abord (F-96 / SF-96-02).
     *
     * <p>C'est la <b>deuxième</b> façon de reconnaître un artefact intact : un fichier dont le
     * contenu est exactement l'un de ceux que le produit a publiés ici n'a, par construction, été
     * touché par personne. Sans ce registre, la mise à jour ne toucherait que les postes activés
     * <b>après</b> F-96 — c'est-à-dire pas ceux qui portent la dette.</p>
     *
     * <p>Le format est délibérément pauvre (du texte, une empreinte par ligne) : ce registre est
     * lu avec le fichier et n'est jamais interrogé seul.</p>
     */
    @Column(name = "known_digests", length = GovernanceKnownDigests.MAX_LENGTH)
    private String knownDigests;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Les empreintes antérieures, dans l'ordre stocké. Jamais {@code null}. */
    public java.util.List<String> knownDigestList() {
        return GovernanceKnownDigests.parse(knownDigests);
    }

    /** Fixe les empreintes antérieures ; une liste vide efface le registre. */
    public void setKnownDigestList(java.util.List<String> digests) {
        this.knownDigests = GovernanceKnownDigests.join(digests);
    }
}
