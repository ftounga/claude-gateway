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

    private TeamsRoutes() {
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
