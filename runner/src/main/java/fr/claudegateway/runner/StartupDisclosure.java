package fr.claudegateway.runner;

import java.util.List;

/**
 * Déclaration de démarrage du runner (F-57 / SF-57-01) : <b>ce qu'il fait</b>, <b>sous quels
 * droits</b>, <b>par quelle route</b>, et <b>ce qu'il ne cherche pas</b>.
 *
 * <p>La question d'origine était l'inverse : « le runner peut-il savoir si des outils scannent
 * l'utilisation d'un shell ? ». La réponse est non — et il ne doit pas chercher. Énumérer les agents
 * de sécurité d'un poste (services, pilotes, processus, clés de registre) est de la
 * <b>reconnaissance de défenses</b> : le comportement même qu'un EDR classe comme malveillant. Un
 * runner qui le ferait se signalerait comme suspect sur chaque parc client.</p>
 *
 * <p>D'où l'angle retenu, le seul tenable : <b>la transparence</b>. Le runner ne cherche pas à savoir
 * ce qu'on voit de lui ; il dit ce qu'il fait à celui qui le lance. Tout ce qui est affiché ici vient
 * de sa <b>propre</b> configuration — jamais d'une inspection du poste.</p>
 *
 * <p>Fonction pure, sans I/O : c'est ce qui la rend testable ligne à ligne, et c'est aussi la
 * garantie qu'elle ne va rien chercher nulle part.</p>
 */
public final class StartupDisclosure {

    /** Libellé affiché quand la JVM ne rapporte aucun nom de compte. */
    static final String UNKNOWN_ACCOUNT = "(compte inconnu)";

    private StartupDisclosure() {
    }

    /**
     * Les lignes du bloc de transparence, dans l'ordre où elles se lisent.
     *
     * <p>Depuis F-73 / SF-73-01, la ligne <b>Portée</b> remplace ce que le confinement disait à la
     * place du runner. Elle est ici parce qu'elle est devenue la seule information exacte sur ce que
     * ce programme peut atteindre : il n'y a plus de garde entre lui et le disque. Le ton reste
     * <b>factuel</b> — c'est la machine de celui qui lit, et c'est lui qui a lancé ce programme.</p>
     *
     * @param privileges droits courants (SF-38-18) — le compte, et s'il est administrateur
     * @param route route sortante telle que le runner la connaît (SF-57-01)
     * @return cinq lignes, jamais vides, jamais nulles
     */
    public static List<String> lines(Privileges privileges, ProxyResolver.Route route) {
        String nl = System.lineSeparator();
        return List.of(
                "Ce runner : exécute sur cette machine les commandes que vous autorisez depuis "
                        + "la Forge," + nl
                        + "            avec les droits du compte ci-dessous. Rien ne s'exécute sans "
                        + "votre geste.",
                // La portée est dite en premier, avant le compte : c'est elle qui a changé, et c'est
                // elle qui n'était pas vraie avant (le dossier du projet ne bornait rien).
                "Portée    : aucune restriction de dossier. Le dossier du projet est le point de "
                        + "départ," + nl
                        + "            pas une limite : une commande lit et écrit partout où ce "
                        + "compte le peut," + nl
                        + "            y compris .env, clés SSH et .aws/. Ce qui est lu part chez le "
                        + "fournisseur" + nl
                        + "            dans le contexte du tour.",
                "Compte    : " + account(privileges),
                "Route     : " + route(route),
                // Le rappel est ICI, et pas seulement dans l'application (SF-57-01, D4) : le runner
                // est parfois lancé sans qu'aucun écran soit ouvert, et c'est lui qui exécute.
                "Rappel    : sur un poste d'entreprise, vos commandes sont vraisemblablement "
                        + "journalisées" + nl
                        + "            par votre employeur. Ce runner ne cherche pas à savoir ce qui "
                        + "observe ce" + nl
                        + "            poste, et ne le fera pas.");
    }

    /** Le compte, et la mention d'élévation quand elle s'applique. */
    private static String account(Privileges privileges) {
        String name = privileges.userName() == null ? "" : privileges.userName().trim();
        String shown = name.isEmpty() ? UNKNOWN_ACCOUNT : name;
        return privileges.elevated() ? shown + "  (administrateur)" : shown;
    }

    /**
     * La route, dite dans les termes de celui qui peut la changer : la variable d'environnement.
     *
     * <p>Le relais local est distingué du proxy d'entreprise parce que ce n'est pas la même chose à
     * comprendre : le relais <b>porte l'authentification</b> que la JVM ne sait pas porter (aucun
     * support SSPI, Basic désactivé sur les tunnels CONNECT depuis Java 8u111).</p>
     */
    private static String route(ProxyResolver.Route route) {
        return switch (route.kind()) {
            case DIRECT -> "directe (aucun proxy déclaré dans ce terminal)";
            case LOCAL_RELAY -> "relais local " + route.address() + " (" + route.variable() + "), "
                    + "qui porte l'authentification à la place du runner";
            case ENTERPRISE_PROXY -> "proxy d'entreprise " + route.address()
                    + " (" + route.variable() + ")";
        };
    }
}
