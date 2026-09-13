package fr.claudegateway.runner.teams;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Un navigateur de papier (F-87 / SF-87-02).
 *
 * <p>Il existe parce que nous n'avons ni compte Teams de test, ni Chrome dans l'intégration
 * continue : il n'en fallait pas moins pouvoir vérifier qu'un corps indisponible produit un manque,
 * qu'une commande interdite est refusée, et qu'aucun en-tête ne franchit l'observation.</p>
 */
final class FakeCdpConnection implements CdpConnection {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Consumer<JsonNode>> listeners = new HashMap<>();
    private final Map<String, String> bodies = new HashMap<>();
    private final List<String> sent = new ArrayList<>();

    /** Identifiants de requête dont le corps doit être déclaré indisponible. */
    private final List<String> purged = new ArrayList<>();

    /** Ce que la page livrera au PROCHAIN geste de défilement (F-88 / SF-88-01). */
    private final java.util.Deque<String[]> onNextScroll = new java.util.ArrayDeque<>();
    /** Les fils que le geste d'ouverture saura trouver dans la page. */
    private final java.util.Set<String> reachable = new java.util.LinkedHashSet<>();
    /** Ce que la page livrera à la PROCHAINE recherche (F-88 / SF-88-02). */
    private final java.util.Deque<String[]> onNextSearch = new java.util.ArrayDeque<>();
    /** Les questions réellement posées dans le champ de recherche. */
    private final List<String> searches = new ArrayList<>();

    /** Le contenu du champ de recherche, ou {@code null} si la page n'en a pas. */
    private String searchField;

    private boolean open = true;
    private boolean scrollMoves = true;
    /**
     * La page s'arrête de remonter quand elle n'a plus rien à livrer — comme un vrai début de fil.
     * Faux par défaut : les tests de F-87 comptent les gestes sur une page qui remonte toujours.
     */
    private boolean stopWhenNothingLeft;
    private String route = "https://teams.microsoft.com/v2/#/conversations/19:fabrique@thread.v2";
    private int scrolls;

    @Override
    public JsonNode send(String method, ObjectNode params) {
        CdpCommands.assertAllowed(method);
        sent.add(method);
        if (CdpCommands.GET_RESPONSE_BODY.equals(method)) {
            String requestId = params.path("requestId").asText("");
            if (purged.contains(requestId)) {
                throw new IllegalStateException("No resource with given identifier found");
            }
            ObjectNode result = mapper.createObjectNode();
            result.put("body", bodies.getOrDefault(requestId, ""));
            result.put("base64Encoded", false);
            return result;
        }
        if (CdpCommands.EVALUATE.equals(method)) {
            return evaluate(params.path("expression").asText(""));
        }
        return mapper.createObjectNode();
    }

    /**
     * Le faux {@code Runtime.evaluate}. Trois gestes seulement, exactement comme le vrai vocabulaire
     * de F-88 : lire la route, ouvrir un fil, faire défiler.
     */
    private JsonNode evaluate(String expression) {
        ObjectNode result = mapper.createObjectNode();
        if (expression.contains("location.href")) {
            result.putObject("result").put("value", route);
            return result;
        }
        if (expression.contains("searchbox")) {
            return searchGesture(expression, result);
        }
        if (expression.contains(".click()")) {
            String opened = "";
            for (String candidate : reachable) {
                if (expression.contains(candidate.toLowerCase(java.util.Locale.ROOT))) {
                    opened = candidate;
                    break;
                }
            }
            if (!opened.isEmpty()) {
                route = "https://teams.microsoft.com/v2/#/conversations/" + opened;
            }
            result.putObject("result").put("value", !opened.isEmpty());
            return result;
        }
        scrolls++;
        String[] delivery = onNextScroll.poll();
        if (delivery != null) {
            emitResponse(delivery[0], delivery[1], delivery[2]);
        }
        boolean moved = scrollMoves && (delivery != null || !stopWhenNothingLeft);
        result.putObject("result").put("value", moved);
        return result;
    }

