package fr.claudegateway.runner.host;

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
 * Un <b>poste</b> (F-48 / SF-48-01) : une machine connectée, avec une racine et un runner, appairée
 * <b>une seule fois</b>.
 *
 * <p>C'est l'unité qui remplace le projet dans tout le mode runner. Un développeur n'a pas un
 * projet, il a une machine avec un dossier de travail dessous ; jusqu'ici chaque dossier exigeait
 * son code d'appairage, son runner et sa connexion pour la même machine. Les projets deviennent des
 * sous-dossiers de la racine du poste, et ouvrir un projet de plus ne coûte plus rien.</p>
 *
 * <p><b>Un poste appartient à un seul utilisateur</b> : {@link #userId} est la racine de l'isolation,
 * et rien n'est partagé entre comptes (le partage est F-17, hors périmètre V3).</p>
 *
 * <p>Tout ce que la gateway sait de la machine est <b>déclaré par le runner</b>, jamais deviné :
 * {@link #rootName} (le dernier segment de la racine seulement — l'arborescence de la machine n'a
 * rien à faire ici), {@link #os}, {@link #shell}, {@link #elevated}.</p>
 */
@Entity
@Table(name = "runner_hosts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RunnerHost {

    /** Longueur maximale du nom lisible d'un poste. */
    public static final int MAX_NAME_LENGTH = 100;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire (= {@code users.id}). Filtre d'isolation obligatoire. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /**
     * Nom lisible, <b>librement choisi</b> par l'utilisateur (décision n° 1 du cadrage). Rien
     * n'empêche de nommer un poste du nom du client chez qui il est installé.
     */
    @Column(name = "name", nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    /**
     * Dernier segment de la racine déclarée par le runner à l'appairage (ex. {@code dev}), jamais le
     * chemin absolu. Sert uniquement à l'affichage. Nul tant qu'aucun runner ne s'est appairé.
     */
    @Column(name = "root_name", length = 255)
    private String rootName;

    /** Système déclaré par le runner ({@code os.name} en minuscules), ou {@code null}. */
    @Column(name = "os", length = 64)
    private String os;

    /**
     * Genre d'interpréteur <b>élu</b> par le runner et déclaré dans sa trame {@code ready}
     * (F-38 / SF-38-27) : {@code posix}, {@code powershell} ou {@code cmd}. Il vit sur le poste et
     * non sur le projet — c'est une propriété de la machine, et elle vaut pour tous ses projets.
     */
    @Column(name = "shell", length = 16)
    private String shell;

    /**
     * Vrai si le runner tourne avec les droits de l'<b>administrateur</b> (F-38 / SF-38-18).
     * Déclaré par le runner : la gateway ne peut pas le deviner.
     */
    @Column(name = "elevated")
    private Boolean elevated;

    /** Dernière connexion observée du poste, tenue par le heartbeat des jetons. */
    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
