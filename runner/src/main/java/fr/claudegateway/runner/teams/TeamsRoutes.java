package fr.claudegateway.runner.teams;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ce qu'une <b>route</b> du client web dit du fil affiché (F-88 / SF-88-01).
 *
 * <p>Un identifiant de fil a une forme reconnaissable — {@code 19:…@thread.v2},
 * {@code 19:…@unq.gbl.spaces} — et la route de la page le porte. C'est la seule chose que le volet
 * lise dans une adresse de page : <b>quel fil est sous les yeux de l'utilisateur</b>, pour savoir
 * s'il faut en ouvrir un autre, et pour <b>remettre</b> celui-là ensuite.</p>
 *
 * <p>Rien de ce qui est lu ici n'entre dans un compte rendu : le DOM et la route servent au geste,
 * jamais à la donnée (§10 du cadrage).</p>
 */
final class TeamsRoutes {

    private static final Pattern THREAD =
            Pattern.compile("(19:[A-Za-z0-9_\\-+=./]+@[A-Za-z0-9_\\-.]+)");

    /**
     * Le calendrier de Teams (F-100 / SF-100-03), où la synchro du soir navigue pour que Teams serve les
     * réunions. <b>Hypothèse</b> : la route du client web actuel, commune à tous les clients ; elle sera
     * confirmée ou corrigée <b>ici</b> par le relevé réel (SF-100-00). Si elle ne sert rien, la couverture
     * le dit ({@code meetings.calendarServed = false}).
     */
    static final String CALENDAR = "https://teams.microsoft.com/v2/#/calendarv2";

    /**
     * La liste des conversations (F-89 / SF-89-05), où un outil de lecture navigue quand le registre est
     * vide. <b>Hypothèse</b> du même ordre que {@link #CALENDAR} : la route du client web v2 ; si elle
     * ne sert rien, le résultat le dit (zéro, manque diagnostiqué, geste nommé).
     */
    static final String CONVERSATIONS = "https://teams.microsoft.com/v2/#/conversations";

    /** Hôtes du client web de Teams : une route y est reportée pour ne pas changer de site. */
    private static final java.util.List<String> TEAMS_HOSTS =
            java.util.List.of("teams.microsoft.com", "teams.cloud.microsoft", "teams.live.com");

    private TeamsRoutes() {
    }

    /**
     * La route, <b>reportée sur l'hôte Teams de l'onglet</b> (F-89 / SF-89-05) : un onglet ouvert sur
     * {@code teams.cloud.microsoft} ne doit pas être renvoyé sur {@code teams.microsoft.com}, ce qui
     * rechargerait tout le client et perdrait la session de travail. Hôte de l'onglet non Teams → la
     * route telle quelle (les gardes de F-108 jugent ensuite la destination).
     */
    static String onTabHost(String route, String tabUrl) {
        String host = MicrosoftDomains.hostOf(tabUrl);
        if (!TEAMS_HOSTS.contains(host) || route == null) {
            return route;
        }
        int scheme = route.indexOf("://");
        int pathStart = scheme < 0 ? -1 : route.indexOf('/', scheme + 3);
        return pathStart < 0 ? route : "https://" + host + route.substring(pathStart);
    }

    /** L'identifiant de fil porté par une route, ou {@code ""}. */
    static String conversationIdOf(String route) {
        if (route == null || route.isBlank()) {
            return "";
        }
        Matcher matcher = THREAD.matcher(route);
        if (!matcher.find()) {
            return "";
        }
        String id = matcher.group(1);
        // Une route colle souvent la suite du chemin à l'identifiant : « …@thread.v2/conversations ».
        int slash = id.indexOf('/');
        return slash >= 0 ? id.substring(0, slash) : id;
    }
}