    /** Le faux champ de recherche : la question posée, et ce que la page sert en retour. */
    private JsonNode searchGesture(String expression, ObjectNode result) {
        String assigned = assignedValue(expression);
        boolean asking = expression.contains("field.focus()");
        if (searchField == null) {
            if (asking) {
                ObjectNode value = result.putObject("result").putObject("value");
                value.put("done", false);
                value.put("previous", "");
            } else {
                result.putObject("result").put("value", false);
            }
            return result;
        }
        if (asking) {
            ObjectNode value = result.putObject("result").putObject("value");
            value.put("done", true);
            value.put("previous", searchField);
            searchField = assigned;
            searches.add(assigned);
            String[] delivery = onNextSearch.poll();
            if (delivery != null) {
                emitResponse(delivery[0], delivery[1], delivery[2]);
            }
            return result;
        }
        searchField = assigned;
        result.putObject("result").put("value", true);
        return result;
    }

    /** La valeur que le script écrit dans le champ, lue dans son littéral JSON. */
    private static String assignedValue(String expression) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("field\\.value = \"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(expression);
        return matcher.find() ? matcher.group(1) : "";
    }

    @Override
    public void onEvent(String method, Consumer<JsonNode> listener) {
        listeners.put(method, listener);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
    }

    // ------------------------------------------------------------------ pilotage du faux

    /** Fait « arriver » une réponse, en-têtes compris — précisément ce qu'on ne doit pas lire. */
    void emitResponse(String requestId, String url, String body) {
        ObjectNode params = mapper.createObjectNode();
        params.put("requestId", requestId);
        ObjectNode response = params.putObject("response");
        response.put("url", url);
        response.put("status", 200);
        response.put("mimeType", "application/json");
        ObjectNode headers = response.putObject("headers");
        headers.put("Set-Cookie", "authtoken=SECRET-COOKIE-DE-SESSION; HttpOnly");
        headers.put("Authorization", "Bearer SECRET-JETON-DE-SESSION");
        headers.put("x-skypetoken", "SECRET-SKYPETOKEN-DE-SESSION");
        if (body != null) {
            bodies.put(requestId, body);
        }
        Consumer<JsonNode> listener = listeners.get("Network.responseReceived");
        if (listener != null) {
            listener.accept(params);
        }
    }

    void purge(String requestId) {
        purged.add(requestId);
    }

    /** Un cadre ou un worker qui s'attache (F-108 / SF-108-01, §4.8), à l'adresse donnée. */
    void emitAttached(String url) {
        ObjectNode params = mapper.createObjectNode();
        params.putObject("targetInfo").put("url", url);
        Consumer<JsonNode> listener = listeners.get("Target.attachedToTarget");
        if (listener != null) {
            listener.accept(params);
        }
    }

    void scrollStopsMoving() {
        scrollMoves = false;
    }

    /** La page cesse de remonter dès qu'elle n'a plus rien à livrer : le début d'un fil. */
    void stopsWhenNothingLeft() {
        stopWhenNothingLeft = true;
    }

    /** La page défile toujours, mais ne rapporte plus rien : le cas du trou silencieux. */
    void scrollsForever() {
        stopWhenNothingLeft = false;
    }

    /** Ce que la page livrera au prochain défilement. */
    void deliverOnScroll(String requestId, String url, String body) {
        onNextScroll.add(new String[] { requestId, url, body });
    }

    /** Le fil affiché, tel que la route le dit. */
    void showing(String conversationId) {
        route = "https://teams.microsoft.com/v2/#/conversations/" + conversationId;
    }

    /** Un fil que le geste d'ouverture saura atteindre dans la page. */
    void reachable(String conversationId) {
        reachable.add(conversationId);
    }

    /** La page a un champ de recherche, qui porte ce texte. */
    void hasSearchField(String current) {
        searchField = current == null ? "" : current;
    }

    /** Ce que la page livrera à la prochaine recherche. */
    void deliverOnSearch(String requestId, String url, String body) {
        onNextSearch.add(new String[] { requestId, url, body });
    }

    List<String> searches() {
        return List.copyOf(searches);
    }

    String searchField() {
        return searchField;
    }

    int scrolls() {
        return scrolls;
    }

    String route() {
        return route;
    }

    List<String> sentCommands() {
        return List.copyOf(sent);
    }
}
