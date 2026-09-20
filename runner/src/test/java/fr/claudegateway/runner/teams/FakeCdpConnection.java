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
    private final Map<String, java.util.function.BiConsumer<String, JsonNode>> listeners = new HashMap<>();
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
        if (CdpCommands.GET_DOCUMENT.equals(method)) {
            ObjectNode result = mapper.createObjectNode();
            result.putObject("root").put("nodeId", 1);
            return result;
        }
        if (CdpCommands.QUERY_SELECTOR.equals(method)) {
            ObjectNode result = mapper.createObjectNode();
            String selector = params.path("selector").asText("");
            result.put("nodeId", selector.startsWith("#") && injectedInputs.contains(selector.substring(1))
                    ? 7 : 0);
            return result;
        }
        if (CdpCommands.SET_FILE_INPUT_FILES.equals(method)) {
            params.path("files").forEach(file -> droppedFiles.add(file.asText()));
            return mapper.createObjectNode();
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
        for (java.util.Iterator<String[]> it = onNavigate.iterator(); it.hasNext();) {
            String[] delivery = it.next();
            if (url.contains(delivery[0])) {
                it.remove();
                if (delivery[1].isEmpty()) {
                    emitResponse(delivery[2], delivery[3], delivery[4]);
                } else {
                    emitSessionResponse(delivery[1], delivery[2], delivery[3], delivery[4]);
                }
            }
        }
        return mapper.createObjectNode();
    }

    /** Ce que la page servira quand on naviguera vers une adresse contenant ce fragment (F-89 / SF-89-05). */
    private final List<String[]> onNavigate = new ArrayList<>();

    /** Livraison à la navigation : {@code session} vide pour l'onglet, sinon celle d'un worker attaché. */
    void deliverOnNavigate(String fragment, String session, String requestId, String url, String body) {
        onNavigate.add(new String[] { fragment, session == null ? "" : session, requestId, url, body });
    }

    /** Une socket WebSocket ouverte par la page (ou une cible attachée). */
    void emitSocket(String sessionId, String requestId, String url) {
        ObjectNode params = mapper.createObjectNode();
        params.put("requestId", requestId);
        params.put("url", url);
        java.util.function.BiConsumer<String, JsonNode> listener = listeners.get("Network.webSocketCreated");
        if (listener != null) {
            listener.accept(sessionId, params);
        }
    }

    /** Une trame reçue sur une socket — son contenu ne doit JAMAIS être lu. */
    void emitSocketFrame(String sessionId, String requestId, String payload) {
        ObjectNode params = mapper.createObjectNode();
        params.put("requestId", requestId);
        params.putObject("response").put("opcode", 1).put("payloadData", payload);
        java.util.function.BiConsumer<String, JsonNode> listener = listeners.get("Network.webSocketFrameReceived");
        if (listener != null) {
            listener.accept(sessionId, params);
        }
    }

    /** Champs de dépôt créés par les scripts d'écriture (F-108 / SF-108-04). */
    private final List<String> injectedInputs = new ArrayList<>();
    /** Fichiers posés dans un champ de dépôt, tels que {@code DOM.setFileInputFiles} les a reçus. */
    private final List<String> droppedFiles = new ArrayList<>();

    List<String> droppedFiles() {
        return List.copyOf(droppedFiles);
    }

    private JsonNode sharePointScript(String expression, ObjectNode result) {
        if (expression.contains("/*cg-input*/")) {
            java.util.regex.Matcher id = java.util.regex.Pattern.compile("i\\.id = \"([^\"]+)\"")
                    .matcher(expression);
            if (id.find()) {
                injectedInputs.add(id.group(1));
            }
            result.putObject("result").put("value", true);
            return result;
        }
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
        if (expression.contains("/*cg-screen:")) {
            // F-89 / SF-89-06 : la lecture d'écran, appliquée à l'écran de papier affiché (ou à rien).
            screenScripts.add(expression);
            if (revealListAfterProbes >= 0 && expression.contains("/*cg-screen:position*/")
                    && ++positionProbes > revealListAfterProbes && revealListScreen != null) {
                // SF-100-10 : la liste a fini par charger — désormais visible sur toute route.
                screens.put("", revealListScreen);
            }
            PaperScreen shown = screenFor(route);
            if (shown == null) {
                result.putObject("result").set("value", expression.contains("/*cg-screen:position*/")
                        ? mapper.getNodeFactory().numberNode(-1)
                        : expression.contains("/*cg-screen:control*/")
                                ? mapper.createObjectNode().put("panel", false)
                                : mapper.createObjectNode().put("found", false).put("moved", false));
                return result;
            }
            result.putObject("result").set("value", shown.evaluate(expression));
            return result;
        }
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
            for (Map.Entry<String, PaperScreen> entry : screensOnClick.entrySet()) {
                if (expression.contains(entry.getKey())) {
                    clickedSelectors.add(entry.getKey());
                    screens.put("", entry.getValue());
                    result.putObject("result").put("value", true);
                    return result;
                }
            }
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
            emitResponse(delivery[0], delivery[1], delivery[2],
                    delivery.length > 3 ? Integer.parseInt(delivery[3]) : 200);
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
        listeners.put(method, (session, params) -> listener.accept(params));
    }

    @Override
    public void onSessionEvent(String method, java.util.function.BiConsumer<String, JsonNode> listener) {
        listeners.put(method, listener);
    }

    /** Commandes adressées à une cible attachée : « session|méthode ». */
    private final List<String> sessionSent = new ArrayList<>();

    @Override
    public JsonNode send(String sessionId, String method, ObjectNode params) {
        if (sessionId == null || sessionId.isBlank()) {
            return send(method, params);
        }
        CdpCommands.assertAllowed(method);
        sessionSent.add(sessionId + "|" + method);
        if (CdpCommands.GET_RESPONSE_BODY.equals(method)) {
            ObjectNode result = mapper.createObjectNode();
            result.put("body", bodies.getOrDefault(sessionId + "|" + params.path("requestId").asText(""), ""));
            result.put("base64Encoded", false);
            return result;
        }
        return mapper.createObjectNode();
    }

    List<String> sessionCommands() {
        return List.copyOf(sessionSent);
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
        java.util.function.BiConsumer<String, JsonNode> listener = listeners.get("Network.responseReceived");
        if (listener != null) {
            listener.accept("", params);
        }
    }

    void purge(String requestId) {
        purged.add(requestId);
    }

    /** Un cadre ou un worker qui s'attache (F-108 / SF-108-01, §4.8), à l'adresse donnée. */
    void emitAttached(String url) {
        ObjectNode params = mapper.createObjectNode();
        params.putObject("targetInfo").put("url", url);
        java.util.function.BiConsumer<String, JsonNode> listener = listeners.get("Target.attachedToTarget");
        if (listener != null) {
            listener.accept("", params);
        }
    }

    /** Un cadre ou un worker qui s'attache avec sa session (F-100 / SF-100-03). */
    void emitAttached(String sessionId, String type, String url) {
        ObjectNode params = mapper.createObjectNode();
        params.put("sessionId", sessionId);
        params.putObject("targetInfo").put("url", url).put("type", type);
        java.util.function.BiConsumer<String, JsonNode> listener = listeners.get("Target.attachedToTarget");
        if (listener != null) {
            listener.accept("", params);
        }
    }

    /** Une réponse reçue par une cible attachée, sur sa session. */
    void emitSessionResponse(String sessionId, String requestId, String url, String body) {
        ObjectNode params = mapper.createObjectNode();
        params.put("requestId", requestId);
        ObjectNode response = params.putObject("response");
        response.put("url", url);
        response.put("status", 200);
        response.put("mimeType", "application/json");
        if (body != null) {
            bodies.put(sessionId + "|" + requestId, body);
        }
        java.util.function.BiConsumer<String, JsonNode> listener = listeners.get("Network.responseReceived");
        if (listener != null) {
            listener.accept(sessionId, params);
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

    /** Même livraison, avec son statut HTTP (un refus 403, F-100). */
    void deliverOnScroll(String requestId, String url, String body, int status) {
        onNextScroll.add(new String[] { requestId, url, body, String.valueOf(status) });
    }

    /** Écrans de papier par fragment de route (F-89 / SF-89-06) ; clé vide : quelle que soit la route. */
    private final Map<String, PaperScreen> screens = new java.util.LinkedHashMap<>();
    /** Écrans qui n'apparaissent qu'après un clic sur un sélecteur contenant ce fragment. */
    private final Map<String, PaperScreen> screensOnClick = new java.util.LinkedHashMap<>();
    private final List<String> screenScripts = new ArrayList<>();
    private final List<String> clickedSelectors = new ArrayList<>();

    /**
     * SF-100-10 : la liste des chats qui « charge » — elle n'apparaît qu'après N sondages de présence
     * ({@code cg-screen:position}), comme le DOM « mid-nav » qui arrive après la navigation vers la vue
     * Chat. {@code -1} : dispositif éteint (comportement d'avant).
     */
    private int revealListAfterProbes = -1;
    private PaperScreen revealListScreen;
    private int positionProbes;

    /** Cet écran est affiché quand la route contient ce fragment ({@code ""} : toujours). */
    void screen(String routeFragment, PaperScreen screen) {
        screens.put(routeFragment, screen);
    }

    /** SF-100-10 : la liste (sur toute route) n'apparaît qu'après {@code probes} sondages de présence. */
    void listPresentAfter(int probes, PaperScreen screen) {
        this.revealListAfterProbes = probes;
        this.revealListScreen = screen;
    }

    /** Cet écran apparaît (sur toute route) après un clic dont le sélecteur contient ce fragment. */
    void screenOnClick(String selectorFragment, PaperScreen screen) {
        screensOnClick.put(selectorFragment, screen);
    }

    List<String> screenScripts() {
        return List.copyOf(screenScripts);
    }

    List<String> clickedSelectors() {
        return List.copyOf(clickedSelectors);
    }

    private PaperScreen screenFor(String currentRoute) {
        PaperScreen found = null;
        for (Map.Entry<String, PaperScreen> entry : screens.entrySet()) {
            if (entry.getKey().isEmpty() || (currentRoute != null && currentRoute.contains(entry.getKey()))) {
                found = entry.getValue();
            }
        }
        return found;
    }

    /** L'onglet est sur cette adresse (F-89 / SF-89-05). */
    void showingUrl(String url) {
        route = url;
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
