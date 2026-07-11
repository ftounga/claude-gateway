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

    /** Supprime toutes les clés sous le préfixe donné. */
    void deletePrefix(String prefix);
}
