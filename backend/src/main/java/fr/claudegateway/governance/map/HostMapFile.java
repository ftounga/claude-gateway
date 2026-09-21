package fr.claudegateway.governance.map;

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
 * <b>La copie de travail d'un fichier de carte</b>, côté gateway (F-136 / SF-136-01).
 *
 * <p><b>La machine fait foi</b>, comme partout ailleurs dans la gouvernance : ceci n'est qu'un
 * reflet, relu <b>après</b> chaque tour. Lire la carte au <b>début</b> d'un tour coûterait six
 * allers-retours avant le premier mot du modèle, pour un savoir qui ne change qu'à la fin. On paie
 * donc la lecture une fois la réponse partie.</p>
 *
 * <p><b>Le contenu est gardé</b>, délibérément : la règle du paquet interdit déjà aux fichiers de
 * carte de porter un secret, et c'est ce qui permettra à l'index par entité (F-137) et à la
 * péremption (F-139) de travailler <b>sans jamais relire la machine</b>.</p>
 *
 * <p><b>Isolation.</b> {@code (user_id, host_id, path)} est unique en base, et aucune lecture
 * n'existe sans le couple utilisateur + poste.</p>
 */
@Entity
@Table(name = "host_map_files")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HostMapFile {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Le poste dont c'est la carte. Jamais nul : une carte sans poste n'a aucun sens. */
    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    /** Chemin relatif à la racine du poste — « acces.md », jamais un chemin absolu. */
    @Column(name = "path", nullable = false, length = 500, updatable = false)
    private String path;

    /** Titre de niveau 1 du fichier, ou son nom à défaut. */
    @Column(name = "title", length = 500)
    private String title;

    /**
     * Titres des sections, une par ligne, dans l'ordre du fichier.
     *
     * <p>{@code columnDefinition = "text"} et <b>non</b> {@code @Lob} : sur PostgreSQL, {@code @Lob}
     * fait attendre un {@code oid} à Hibernate là où la migration crée un {@code text}, et la
     * validation de schéma refuse alors de démarrer. C'est la convention du projet (voir
     * {@code AtelierMessage}, {@code Document}).</p>
     */
    @Column(name = "sections", columnDefinition = "text")
    private String sections;

    /** Lignes porteuses de faits, comptées comme l'écran les compte. */
    @Column(name = "facts", nullable = false)
    private int facts;

    @Column(name = "content", columnDefinition = "text")
    private String content;

    /** Empreinte du contenu : ce qui permet de ne rien réécrire quand rien n'a changé. */
    @Column(name = "digest", length = 64)
    private String digest;

    @Column(name = "observed_at", nullable = false)
    private OffsetDateTime observedAt;
}
