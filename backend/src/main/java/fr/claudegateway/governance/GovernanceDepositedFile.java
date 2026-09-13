package fr.claudegateway.governance;

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
 * <b>L'empreinte de ce qu'on a déposé</b>, à un endroit précis (F-96 / SF-96-01).
 *
 * <p><b>Pourquoi cette table existe.</b> Le dépôt n'avait que deux issues — créer ce qui manque, ou
 * laisser tel quel — et ne gardait donc aucune trace. Pour mettre à jour un artefact <b>sans jamais
 * écraser du contenu utilisateur</b>, il faut pouvoir répondre à une question que le modèle ne
 * portait nulle part : <i>le fichier présent est-il celui que nous y avions mis ?</i>
 * {@code governance_host_activations} retient une <b>version de paquet</b>, pas un contenu ; c'est
 * donc ici, et une ligne par <b>destination</b> — le même fichier n'a pas le même sort dans deux
 * dossiers.</p>
 *
 * <p><b>Une empreinte, pas une copie.</b> Le contenu de l'utilisateur ne quitte jamais sa machine :
 * on retient 64 caractères hexadécimaux, dont on ne peut rien reconstituer. C'est aussi ce qui rend
 * la table minuscule là où une copie ferait de la gateway un dépôt de fichiers clients.</p>
 *
 * <p><b>L'absence d'empreinte n'autorise rien.</b> Un fichier présent sans ligne ici est traité en
 * contenu utilisateur — conservé — parce qu'on ne sait pas d'où il vient. Le doute n'écrit jamais.
 * (Le rattrapage des dépôts d'avant F-96 arrive en SF-96-02, par les empreintes <b>publiées</b>.)</p>
 *
 * <p><b>Isolation.</b> {@link #userId} est en tête de l'index d'unicité
 * {@code (user_id, host_id, workspace_id, package_id, path)} et aucune lecture n'existe sans lui.
 * Le poste, lui, est vérifié possédé en amont par {@link GovernanceHostScope}.</p>
 */
@Entity
@Table(name = "governance_deposited_files")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GovernanceDepositedFile {

    /**
     * Clé réservée du dépôt fait à la <b>racine du poste</b> — la carte (F-92), qui n'appartient à
     * aucun dossier.
     *
     * <p>Une colonne nulle ne dédoublonnerait rien sous PostgreSQL : la racine a donc une clé, comme
     * le poste virtuel « Hébergé » en a une ({@link GovernanceHostRef#HOSTED_ID}).</p>
     */
    public static final UUID ROOT_SCOPE = new UUID(0L, 0L);

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire (= {@code users.id}). Filtre d'isolation obligatoire. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Poste destinataire (= {@code runner_hosts.id}, ou la clé du poste « Hébergé »). */
    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    /** Dossier destinataire (= {@code workspaces.id}), ou {@link #ROOT_SCOPE} pour la racine. */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    /** Paquet qui a déposé (= {@code governance_packages.id}). */
    @Column(name = "package_id", nullable = false, updatable = false)
    private UUID packageId;

    /** Chemin déposé, déjà canonique ({@link GovernancePath}). */
    @Column(name = "path", nullable = false, updatable = false, length = GovernancePath.MAX_LENGTH)
    private String path;

    /** Empreinte sha-256 du contenu déposé, fins de ligne normalisées ({@link GovernanceDigest}). */
    @Column(name = "digest", nullable = false, length = GovernanceDigest.LENGTH)
    private String digest;

    /** Version du paquet au moment du dépôt — pour lire une ligne sans la décoder. */
    @Column(name = "package_version", nullable = false)
    private int packageVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
