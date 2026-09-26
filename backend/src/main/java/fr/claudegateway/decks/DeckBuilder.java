package fr.claudegateway.decks;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>Ce qui construit une présentation</b> (F-129 / SF-129-05).
 *
 * <p><b>Une interface</b> (Provider Independence), comme pour les diagrammes : le métier ne connaît
 * pas l'outil qui écrit le fichier.</p>
 *
 * <p><b>Pourquoi côté gateway</b> : le deck était fabriqué <b>sur le poste</b> avec {@code python-pptx},
 * et sur un poste d'entreprise {@code pip install} est bloqué — les diagrammes se rendaient, et le deck
 * ne se produisait pas. Ici comme en SF-142-07, la gateway reçoit une <b>description</b> et construit
 * le fichier elle-même : le Python d'un modèle n'a pas à s'exécuter sur notre infrastructure.</p>
 */
public interface DeckBuilder {

    /**
     * Le fichier construit, et son <b>aperçu</b> (F-129 / SF-129-06) : une image par slide, rendue
     * par le service. {@code previewError} est <b>dit</b> quand l'aperçu manque — le fichier, lui,
     * est toujours là : un aperçu en échec ne fait jamais échouer la présentation.
     */
    record Deck(byte[] bytes, List<byte[]> slides, String previewError) {

        /** Un deck sans aperçu (formes historiques, tests). */
        static Deck of(byte[] bytes) {
            return new Deck(bytes, List.of(), "");
        }
    }

    /**
     * Construit la présentation.
     *
     * @param spec la description (slides typées, puces, images par nom, notes)
     * @throws DeckRejectedException    la description est invalide ou hors bornes — la raison est dite
     * @throws DeckBuilderUnavailableException le service n'a pas répondu
     */
    Deck build(JsonNode spec);

    /** Vrai si un constructeur est configuré ; sinon l'outil n'est pas proposé. */
    boolean isAvailable();
}
