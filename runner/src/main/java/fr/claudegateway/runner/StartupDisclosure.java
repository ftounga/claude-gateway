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
     * Les lignes du bloc de transparence, sans la ligne de confiance — forme conservée pour les
     * appelants qui n'ont rien à dire du truststore.
     */
    public static List<String> lines(Privileges privileges, ProxyResolver.Route route) {
        return lines(privileges, route, TrustStores.Trust.jdkOnly(), true, null);
    }

    /**
     * Les lignes du bloc de transparence, dans l'ordre où elles se lisent.
     *
     * <p>Depuis F-73 / SF-73-01, la ligne <b>Portée</b> remplace ce que le confinement disait à la
     * place du runner. Elle est ici parce qu'elle est devenue la seule information exacte sur ce que
     * ce programme peut atteindre : il n'y a plus de garde entre lui et le disque. Le ton reste
     * <b>factuel</b> — c'est la machine de celui qui lit, et c'est lui qui a lancé ce programme.</p>
     *
     * <p>Depuis F-80 / SF-80-02, une ligne <b>Confiance</b> dit à quels magasins de certificats le
     * runner se fie. Elle est ici parce qu'elle répond à la même question que les autres — « qu'est-ce
     * que ce programme fait sur ma machine ? » — et parce que la décision du PO (OQ-17) est
     * « automatique <b>et annoncé</b> » : c'est cette ligne qui porte l'annonce.</p>
     *
     * @param privileges droits courants (SF-38-18) — le compte, et s'il est administrateur
     * @param route route sortante telle que le runner la connaît (SF-57-01)
     * @param trust ce à quoi le runner se fie (SF-80-02)
     * @param systemTrustRequested faux quand {@code --no-system-trust} a été posé
     * @param enterpriseRoot racine d'entreprise <b>constatée sur notre propre connexion</b>, ou
     *     {@code null} — jamais une racine moissonnée dans le magasin du poste (D2)
     * @return cinq ou six lignes, jamais vides, jamais nulles
     */
    public static List<String> lines(Privileges privileges, ProxyResolver.Route route,
            TrustStores.Trust trust, boolean systemTrustRequested, String enterpriseRoot) {
        List<String> lines = new java.util.ArrayList<>(
                baseLines(privileges, route, trust, systemTrustRequested, enterpriseRoot));
        return List.copyOf(lines);
    }

    private static List<String> baseLines(Privileges privileges, ProxyResolver.Route route,
            TrustStores.Trust trust, boolean systemTrustRequested, String enterpriseRoot) {
        String nl = System.lineSeparator();
        List<String> lines = new java.util.ArrayList<>(List.of(
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
                        + "            poste, et ne le fera pas."));
        // La confiance se dit APRÈS la route : elle en est la suite naturelle — par où l'on sort, et
        // à qui l'on se fie en sortant. Insérée juste avant le rappel, qui clôt le bloc.
        trustLine(trust, systemTrustRequested, enterpriseRoot)
                .ifPresent(line -> lines.add(lines.size() - 1, line));
        return lines;
    }

    /**
     * Ce que la console dit du truststore, <b>quand il y a quelque chose à en dire</b>
     * (F-80 / SF-80-02).
     *
     * <p>Trois cas, et un silence :</p>
     * <ul>
     *   <li>le magasin du système apporte des racines → on le dit, et on <b>nomme</b> la racine
     *       d'entreprise si notre propre connexion en a montré une ;</li>
     *   <li>{@code --no-system-trust} → on confirme la confiance stricte, parce qu'elle explique
     *       d'avance l'échec que ce drapeau peut provoquer ;</li>
     *   <li>magasin absent, illisible, ou n'ajoutant rien → <b>aucune ligne</b>. Le repli est
     *       silencieux (D4), et une ligne qui ne dit rien fait du bruit.</li>
     * </ul>
     *
     * <p><b>La racine nommée vient de notre connexion, jamais du magasin du poste</b> (D2). Ce bloc
     * décrit la configuration du runner — « jamais une inspection du poste » —, et le magasin racine
     * de Windows contient des centaines de racines absentes du {@code cacerts} qui ne sont pas des
     * racines d'entreprise : en nommer une serait le faux positif que F-57 interdit.</p>
     */
    static java.util.Optional<String> trustLine(TrustStores.Trust trust,
            boolean systemTrustRequested, String enterpriseRoot) {
        if (!systemTrustRequested) {
            return java.util.Optional.of(
                    "Confiance : magasin de la JDK seul (--no-system-trust)");
        }
        if (trust == null || !trust.systemStoreUsed()) {
            return java.util.Optional.empty();
        }
        StringBuilder line = new StringBuilder("Confiance : magasin de la JDK + magasin du système");
        if (!trust.source().isBlank()) {
            line.append(" (").append(trust.source()).append(')');
        }
        String root = enterpriseRoot == null ? "" : enterpriseRoot.trim();
        if (!root.isEmpty()) {
            line.append(" — racine d'entreprise détectée : ").append(root);
        }
        return java.util.Optional.of(line.toString());
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
