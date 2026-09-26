package fr.claudegateway.office;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>Ce qui construit un document Office</b> (F-129 / SF-129-07).
 *
 * <p><b>Une interface</b> (Provider Independence), comme {@code DeckBuilder} et le rendu de
 * diagrammes : le métier ne connaît pas l'outil qui écrit le fichier.</p>
 *
 * <p><b>Pourquoi côté gateway</b> : le compte rendu Word et le tableau Excel étaient fabriqués
 * <b>sur le poste</b> par un script du modèle, qui commence par {@code import docx} — et sur un
 * poste d'entreprise {@code pip install} est bloqué. La gateway reçoit une <b>description</b> et
 * construit le fichier elle-même ; le Python d'un modèle ne s'exécute pas sur notre
 * infrastructure.</p>
 */
public interface OfficeBuilder {

    /**
     * Construit le fichier et rend ses octets.
     *
     * @param format le format voulu — il vient de l'outil appelé, pas de la description
     * @param spec   la description (blocs ou feuilles, images par nom)
     * @throws OfficeRejectedException           la description est invalide ou hors bornes
     * @throws OfficeBuilderUnavailableException le service n'a pas répondu
     */
    byte[] build(OfficeFormat format, JsonNode spec);

    /** Vrai si un constructeur est configuré ; sinon les outils ne sont pas proposés. */
    boolean isAvailable();
}
