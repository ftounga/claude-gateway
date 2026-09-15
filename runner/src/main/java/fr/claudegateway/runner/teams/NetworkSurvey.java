package fr.claudegateway.runner.teams;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * <b>Le relevé réel</b> (F-100 / SF-100-00) : noter <b>d'où</b> et <b>par quels chemins</b> Teams web
 * reçoit ses réponses, sans jamais lire une réponse.
 *
 * <h2>Ce qui est écouté</h2>
 *
 * <ul>
 *   <li>l'<b>onglet Teams</b> ;</li>
 *   <li>les <b>cadres et workers</b> que la page attache — <b>seulement</b> ceux dont l'adresse est sur
 *       un domaine Microsoft autorisé ({@link MicrosoftDomains}, la source unique de F-108) ;</li>
 *   <li>les <b>autres onglets</b> sur ces mêmes domaines (SharePoint, Stream, OneDrive), pour trancher
 *       l'angle mort « vu seulement depuis un autre onglet ».</li>
 * </ul>
 *
 * <h2>Ce qui n'est jamais fait — en mode normal</h2>
 *
 * <p>Aucun corps n'est demandé ({@code Network.getResponseBody} n'est pas émis), aucun en-tête n'est
 * lu, aucune chaîne de requête n'entre ({@link SurveyPaths}). Les seules commandes émises sont
 * {@code Network.enable} et {@code Target.setAutoAttach}, déjà dans la liste blanche : ce mode
 * n'ajoute rien à ce que le runner s'autorise.</p>
 *
 * <h2>Le mode « forme » (F-89 / SF-89-12) — opt-in, jamais par défaut</h2>
 *
 * <p>Quand il est <b>explicitement</b> demandé (drapeau {@code --forme}), le relevé récupère le corps
 * des seules réponses classées {@link #SHAPE_KINDS} et n'en écrit que le <b>squelette</b> — les NOMS de
 * champs et leur TYPE JSON, <b>jamais une valeur</b> ({@link PayloadShape}). C'est ce qui révèle la
 * forme réelle des réponses Teams pour recaler {@link TeamsAdapterV1}. Les corps sont lus <b>hors du
 * fil de la socket</b>, par {@link #captureReadyShapes()} appelé depuis la boucle de commande — jamais
 * dans le fil de l'événement, seul à pouvoir délivrer la réponse. Sans ce drapeau, rien de tout cela
 * n'a lieu : {@code Network.getResponseBody} n'est pas émis.</p>
 *
 * <p><b>Pourquoi un exécuteur.</b> Un événement arrive sur le fil de la socket ; y attendre la réponse
 * d'une commande bloquerait ce même fil, qui est le seul à pouvoir la délivrer. L'activation du réseau
 * sur une cible attachée est donc confiée à un exécuteur.</p>
 */
public final class NetworkSurvey {

    /** D'où une réponse a été vue. C'est la question des trois angles morts du cadrage. */
    public enum Origin {
        TEAMS_TAB, OTHER_TAB, FRAME, WORKER, SERVICE_WORKER
    }

    /** Chemins distincts retenus au plus : au-delà, on compte. */
    static final int MAX_ENTRIES = 2_000;

    /**
     * Les genres dont le mode « forme » relève le squelette (F-89 / SF-89-12), et <b>rien d'autre</b> —
     * y compris {@code MEETING_COLLAB_OBJECT}, dont c'est ici qu'on a découvert la forme (désormais
     * lu par l'adaptateur, SF-89-13). Le squelette ne porte aucune valeur.
     */
    static final Set<TeamsPayloadKind> SHAPE_KINDS = EnumSet.of(
            TeamsPayloadKind.MEETING_DETAILS, TeamsPayloadKind.CALENDAR_EVENT,
            TeamsPayloadKind.CONVERSATION_LIST, TeamsPayloadKind.CONVERSATION_MESSAGES,
            TeamsPayloadKind.MEETING_COLLAB_OBJECT);

    /** Profondeur de récursion du squelette. */
    static final int SHAPE_DEPTH = PayloadShape.DEFAULT_DEPTH;

    /** Squelettes distincts (genre + chemin) retenus au plus : au-delà, on compte. */
    static final int MAX_SHAPES = 200;

    /** Réponses en attente de lecture de forme retenues au plus : au-delà, on compte. */
    static final int MAX_PENDING_SHAPES = 2_000;

    /** Corps au-delà duquel on renonce à lire une forme (garde-fou, aligné sur l'observateur). */
    static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private static final String RESPONSE_RECEIVED = "Network.responseReceived";
    private static final String ATTACHED_TO_TARGET = "Target.attachedToTarget";
    private static final String WEBSOCKET_CREATED = "Network.webSocketCreated";
    private static final String WEBSOCKET_FRAME = "Network.webSocketFrameReceived";

    /** Sockets distinctes (hôte + chemin) retenues au plus. */
    static final int MAX_SOCKETS = 200;
    private static final Set<String> STATIC_TYPES = Set.of("script", "stylesheet", "image", "font");
    private static final List<String> STATIC_EXTENSIONS = List.of(".js", ".css", ".png", ".jpg",
            ".jpeg", ".svg", ".woff", ".woff2", ".map", ".ico", ".gif", ".webp", ".ttf");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Executor executor;
    /** Session d'une cible attachée retenue → origine. Une session absente n'est pas écoutée. */
    private final Map<String, Origin> sessions = new ConcurrentHashMap<>();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Set<String> watchedTabs = ConcurrentHashMap.newKeySet();
    /** Sockets par hôte et chemin (F-89 / SF-89-05) : comptées, jamais lues. */
    private final Map<String, Socket> sockets = new LinkedHashMap<>();
    /** « session|requestId » d'une socket ouverte → clé de sa ligne. */
    private final Map<String, String> openSockets = new java.util.HashMap<>();

    /** Mode « forme » (F-89 / SF-89-12) : opt-in. Faux → aucun corps n'est jamais demandé. */
    private final boolean shapeMode;
    /** « session|requestId » d'une réponse classée dont la forme reste à lire. */
    private final Map<String, PendingShape> pendingShapes = new LinkedHashMap<>();
    /** Squelettes relevés, par « genre + chemin ». */
    private final Map<String, Shape> shapes = new LinkedHashMap<>();

    private volatile int step;
    private int outsideMicrosoft;
    private int staticResources;
    private int dropped;
    private int refusedTargets;
    private int shapesRead;
    private int shapesUnavailable;
    private int shapesDropped;

    public NetworkSurvey(Executor executor) {
        this(executor, false);
    }

    /**
     * @param shapeMode vrai pour relever le <b>squelette</b> des réponses classées {@link #SHAPE_KINDS}
     *                  (noms + types, jamais une valeur) ; faux : aucun corps n'est jamais demandé
     */
    public NetworkSurvey(Executor executor, boolean shapeMode) {
        this.executor = executor == null ? Runnable::run : executor;
        this.shapeMode = shapeMode;
    }

    /** Vrai si le mode « forme » est actif (F-89 / SF-89-12). */
    public boolean shapeMode() {
        return shapeMode;
    }

    /** Écoute l'onglet Teams et les cadres et workers qu'il attache. */
    public void watchTeamsTab(CdpConnection connection) {
        connection.onSessionEvent(RESPONSE_RECEIVED,
                (sessionId, params) -> onResponse(connection, sessionId, Origin.TEAMS_TAB, params));
        watchSockets(connection, Origin.TEAMS_TAB);
        connection.onSessionEvent(ATTACHED_TO_TARGET,
                (parent, params) -> onAttached(connection, params));
        connection.send(CdpCommands.NETWORK_ENABLE, mapper.createObjectNode());
        connection.send(CdpCommands.SET_AUTO_ATTACH, autoAttach());
    }

    /**
     * Écoute un autre onglet, déjà jugé sur un domaine Microsoft par l'appelant — et rejugé ici : le
     * refus est le défaut.
     *
     * @return vrai si l'onglet est désormais écouté
     */
    public boolean watchOtherTab(String targetId, String url, CdpConnection connection) {
        if (targetId == null || !MicrosoftDomains.isAllowed(url) || !watchedTabs.add(targetId)) {
            return false;
        }
        connection.onSessionEvent(RESPONSE_RECEIVED,
                (sessionId, params) -> onResponse(connection, sessionId, Origin.OTHER_TAB, params));
        watchSockets(connection, Origin.OTHER_TAB);
        connection.send(CdpCommands.NETWORK_ENABLE, mapper.createObjectNode());
        return true;
    }

    /**
     * <b>Les sockets</b> (F-89 / SF-89-05) : l'hôte, le chemin sans requête et le nombre de trames reçues
     * — jamais leur contenu. C'est ce qui dira si messages et transcriptions arrivent par ce canal, que
     * l'observation des réponses HTTP ne voit pas.
     */
    private void watchSockets(CdpConnection connection, Origin tabOrigin) {
        connection.onSessionEvent(WEBSOCKET_CREATED, (sessionId, params) -> onSocket(sessionId, tabOrigin, params));
        connection.onSessionEvent(WEBSOCKET_FRAME, (sessionId, params) -> onSocketFrame(sessionId, params));
    }

    private void onSocket(String sessionId, Origin tabOrigin, JsonNode params) {
        if (params == null) {
            return;
        }
        Origin origin = tabOrigin;
        String session = sessionId == null ? "" : sessionId;
        if (!session.isBlank()) {
            origin = sessions.get(session);
            if (origin == null) {
                return;
            }
        }
        String url = ObservedResponse.withoutQuery(params.path("url").asText(""));
        synchronized (this) {
            if (url.isEmpty() || !MicrosoftDomains.isMicrosoftFamily(url)) {
                outsideMicrosoft++;
                return;
            }
            String host = SurveyPaths.hostMotif(url);
            String path = SurveyPaths.template(url);
            String key = host + ' ' + path;
            Socket socket = sockets.get(key);
            if (socket == null) {
                if (sockets.size() >= MAX_SOCKETS) {
                    dropped++;
                    return;
                }
                socket = new Socket(host, path, step);
                sockets.put(key, socket);
            }
            socket.opened++;
            socket.origins.add(origin.name());
            openSockets.put(session + '|' + params.path("requestId").asText(""), key);
        }
    }

    private synchronized void onSocketFrame(String sessionId, JsonNode params) {
        if (params == null) {
            return;
        }
        String key = openSockets.get((sessionId == null ? "" : sessionId) + '|' + params.path("requestId").asText(""));
        Socket socket = key == null ? null : sockets.get(key);
        if (socket != null) {
            socket.frames++; // la trame est comptée ; son contenu (payloadData) n'est jamais lu
        }
    }

    /** Vrai si cet onglet est déjà écouté. */
    public boolean watches(String targetId) {
        return targetId != null && watchedTabs.contains(targetId);
    }

    /** L'étape de la visite guidée en cours (1 un fil, 2 une réunion, 3 récapitulatif, 4 transcription). */
    public void step(int value) {
        this.step = Math.max(0, value);
    }

    public int step() {
        return step;
    }

    private ObjectNode autoAttach() {
        ObjectNode params = mapper.createObjectNode();
        params.put("autoAttach", true);
        params.put("waitForDebuggerOnStart", false);
        params.put("flatten", true);
        return params;
    }

    private void onAttached(CdpConnection connection, JsonNode params) {
        if (params == null) {
            return;
        }
        String sessionId = params.path("sessionId").asText("");
        JsonNode info = params.path("targetInfo");
        String url = info.path("url").asText("");
        if (sessionId.isEmpty() || !MicrosoftDomains.isAllowed(url)) {
            // §4.8 de F-108 : un cadre ou un worker hors liste n'est pas écouté. Le refus est le défaut.
            synchronized (this) {
                refusedTargets++;
            }
            return;
        }
        Origin origin = originOf(info.path("type").asText(""));
        sessions.put(sessionId, origin);
        executor.execute(() -> {
            try {
                connection.send(sessionId, CdpCommands.NETWORK_ENABLE, mapper.createObjectNode());
                // Un cadre peut à son tour porter un worker : on lui demande de s'annoncer aussi.
                connection.send(sessionId, CdpCommands.SET_AUTO_ATTACH, autoAttach());
            } catch (RuntimeException e) {
                sessions.remove(sessionId); // cible déjà partie : rien à écouter
            }
        });
    }

    static Origin originOf(String targetType) {
        return switch (targetType == null ? "" : targetType.toLowerCase(Locale.ROOT)) {
            case "service_worker" -> Origin.SERVICE_WORKER;
            case "worker", "shared_worker" -> Origin.WORKER;
            case "page" -> Origin.OTHER_TAB;
            default -> Origin.FRAME;
        };
    }

    private void onResponse(CdpConnection connection, String sessionId, Origin tabOrigin,
            JsonNode params) {
        if (params == null) {
            return;
        }
        Origin origin = tabOrigin;
        String session = sessionId == null ? "" : sessionId;
        if (!session.isBlank()) {
            origin = sessions.get(session);
            if (origin == null) {
                return; // une session que nous n'avons pas retenue : jamais écoutée, jamais notée
            }
        }
        JsonNode response = params.path("response");
        // On ne lit QUE l'adresse, le statut, le type MIME et le type de ressource. Les en-têtes sont
        // dans le même objet, et on n'y touche pas.
        String url = ObservedResponse.withoutQuery(response.path("url").asText(""));
        String resourceType = params.path("type").asText("");
        int status = response.path("status").asInt(0);
        record(origin, url, resourceType, response.path("mimeType").asText(""), status);
        // F-89 / SF-89-12 : en mode forme SEULEMENT, on note la réponse pour en lire la FORME plus tard
        // (jamais ici, sur le fil de la socket). Sans mode forme, aucun corps n'est jamais demandé.
        if (shapeMode) {
            queueShape(connection, session, origin, url, status, params.path("requestId").asText(""));
        }
    }

    /**
     * Met une réponse classée en attente de lecture de forme — <b>uniquement</b> si son URL est
     * classée {@link #SHAPE_KINDS}, qu'elle n'a pas été refusée (401/403 : le corps serait un message
     * d'erreur) et qu'elle porte un identifiant de requête. Aucun corps n'est demandé ici.
     */
    private synchronized void queueShape(CdpConnection connection, String session, Origin origin,
            String url, int status, String requestId) {
        if (requestId.isEmpty() || status == 401 || status == 403) {
            return;
        }
        TeamsPayloadKind kind = TeamsUrls.classify(url);
        if (!SHAPE_KINDS.contains(kind)) {
            return;
        }
        if (pendingShapes.size() >= MAX_PENDING_SHAPES) {
            shapesDropped++;
            return;
        }
        pendingShapes.put(session + '|' + requestId, new PendingShape(connection, session, requestId,
                kind, SurveyPaths.hostMotif(url), SurveyPaths.template(url), origin.name(),
                TeamsUrls.apiVersions(url), step));
    }

    /**
     * <b>Lit la forme des réponses classées en attente</b> (F-89 / SF-89-12), puis vide la file. À
     * appeler <b>hors du fil de la socket</b> — depuis la boucle de commande —, jamais dans le fil de
     * l'événement : le protocole ne remet le corps qu'une fois la réponse complète, et bloquer
     * l'événement ralentirait la page. Seul le <b>squelette</b> (noms + types) est retenu ; aucune
     * valeur n'entre. Ne fait rien hors mode forme.
     */
    public void captureReadyShapes() {
        if (!shapeMode) {
            return;
        }
        List<PendingShape> ready;
        synchronized (this) {
            ready = new ArrayList<>(pendingShapes.values());
            pendingShapes.clear();
        }
        for (PendingShape pending : ready) {
            recordShape(pending, fetchBody(pending));
        }
    }

    /** Le corps d'une réponse, en JSON, ou {@code null} si indisponible. Jamais un en-tête. */
    private JsonNode fetchBody(PendingShape pending) {
        ObjectNode params = mapper.createObjectNode();
        params.put("requestId", pending.requestId());
        try {
            JsonNode result = pending.connection().send(pending.session(),
                    CdpCommands.GET_RESPONSE_BODY, params);
            String raw = result == null ? "" : result.path("body").asText("");
            if (raw.isEmpty()) {
                return null;
            }
            if (result.path("base64Encoded").asBoolean(false)) {
                byte[] decoded = Base64.getDecoder().decode(raw);
                if (decoded.length > MAX_BODY_BYTES) {
                    return null;
                }
                raw = new String(decoded, StandardCharsets.UTF_8);
            } else if (raw.length() > MAX_BODY_BYTES) {
                return null;
            }
            return mapper.readTree(raw);
        } catch (RuntimeException | java.io.IOException e) {
            return null; // corps déjà purgé, non-JSON, ou liaison partie : la forme est simplement absente
        }
    }

    /** Range le squelette d'un corps lu, sous « genre + chemin ». Aucune valeur n'entre. */
    private synchronized void recordShape(PendingShape pending, JsonNode body) {
        if (body == null) {
            shapesUnavailable++;
            return;
        }
        String key = pending.kind().name() + ' ' + pending.path();
        Shape shape = shapes.get(key);
        if (shape == null) {
            if (shapes.size() >= MAX_SHAPES) {
                shapesDropped++;
                return;
            }
            shape = new Shape(pending.kind(), pending.host(), pending.path(),
                    PayloadShape.of(body, SHAPE_DEPTH), pending.firstStep());
            shapes.put(key, shape);
        }
        shape.count++;
        shape.origins.add(pending.origin());
        shape.apiVersions.addAll(pending.apiVersions());
        shapesRead++;
    }

    /** Une réponse classée en attente de lecture de forme. */
    private record PendingShape(CdpConnection connection, String session, String requestId,
            TeamsPayloadKind kind, String host, String path, String origin, List<String> apiVersions,
            int firstStep) {
    }

    private synchronized void record(Origin origin, String url, String resourceType, String mime,
            int status) {
        // F-89 / SF-89-05 : relevé élargi à la FAMILLE d'hôtes Microsoft (comptage seulement ; les gestes et
        // l'écoute des cadres restent bornés à MicrosoftDomains.isAllowed). Le premier relevé réel avait
        // compté 176 réponses « hors domaines » sans pouvoir dire lesquelles.
        if (url.isEmpty() || !MicrosoftDomains.isMicrosoftFamily(url)) {
            outsideMicrosoft++;
            return;
        }
        if (isStatic(url, resourceType)) {
            staticResources++;
            return;
        }
        String host = SurveyPaths.hostMotif(url);
        String path = SurveyPaths.template(url);
        String key = host + ' ' + path;
        Entry entry = entries.get(key);
        if (entry == null) {
            if (entries.size() >= MAX_ENTRIES) {
                dropped++;
                return;
            }
            // F-108 / SF-108-03 : un appel SharePoint que l'adaptateur fichiers emprunte est classé
            // sous son nom — le relevé dit alors si le poste réel sert la forme documentée.
            String files = SharePointFiles.endpointOf(url);
            entry = new Entry(host, path, files.isEmpty() ? TeamsUrls.classify(url).name() : files,
                    step);
            entries.put(key, entry);
        }
        entry.count++;
        entry.origins.add(origin.name());
        if (!resourceType.isBlank()) {
            entry.resourceTypes.add(resourceType);
        }
        if (!mime.isBlank()) {
            entry.mimeTypes.add(baseMime(mime));
        }
        if (status > 0) {
            entry.statuses.add(status);
        }
        entry.steps.add(step);
    }

    private static boolean isStatic(String url, String resourceType) {
        if (STATIC_TYPES.contains(resourceType.toLowerCase(Locale.ROOT))) {
            return true;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return STATIC_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private static String baseMime(String mime) {
        int separator = mime.indexOf(';');
        return (separator < 0 ? mime : mime.substring(0, separator)).strip().toLowerCase(Locale.ROOT);
    }

    /** L'état du relevé à cet instant : ce qui sera écrit dans le rapport. */
    public synchronized Snapshot snapshot() {
        List<Entry> copy = new ArrayList<>();
        entries.values().forEach(entry -> copy.add(entry.copy()));
        List<Socket> socketCopy = new ArrayList<>();
        sockets.values().forEach(socket -> socketCopy.add(socket.copy()));
        List<Shape> shapeCopy = new ArrayList<>();
        shapes.values().forEach(shape -> shapeCopy.add(shape.copy()));
        return new Snapshot(copy, outsideMicrosoft, staticResources, dropped, refusedTargets, socketCopy,
                shapeMode, shapeCopy, shapesRead, shapesUnavailable, shapesDropped);
    }

    /** Une socket relevée : hôte, chemin gabarisé, origines, ouvertures et trames — jamais une trame lue. */
    public static final class Socket {

        final String host;
        final String path;
        final int firstStep;
        final Set<String> origins = new LinkedHashSet<>();
        int opened;
        int frames;

        Socket(String host, String path, int firstStep) {
            this.host = host;
            this.path = path;
            this.firstStep = firstStep;
        }

        Socket copy() {
            Socket copy = new Socket(host, path, firstStep);
            copy.origins.addAll(origins);
            copy.opened = opened;
            copy.frames = frames;
            return copy;
        }

        public String host() {
            return host;
        }

        public String path() {
            return path;
        }

        public int opened() {
            return opened;
        }

        public int frames() {
            return frames;
        }

        public Set<String> origins() {
            return Set.copyOf(origins);
        }

        public int firstStep() {
            return firstStep;
        }
    }

    /**
     * Le squelette relevé d'un genre de réponse (F-89 / SF-89-12) : le genre, l'hôte motif, le chemin
     * gabarisé, les origines, les versions d'API observées, et la <b>forme</b> (noms + types) — jamais
     * une valeur.
     */
    public static final class Shape {

        final TeamsPayloadKind kind;
        final String host;
        final String path;
        final String skeleton;
        final int firstStep;
        final Set<String> origins = new LinkedHashSet<>();
        final Set<String> apiVersions = new LinkedHashSet<>();
        int count;

        Shape(TeamsPayloadKind kind, String host, String path, String skeleton, int firstStep) {
            this.kind = kind;
            this.host = host;
            this.path = path;
            this.skeleton = skeleton;
            this.firstStep = firstStep;
        }

        Shape copy() {
            Shape copy = new Shape(kind, host, path, skeleton, firstStep);
            copy.origins.addAll(origins);
            copy.apiVersions.addAll(apiVersions);
            copy.count = count;
            return copy;
        }

        public TeamsPayloadKind kind() {
            return kind;
        }

        public String host() {
            return host;
        }

        public String path() {
            return path;
        }

        /** La forme : les NOMS de champs et leur TYPE JSON, jamais une valeur. */
        public String skeleton() {
            return skeleton;
        }

        public int firstStep() {
            return firstStep;
        }

        public Set<String> origins() {
            return Set.copyOf(origins);
        }

        public Set<String> apiVersions() {
            return Set.copyOf(apiVersions);
        }

        public int count() {
            return count;
        }
    }

    /** Un chemin relevé : ce qui a le droit d'être écrit, et rien d'autre. */
    public static final class Entry {

        final String host;
        final String path;
        final String classification;
        final int firstStep;
        final Set<String> origins = new LinkedHashSet<>();
        final Set<String> resourceTypes = new LinkedHashSet<>();
        final Set<String> mimeTypes = new LinkedHashSet<>();
        final Set<Integer> statuses = new LinkedHashSet<>();
        final Set<Integer> steps = new LinkedHashSet<>();
        int count;

        Entry(String host, String path, String classification, int firstStep) {
            this.host = host;
            this.path = path;
            this.classification = classification;
            this.firstStep = firstStep;
        }

        Entry copy() {
            Entry copy = new Entry(host, path, classification, firstStep);
            copy.origins.addAll(origins);
            copy.resourceTypes.addAll(resourceTypes);
            copy.mimeTypes.addAll(mimeTypes);
            copy.statuses.addAll(statuses);
            copy.steps.addAll(steps);
            copy.count = count;
            return copy;
        }

        public String host() {
            return host;
        }

        public String path() {
            return path;
        }

        public String classification() {
            return classification;
        }

        public int firstStep() {
            return firstStep;
        }

        public Set<String> origins() {
            return Set.copyOf(origins);
        }

        public int count() {
            return count;
        }

        /** Vu seulement hors de l'onglet Teams : un des trois angles morts du cadrage. */
        public boolean onlyOutsideTeamsTab() {
            return !origins.contains(Origin.TEAMS_TAB.name());
        }
    }

    /**
     * Le relevé figé.
     *
     * @param entries          chemins distincts, dans l'ordre de première vue
     * @param outsideMicrosoft réponses hors domaines Microsoft, comptées et non détaillées
     * @param staticResources  scripts, styles, images, polices écartés
     * @param dropped          réponses de chemins nouveaux au-delà de {@link #MAX_ENTRIES}
     * @param refusedTargets    cibles attachées hors domaines Microsoft, jamais écoutées
     * @param sockets           sockets WebSocket de la famille Microsoft, par hôte et chemin (F-89 / SF-89-05)
     * @param shapeMode         vrai si le mode « forme » était actif (F-89 / SF-89-12)
     * @param shapes            squelettes relevés des réponses classées (noms + types, jamais une valeur)
     * @param shapesRead        corps lus pour leur forme
     * @param shapesUnavailable corps classés mais indisponibles (purgés, non-JSON, trop volumineux)
     * @param shapesDropped     réponses classées non relevées au-delà des plafonds
     */
    public record Snapshot(List<Entry> entries, int outsideMicrosoft, int staticResources, int dropped,
            int refusedTargets, List<Socket> sockets, boolean shapeMode, List<Shape> shapes,
            int shapesRead, int shapesUnavailable, int shapesDropped) {

        public Snapshot {
            sockets = sockets == null ? List.of() : List.copyOf(sockets);
            shapes = shapes == null ? List.of() : List.copyOf(shapes);
        }

        /** Forme d'avant F-89 / SF-89-05 : aucune socket relevée. */
        public Snapshot(List<Entry> entries, int outsideMicrosoft, int staticResources, int dropped,
                int refusedTargets) {
            this(entries, outsideMicrosoft, staticResources, dropped, refusedTargets, List.of());
        }

        /** Forme d'avant F-89 / SF-89-12 : aucun mode forme, aucun squelette. */
        public Snapshot(List<Entry> entries, int outsideMicrosoft, int staticResources, int dropped,
                int refusedTargets, List<Socket> sockets) {
            this(entries, outsideMicrosoft, staticResources, dropped, refusedTargets, sockets, false,
                    List.of(), 0, 0, 0);
        }
    }
}
