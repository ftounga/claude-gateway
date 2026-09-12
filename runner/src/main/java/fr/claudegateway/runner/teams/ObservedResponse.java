package fr.claudegateway.runner.teams;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Une réponse que la page a reçue, et que nous avons <b>observée</b> (F-87 / SF-87-02).
 *
 * <p><b>Ce que cet objet ne porte pas est aussi important que ce qu'il porte.</b></p>
 * <ul>
 *   <li><b>Aucun en-tête.</b> Il n'y a pas de champ pour eux — donc rien à oublier de vider. Les
 *       en-têtes d'une réponse Teams portent des cookies ; ils ne doivent entrer nulle part.</li>
 *   <li><b>Aucune chaîne de requête.</b> L'adresse est tronquée <b>à la construction</b> : certains
 *       paramètres portent des jetons, et la classification n'a besoin que du chemin. Un jeton qui
 *       n'entre jamais dans l'objet ne peut pas en sortir.</li>
 * </ul>
 *
 * @param url  adresse <b>sans</b> sa chaîne de requête
 * @param kind ce que l'adaptateur a reconnu dans cette adresse
 * @param body corps JSON déjà analysé, ou {@code null} s'il n'a pas pu être récupéré
 */
public record ObservedResponse(String url, TeamsPayloadKind kind, JsonNode body) {

    public ObservedResponse {
        url = withoutQuery(url);
        kind = kind == null ? TeamsPayloadKind.UNKNOWN : kind;
    }

    /** Vrai si le corps a pu être récupéré et analysé. */
    public boolean hasBody() {
        return body != null;
    }

    /** L'adresse, amputée de tout ce qui suit « ? » ou « # ». */
    static String withoutQuery(String raw) {
        if (raw == null) {
            return "";
        }
        String url = raw.strip();
        int query = url.indexOf('?');
        if (query >= 0) {
            url = url.substring(0, query);
        }
        int fragment = url.indexOf('#');
        return fragment >= 0 ? url.substring(0, fragment) : url;
    }
}
