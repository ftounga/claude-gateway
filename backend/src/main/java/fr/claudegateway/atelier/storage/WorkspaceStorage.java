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

    // ------------------------------------------------------------------ gros objets (F-213 / SF-213-01)

    /**
     * Ouvre un <b>envoi par morceaux</b> vers la clé donnée (F-213 / SF-213-01) : une vidéo de 300 Mo
     * n'est jamais tenue entière en mémoire. Par défaut, les morceaux sont accumulés puis écrits d'un
     * coup — suffisant pour le stockage en mémoire (dev, tests) ; S3 l'implémente en multipart.
     */
    default Upload openUpload(String key, String contentType) {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        return new Upload() {
            @Override
            public void write(byte[] chunk) {
                buffer.write(chunk, 0, chunk.length);
            }

            @Override
            public void complete() {
                putFile(key, buffer.toByteArray(), contentType);
            }

            @Override
            public void abort() {
                buffer.reset();
            }
        };
    }

    /** Taille de l'objet à la clé donnée, ou vide s'il est absent. */
    default java.util.OptionalLong size(String key) {
        return getFile(key).map(bytes -> java.util.OptionalLong.of(bytes.length))
                .orElse(java.util.OptionalLong.empty());
    }

    /**
     * Les octets {@code [start, endInclusive]} de l'objet, en flux (lecture progressive d'une vidéo).
     * Vide si l'objet est absent. L'appelant ferme le flux.
     */
    default Optional<java.io.InputStream> openRange(String key, long start, long endInclusive) {
        return getFile(key).map(bytes -> {
            int from = (int) Math.min(Math.max(0, start), bytes.length);
            int to = (int) Math.min(bytes.length, endInclusive + 1);
            return new java.io.ByteArrayInputStream(bytes, from, Math.max(0, to - from));
        });
    }

    /** Un envoi par morceaux : {@link #write} autant que nécessaire, puis {@link #complete} ou {@link #abort}. */
    interface Upload {

        /** Ajoute un morceau, dans l'ordre. */
        void write(byte[] chunk);

        /** Termine l'envoi : l'objet devient lisible. */
        void complete();

        /** Abandonne l'envoi : rien ne reste. Sans effet après {@link #complete}. */
        void abort();
    }
}
