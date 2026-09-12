package fr.claudegateway.runner.teams;

import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * <b>Le vocabulaire de gestes</b> du volet Teams (F-88 / SF-88-01) : ce que le runner sait faire
 * <b>dans</b> la page, et rien d'autre.
 *
 * <p><b>Trois verbes, et pas un de plus</b> : {@code scroll} (approfondir la liste ouverte, hérité de
 * F-87), {@code nudge} (provoquer un rafraîchissement en remettant la vue où elle était, hérité de
 * F-87) et {@code show} (afficher un fil que l'utilisateur n'a pas ouvert).</p>
 *
 * <p><b>La liste blanche CDP n'est pas touchée.</b> Tout passe par {@code Runtime.evaluate}, la
 * seule porte que SF-87-02 a ouverte et qui sert déjà au défilement. Aucune commande n'est ajoutée :
 * ni {@code Page.navigate}, ni {@code Input.dispatch*}, ni rien qui approche les cookies ou le
 * stockage. Cette décision de sécurité ne se rouvre pas pour une commodité.</p>
 *
 * <p><b>Pourquoi {@code show} existe quand même.</b> Le troisième gisement d'un engagement — ses
 * propres promesses, « je te l'envoie demain » — ne porte ni mention ni nom : aucune recherche par
 * mot-clé ne le trouve, il faut <b>ouvrir</b> les fils récemment actifs. Sans ce geste, le volet
 * livrerait les deux gisements faciles et manquerait celui qui engage l'utilisateur.</p>
 *
 * <p><b>Le prix, assumé et dit.</b> {@code show} touche la fenêtre de l'utilisateur. Il note donc le
 * fil affiché avant d'agir, et {@link #restore(String)} le remet. Ce qui a été fait est écrit dans
 * le résultat de l'outil — jamais fait en douce.</p>
 *
 * <p><b>Le DOM sert au geste, jamais à la donnée</b> (§10 du cadrage) : on cherche un élément qui
 * <i>mène</i> au fil et on le clique ; rien de ce qui est lu à l'écran n'entre dans un compte rendu.
 * Un sélecteur qui ne trouve rien produit un <b>manque nommé</b>, pas un silence.</p>
 */
final class PageGestures {

    /** Le geste de défilement rend la main dès que la page ne remonte plus (F-87). */
    static final int MAX_SCROLL_GESTURES = BrowserLink.MAX_SCROLL_GESTURES;
    /** Temps laissé à la page pour charger ce que le geste a demandé (F-87). */
    static final long SETTLE_MS = BrowserLink.SCROLL_SETTLE_MS;

    /**
     * L'adresse courante, telle que la page la connaît. C'est elle qui dit quel fil est affiché :
     * la route d'un client de conversation porte l'identifiant du fil.
     */
    private static final String CURRENT_ROUTE = "(() => location.href)()";

    private final BrowserLink link;
    private final BrowserLink.Sleeper sleeper;
    private final ObjectMapper mapper = new ObjectMapper();

    PageGestures(BrowserLink link, BrowserLink.Sleeper sleeper) {
        this.link = link;
        this.sleeper = sleeper;
    }

    /** Le fil affiché, ou {@code ""} si la route n'en nomme aucun. */
    String shownConversationId() {
        return TeamsRoutes.conversationIdOf(evaluateText(CURRENT_ROUTE));
    }

    /** Un geste de défilement vers le haut. Rend faux dès que la page ne remonte plus. */
    boolean scroll() {
        return link.scrollUpOnce(sleeper);
    }

    /** Remue la vue sans la déplacer durablement : ce qui arrive en réponse est observé. */
    boolean nudge() {
        return link.nudge(sleeper);
    }

    /**
     * Affiche un fil. Rend vrai <b>seulement</b> si la route a effectivement changé pour lui : un
     * clic qui n'aboutit pas ne doit pas se faire passer pour une ouverture.
     */
    boolean show(String conversationId) {
        String wanted = conversationId == null ? "" : conversationId.strip();
        if (wanted.isEmpty()) {
            return false;
        }
        if (wanted.equals(shownConversationId())) {
            return true;
        }
        evaluateBoolean(clickScript(wanted));
        if (sleeper != null) {
            sleeper.sleep(SETTLE_MS);
        }
        return wanted.equals(shownConversationId());
    }

    /** Remet le fil qui était affiché. Sans objet si aucun ne l'était. */
    boolean restore(String conversationId) {
        return conversationId != null && !conversationId.isBlank() && show(conversationId);
    }

    // ------------------------------------------------------------------ exécution

    private String evaluateText(String expression) {
        JsonNode value = evaluate(expression);
        return value == null || !value.isTextual() ? "" : value.asText("");
    }

    private boolean evaluateBoolean(String expression) {
        JsonNode value = evaluate(expression);
        return value != null && value.asBoolean(false);
    }

    private JsonNode evaluate(String expression) {
        ObjectNode params = mapper.createObjectNode();
        params.put("expression", expression);
        params.put("returnByValue", true);
        JsonNode result = link.connection().send(CdpCommands.EVALUATE, params);
        return result == null ? null : result.path("result").get("value");
    }

    /**
     * Le script du geste {@code show} : trouver un élément de la page qui <b>mène</b> au fil, et le
     * cliquer.
     *
     * <p>L'identifiant est injecté <b>en littéral JSON</b> — jamais concaténé à la main : un
     * identifiant de fil contient des caractères (deux-points, arobase) qu'une concaténation naïve
     * laisserait s'échapper du littéral.</p>
     */
    private String clickScript(String conversationId) {
        String needle = TextNode.valueOf(conversationId.toLowerCase(Locale.ROOT)).toString();
        return "(() => {"
                + " const needle = " + needle + ";"
                + " const candidates = document.querySelectorAll("
                + "   'a[href], [role=\"treeitem\"], [role=\"option\"], [role=\"listitem\"],"
                + "    [data-tid], [id]');"
                + " for (const el of candidates) {"
                + "   const haystack = ((el.getAttribute('href') || '') + ' '"
                + "     + (el.getAttribute('id') || '') + ' '"
                + "     + (el.getAttribute('data-tid') || '')).toLowerCase();"
                + "   if (haystack.indexOf(needle) >= 0) {"
                + "     const target = el.closest('a[href]') || el;"
                + "     target.click();"
                + "     return true;"
                + "   }"
                + " }"
                + " return false;"
                + "})()";
    }
}
