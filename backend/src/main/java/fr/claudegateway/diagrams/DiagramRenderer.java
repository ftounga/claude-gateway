package fr.claudegateway.diagrams;

/**
 * <b>Ce qui transforme du code de diagramme en image</b> (F-142 / SF-142-06).
 *
 * <p><b>Une interface, et c'est délibéré</b> (Provider Independence) : le métier ne connaît pas le
 * moteur. Aujourd'hui c'est Mermaid, rendu par un service de notre cluster ; demain ce sera aussi
 * {@code diagrams} pour les icônes cloud officielles (SF-142-07), sans que rien ne soit réécrit ici.</p>
 *
 * <p><b>Pourquoi côté gateway</b> : l'ancienne chaîne demandait au poste du client d'installer
 * {@code mermaid-cli} et de télécharger chromium. Sur un poste de banque — proxy, droits,
 * téléchargement de 150 Mo — cela ne passe pas, et le livrable partait sans ses diagrammes.</p>
 */
public interface DiagramRenderer {

    /** Le format demandé : une slide veut du PNG, une page gagne à recevoir du SVG. */
    enum Format {
        PNG("image/png", ".png"),
        SVG("image/svg+xml", ".svg");

        private final String contentType;
        private final String extension;

        Format(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }

        public String contentType() {
            return contentType;
        }

        public String extension() {
            return extension;
        }

        /** Le format nommé, PNG par défaut — un nom inconnu ne fait pas échouer un livrable. */
        public static Format of(String name) {
            return name != null && "svg".equalsIgnoreCase(name.strip()) ? SVG : PNG;
        }
    }

    /** L'image rendue, et ce qu'il faut pour la ranger. */
    record Rendered(byte[] bytes, Format format) {
    }

    /**
     * Rend un diagramme <b>Mermaid</b>.
     *
     * @throws DiagramRejectedException    le code est invalide ou hors bornes — la raison est dite
     * @throws DiagramRendererUnavailableException le service n'a pas répondu — le repli est dit
     */
    Rendered render(String code, Format format, Integer width);

    /**
     * Rend une <b>architecture cloud avec les icônes officielles</b> (F-142 / SF-142-07), depuis une
     * <b>description</b> — jamais du code : {@code diagrams} se pilote en Python, et exécuter le Python
     * d'un modèle sur notre infrastructure serait une porte qu'on n'ouvre pas.
     *
     * @param spec la description (nœuds typés, groupes, liens), telle que l'agent l'a donnée
     */
    Rendered renderCloud(com.fasterxml.jackson.databind.JsonNode spec);

    /** Vrai si un moteur est configuré : sans lui, l'outil n'est pas proposé plutôt que de promettre. */
    boolean isAvailable();
}
