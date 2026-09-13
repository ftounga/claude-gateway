package fr.claudegateway.runner.teams;

import java.nio.file.Path;
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
        if (CdpCommands.PAGE_NAVIGATE.equals(method)) {
            return navigate(params.path("url").asText(""));
        }
        if (CdpCommands.SET_DOWNLOAD_BEHAVIOR.equals(method)) {
            String behavior = params.path("behavior").asText("");
            downloadBehaviors.add(behavior);
            downloadPath = "default".equals(behavior) ? null : params.path("downloadPath").asText("");
            return mapper.createObjectNode();
        }
        return mapper.createObjectNode();
    }

    // ------------------------------------------------------------------ F-108 : SharePoint de papier

    /** Réponses modèles par opération (nom de l'opération tel qu'il est écrit dans le script). */
    private final Map<String, java.util.Deque<ObjectNode>> sharePoint = new HashMap<>();
    /** Opérations démarrées, en attente de relève. */
    private final Map<String, ObjectNode> startedOps = new HashMap<>();
    /** Scripts de fichiers réellement exécutés dans la page, dans l'ordre. */
    private final List<String> scripts = new ArrayList<>();
    /** Adresses de navigation réellement émises. */
    private final List<String> navigations = new ArrayList<>();
    private final List<String> downloadBehaviors = new ArrayList<>();
    /** Là où la navigation atterrit à la place de sa cible (redirection), ou {@code null}. */
    private String redirect;
    private String downloadPath;
    private byte[] downloadContent;
    private final List<Path> downloads = new ArrayList<>();

    /**
     * La page de papier répond à une opération par une réponse modèle : un statut, et le corps tel que
     * Microsoft le servirait. Comme le vrai script, elle ne rend que la projection sur liste blanche.
     */
    FakeCdpConnection sharePoint(String operation, int status, JsonNode raw) {
        ObjectNode out = mapper.createObjectNode();
        boolean ok = status >= 200 && status < 300;
        out.put("ok", ok);
        out.put("status", status);
        if (ok) {
            JsonNode picked = SharePointProjection.pick(raw);
            out.set("body", picked == null ? mapper.nullNode() : picked);
        } else {
            out.put("error", raw == null ? "" : raw.path("odata.error").path("message")
                    .path("value").asText(""));
        }
        sharePoint.computeIfAbsent(operation, key -> new java.util.ArrayDeque<>()).add(out);
        return this;
    }

    /** Une page qui renverrait tout, sans projection : ce qu'une page modifiée pourrait faire. */
    FakeCdpConnection sharePointUnfiltered(String operation, JsonNode raw) {
        ObjectNode out = mapper.createObjectNode();
        out.put("ok", true);
        out.put("status", 200);
        out.set("body", raw);
        out.put("digest", "0xSECRET-DIGEST-QUI-FUIT");
        sharePoint.computeIfAbsent(operation, key -> new java.util.ArrayDeque<>()).add(out);
        return this;
    }

    /** Toute navigation atterrit à cette adresse (une page d'identification, par exemple). */
    FakeCdpConnection redirectingTo(String url) {
        this.redirect = url;
        return this;
    }

    /** Ce que Chrome écrira quand on naviguera vers une adresse de téléchargement. */
    FakeCdpConnection downloading(byte[] content) {
        this.downloadContent = content;
        return this;
    }

    /** Ce que Chrome écrira pour une adresse de téléchargement qui contient ce fragment (encodé). */
    FakeCdpConnection downloadingFor(String urlFragment, byte[] content) {
        downloadsByUrl.put(urlFragment, content);
        return this;
    }

    private final Map<String, byte[]> downloadsByUrl = new java.util.LinkedHashMap<>();

    private JsonNode navigate(String url) {
        navigations.add(url);
        if (url.contains("/_layouts/15/download.aspx")) {
            byte[] downloadContent = this.downloadContent;
            for (Map.Entry<String, byte[]> entry : downloadsByUrl.entrySet()) {
                if (url.contains(entry.getKey())) {
                    downloadContent = entry.getValue();
                }
            }
            if (downloadPath != null && downloadContent != null) {
                try {
                    Path dir = Path.of(downloadPath);
                    java.nio.file.Files.createDirectories(dir);
                    Path file = dir.resolve(java.util.UUID.randomUUID().toString());
                    java.nio.file.Files.write(file, downloadContent);
                    downloads.add(file);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return mapper.createObjectNode(); // un téléchargement ne déplace pas la page
        }
        route = redirect != null ? redirect : url;
        return mapper.createObjectNode();
    }

    private JsonNode sharePointScript(String expression, ObjectNode result) {
        java.util.regex.Matcher op = java.util.regex.Pattern.compile("/\\*cg-op:([a-z0-9-]*)\\*/")
                .matcher(expression);
        if (op.find()) {
            scripts.add(expression);
            java.util.regex.Matcher id = java.util.regex.Pattern
                    .compile("const id = \"(cg[0-9a-f]+)\";").matcher(expression);
            String opId = id.find() ? id.group(1) : "";
            java.util.Deque<ObjectNode> answers = sharePoint.get(op.group(1));
            ObjectNode answer = answers == null || answers.isEmpty() ? null
                    : (answers.size() > 1 ? answers.poll() : answers.peek());
            if (answer == null) {
                answer = mapper.createObjectNode();
                answer.put("ok", false);
                answer.put("status", 0);
                answer.put("error", "aucune réponse modèle pour « " + op.group(1) + " »");
            }
            startedOps.put(opId, answer);
            result.putObject("result").put("value", opId);
            return result;
        }
        java.util.regex.Matcher poll = java.util.regex.Pattern.compile("/\\*cg-poll:(cg[0-9a-f]+)\\*/")
                .matcher(expression);
        if (poll.find()) {
            ObjectNode answer = startedOps.remove(poll.group(1));
            if (answer == null) {
                result.putObject("result").putObject("value").put("missing", true);
            } else {
                result.putObject("result").set("value", answer);
            }
            return result;
        }
        return null;
    }

    List<String> scripts() {
        return List.copyOf(scripts);
    }

    List<String> navigations() {
        return List.copyOf(navigations);
    }

    List<String> downloadBehaviors() {
        return List.copyOf(downloadBehaviors);
    }

    List<Path> downloads() {
        return List.copyOf(downloads);
    }

    /**
     * Le faux {@code Runtime.evaluate}. Trois gestes seulement, exactement comme le vrai vocabulaire
     * de F-88 : lire la route, ouvrir un fil, faire défiler.
     */
    private JsonNode evaluate(String expression) {
        ObjectNode result = mapper.createObjectNode();
        JsonNode files = sharePointScript(expression, result);
        if (files != null) {
            return files;
        }
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
        emitResponse(requestId, url, body, 200);
    }

    /** Même réponse, avec son statut HTTP — un refus 403 dit quelque chose des droits (F-100). */
    void emitResponse(String requestId, String url, String body, int status) {
        ObjectNode params = mapper.createObjectNode();
        params.put("requestId", requestId);
        ObjectNode response = params.putObject("response");
        response.put("url", url);
        response.put("status", status);
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
