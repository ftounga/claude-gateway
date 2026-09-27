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
        SVG("image/svg+xml", ".svg"),
        /**
         * Le schéma <b>réouvrable</b> dans draw.io / diagrams.net (F-142 / SF-142-13). Ce n'est pas une
         * image : c'est la <b>source éditable</b>, déposée à côté de son aperçu.
         */
        DRAWIO("application/vnd.jgraph.mxfile", ".drawio");

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

    /**
     * L'image rendue, ce qu'il faut pour la ranger, et — s'il y en a — les types rendus <b>sans icône
     * officielle</b> (F-142 / SF-142-09). Ce dernier point doit remonter jusqu'à l'agent : un composant
     * dessiné en boîte neutre se dit, il ne se devine pas.
     */
    record Rendered(byte[] bytes, Format format, String unknownTypes, String notice) {

        public Rendered(byte[] bytes, Format format) {
            this(bytes, format, "", "");
        }

        public Rendered(byte[] bytes, Format format, String unknownTypes) {
            this(bytes, format, unknownTypes, "");
        }

        public boolean hasUnknownTypes() {
            return unknownTypes != null && !unknownTypes.isBlank();
        }

        /**
         * Vrai si le service de rendu a quelque chose à dire sur le schéma lui-même — aujourd'hui sa
         * <b>densité</b> (F-142 / SF-142-17). Ce n'est pas une erreur : l'image existe, elle est
         * seulement trop grande pour se lire, et l'agent doit pouvoir proposer de la scinder.
         */
        public boolean hasNotice() {
            return notice != null && !notice.isBlank();
        }
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

    /**
     * Les <b>deux artefacts</b> d'un schéma réouvrable (F-142 / SF-142-13) : la source {@code .drawio}
     * et son aperçu PNG.
     *
     * <p><b>Pourquoi l'aperçu peut manquer</b> : c'est la <b>source éditable</b> qui a de la valeur —
     * l'image se refait, le schéma perdu se refait à la main. Un aperçu en échec ne fait donc pas échouer
     * le tout : il est <b>dit</b> ({@code previewError}), et le fichier part quand même.</p>
     */
    record Editable(byte[] drawio, byte[] png, String previewError) {

        public boolean hasPreview() {
            return png != null && png.length > 0;
        }
    }

    /**
     * Rend un schéma <b>réouvrable dans draw.io</b> (F-142 / SF-142-13), depuis une <b>description</b> —
     * la même que {@link #renderCloud(com.fasterxml.jackson.databind.JsonNode)}, pour que l'agent n'ait
     * pas un second vocabulaire à apprendre.
     */
    Editable renderEditable(com.fasterxml.jackson.databind.JsonNode spec);

    /** Vrai si un moteur est configuré : sans lui, l'outil n'est pas proposé plutôt que de promettre. */
    boolean isAvailable();
}
