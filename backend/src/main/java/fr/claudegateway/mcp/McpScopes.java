package fr.claudegateway.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Les périmètres OAuth du serveur MCP (cadrage F-112 §4) et leur libellé en français clair, affiché
 * à l'utilisateur sur l'écran de consentement.
 *
 * <p>Un périmètre n'est jamais plus large que les droits de l'utilisateur : il est demandé par le
 * client, accordé par l'utilisateur, et vérifié <b>avant</b> le service par l'outil. Le périmètre
 * {@code admin} n'est utile qu'au rôle ADMIN.</p>
 */
public final class McpScopes {

    public static final String POSTES_LIRE = "postes:lire";
    public static final String POSTES_AGIR = "postes:agir";
    public static final String TERMINAUX_ECRIRE = "terminaux:ecrire";
    public static final String RADAR_LIRE = "radar:lire";
    public static final String RADAR_ECRIRE = "radar:ecrire";
    public static final String PAGES = "pages";
    public static final String COURRIEL = "courriel";
    public static final String COMPTE_LIRE = "compte:lire";
    public static final String ADMIN = "admin";

    /** Libellés français, dans l'ordre d'affichage du consentement. */
    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put(POSTES_LIRE, "Voir vos postes, leur statut, leurs projets et la gouvernance");
        LABELS.put(POSTES_AGIR, "Vérifier un poste, mettre à jour le runner, activer un espace");
        LABELS.put(TERMINAUX_ECRIRE, "Écrire dans un terminal, préciser, interrompre, lire les tours");
        LABELS.put(RADAR_LIRE, "Lire la Vigie et le Radar");
        LABELS.put(RADAR_ECRIRE, "Synchroniser, donner une nouvelle, clore et lier un sujet du Radar");
        LABELS.put(PAGES, "Publier, lister et lire des pages");
        LABELS.put(COURRIEL, "Vous envoyer un courriel");
        LABELS.put(COMPTE_LIRE, "Voir votre quota, votre abonnement et votre consommation");
        LABELS.put(ADMIN, "Outils d'administration (réservé au rôle ADMIN)");
    }

    private McpScopes() {
    }

    /** L'ensemble ordonné de tous les périmètres. */
    public static Set<String> all() {
        return LABELS.keySet();
    }

    /** Libellé français d'un périmètre, ou le périmètre lui-même s'il est inconnu. */
    public static String label(String scope) {
        return LABELS.getOrDefault(scope, scope);
    }
}
