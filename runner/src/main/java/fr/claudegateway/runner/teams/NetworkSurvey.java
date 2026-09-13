package fr.claudegateway.runner.teams;

import java.util.ArrayList;
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
 * <h2>Ce qui n'est jamais fait</h2>
 *
 * <p>Aucun corps n'est demandé ({@code Network.getResponseBody} n'est pas émis), aucun en-tête n'est
 * lu, aucune chaîne de requête n'entre ({@link SurveyPaths}). Les seules commandes émises sont
 * {@code Network.enable} et {@code Target.setAutoAttach}, déjà dans la liste blanche : ce mode
 * n'ajoute rien à ce que le runner s'autorise.</p>
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

    private static final String RESPONSE_RECEIVED = "Network.responseReceived";
    private static final String ATTACHED_TO_TARGET = "Target.attachedToTarget";
    private static final Set<String> STATIC_TYPES = Set.of("script", "stylesheet", "image", "font");
    private static final List<String> STATIC_EXTENSIONS = List.of(".js", ".css", ".png", ".jpg",
            ".jpeg", ".svg", ".woff", ".woff2", ".map", ".ico", ".gif", ".webp", ".ttf");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Executor executor;
    /** Session d'une cible attachée retenue → origine. Une session absente n'est pas écoutée. */
    private final Map<String, Origin> sessions = new ConcurrentHashMap<>();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Set<String> watchedTabs = ConcurrentHashMap.newKeySet();

    private volatile int step;
    private int outsideMicrosoft;
    private int staticResources;
    private int dropped;
    private int refusedTargets;

    public NetworkSurvey(Executor executor) {
        this.executor = executor == null ? Runnable::run : executor;
    }

    /** Écoute l'onglet Teams et les cadres et workers qu'il attache. */
    public void watchTeamsTab(CdpConnection connection) {
        connection.onSessionEvent(RESPONSE_RECEIVED,
                (sessionId, params) -> onResponse(sessionId, Origin.TEAMS_TAB, params));
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
                (sessionId, params) -> onResponse(sessionId, Origin.OTHER_TAB, params));
        connection.send(CdpCommands.NETWORK_ENABLE, mapper.createObjectNode());
        return true;
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

    private void onResponse(String sessionId, Origin tabOrigin, JsonNode params) {
        if (params == null) {
            return;
        }
        Origin origin = tabOrigin;
        if (sessionId != null && !sessionId.isBlank()) {
            origin = sessions.get(sessionId);
            if (origin == null) {
                return; // une session que nous n'avons pas retenue : jamais écoutée, jamais notée
            }
        }
        JsonNode response = params.path("response");
        // On ne lit QUE l'adresse, le statut, le type MIME et le type de ressource. Les en-têtes sont
        // dans le même objet, et on n'y touche pas.
        String url = ObservedResponse.withoutQuery(response.path("url").asText(""));
        String resourceType = params.path("type").asText("");
        record(origin, url, resourceType, response.path("mimeType").asText(""),
                response.path("status").asInt(0));
    }

    private synchronized void record(Origin origin, String url, String resourceType, String mime,
            int status) {
        if (url.isEmpty() || !MicrosoftDomains.isAllowed(url)) {
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
        return new Snapshot(copy, outsideMicrosoft, staticResources, dropped, refusedTargets);
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
     * @param refusedTargets   cibles attachées hors domaines Microsoft, jamais écoutées
     */
    public record Snapshot(List<Entry> entries, int outsideMicrosoft, int staticResources, int dropped,
            int refusedTargets) {
    }
}
