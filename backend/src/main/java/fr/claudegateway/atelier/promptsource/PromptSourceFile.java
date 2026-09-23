package fr.claudegateway.atelier.promptsource;

import java.time.OffsetDateTime;
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
 * <b>La copie de travail d'un fichier source de la consigne</b>, côté gateway (F-148 / SF-148-06).
 *
 * <p><b>La machine fait foi</b>, comme pour la carte (F-136) : ceci n'est qu'un reflet de ce que
 * {@code buildSystemPrompt} relit à chaque message sur le runner — {@code CLAUDE.md},
 * {@code STATE.md}/{@code PLAN-ACTION.md} du sujet, l'arborescence et les fichiers de skills. On le
 * relit <b>après</b> un tour pour servir le suivant sans payer jusqu'à ~19 allers-retours runner
 * avant le premier mot du modèle.</p>
 *
 * <p><b>Isolation.</b> {@code (user_id, workspace_id, path)} est unique en base ; {@code host_id} est
 * rangé pour l'isolation et la purge. Aucune lecture n'existe sans le couple utilisateur + projet.</p>
 */
@Entity
@Table(name = "prompt_source_files")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromptSourceFile {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Le poste sous lequel ce projet vit — rangé pour l'isolation et la purge. */
    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    /** Le projet (workspace) dont c'est la consigne : il détermine le {@code projectPath}. */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    /**
     * Chemin relatif au {@code projectPath} du workspace, ou le chemin réservé de l'arborescence
     * ({@link PromptSourceStore#TREE_PATH}).
     */
    @Column(name = "path", nullable = false, length = 512, updatable = false)
    private String path;

    /**
     * Contenu du fichier (ou listage brut pour l'arborescence).
     *
     * <p>{@code columnDefinition = "text"} et <b>non</b> {@code @Lob} : sur PostgreSQL, {@code @Lob}
     * fait attendre un {@code oid} à Hibernate là où la migration crée un {@code text}, et la
     * validation de schéma refuse alors de démarrer. C'est la convention du projet (voir
     * {@code HostMapFile}, {@code AtelierMessage}).</p>
     */
    @Column(name = "content", columnDefinition = "text")
    private String content;

    /** Empreinte du contenu : ce qui permet de ne rien réécrire quand rien n'a changé. */
    @Column(name = "digest", length = 64)
    private String digest;

    @Column(name = "observed_at", nullable = false)
    private OffsetDateTime observedAt;
}
