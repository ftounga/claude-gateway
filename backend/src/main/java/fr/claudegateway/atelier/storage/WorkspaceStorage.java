package fr.claudegateway.atelier.storage;

import java.util.List;
import java.util.Optional;

/**
 * Abstraction du stockage des fichiers d'un workspace Atelier (F-28). Le domaine
 * ({@code WorkspaceService}) ne dépend que de cette interface, jamais de S3 en direct
 * (Provider Independence) — impl {@code in-memory} pour les tests, {@code s3} en cluster.
 * Les clés sont des chemins relatifs sous le préfixe {@code atelier/{userId}/{workspaceId}/}.
 */
public interface WorkspaceStorage {

    /** Écrit (ou remplace) le contenu à la clé donnée. */
    void putFile(String key, byte[] content, String contentType);

    /** Contenu à la clé donnée, ou vide si absent. */
    Optional<byte[]> getFile(String key);

    /** Liste des clés existantes sous le préfixe donné. */
    List<String> listKeys(String prefix);

    /**
     * Supprime la clé <b>exacte</b> donnée (aucun effet si elle n'existe pas). À ne pas confondre
     * avec {@link #deletePrefix(String)} : cette méthode ne touche jamais une clé voisine partageant
     * le même préfixe (ex. {@code x.js} vs {@code x.js.bak}).
     */
    void deleteFile(String key);

    /**
     * Supprime <b>toutes</b> les clés sous le préfixe donné, et ne rend la main qu'une fois le
     * dernier lot traité. Une implémentation qui doit découper (S3 plafonne {@code DeleteObjects} à
     * 1 000 clés) le fait ici, pas chez l'appelant.
     *
     * @throws WorkspaceStorageDeletionException si l'effacement est <b>incomplet</b> — elle porte le
     *         nombre de clés effacées et le nombre restantes. Un échec partiel silencieux est
     *         interdit : l'appelant doit pouvoir dire ce qui est parti (F-79 / SF-79-01).
     */
    void deletePrefix(String prefix);
}
