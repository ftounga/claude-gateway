package fr.claudegateway.runner.teams;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * <b>On observe le réseau, pas le DOM</b> (F-87 / SF-87-02) — la décision technique du volet.
 *
 * <p>Quand Teams affiche une conversation, il ne la fabrique pas : il appelle ses propres services
 * et reçoit du JSON structuré. Cette classe écoute ces réponses pendant que la page fait son travail
 * normal, et récupère le corps de celles que l'adaptateur reconnaît. On reçoit des objets déjà
 * structurés au lieu de texte à reconstituer, et une refonte visuelle ne change rien.</p>
 *
 * <p><b>Ce qui n'est jamais lu</b> : les en-têtes de la réponse. L'événement du protocole les porte,
 * et nous n'y touchons pas — ils contiennent les cookies de la session.</p>
 *
 * <p><b>Ce qui est déclaré quand ça manque</b> : un corps que le navigateur a déjà purgé produit un
 * {@link TeamsGapKind#BODY_UNAVAILABLE}. Il n'y a pas de troisième issue : soit on a lu, soit on
 * dit qu'on n'a pas lu.</p>
 */
public final class NetworkObserver {

    /** Corps au-delà duquel on renonce : une réponse de cette taille n'est pas une conversation. */
    public static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private static final String RESPONSE_RECEIVED = "Network.responseReceived";
    private static final String ATTACHED_TO_TARGET = "Target.attachedToTarget";

    private final CdpConnection connection;
    private final TeamsAdapter adapter;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Réponses vues, dans l'ordre d'arrivée : identifiant de requête → adresse sans requête. */
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final List<TeamsGap> gaps = new ArrayList<>();
    /** Réponses refusées (401/403) par nature, depuis le rattachement (F-100 / SF-100-01). */
    private final Map<TeamsPayloadKind, Integer> denied = new java.util.concurrent.ConcurrentHashMap<>();
    /** Cadres et workers réellement retenus pour l'observation — filtrés sur les domaines (§4.8). */
    private final List<String> attachedFrames = new ArrayList<>();
    /**
     * Chemins SharePoint / OneDrive vus passer (F-108 / SF-108-03) : hôte et chemin, <b>jamais</b> la
     * chaîne de requête, jamais le corps. C'est le relevé du diagnostic, et la source des sites
     * d'équipe que les outils fichiers savent retrouver.
     */
    private final java.util.Set<String> filePaths = new java.util.LinkedHashSet<>();

    /** Chemins de fichiers retenus au plus : au-delà, les nouveaux sont ignorés. */
    public static final int MAX_FILE_PATHS = 200;

    /** Sessions des cadres et workers Microsoft écoutés (F-100 / SF-100-03) : seules leurs réponses entrent. */
    private final java.util.Set<String> frameSessions = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * Où l'écoute d'une cible attachée est activée : <b>jamais</b> sur le fil de la socket, qui est le seul
     * à pouvoir délivrer la réponse de la commande.
     */
    private java.util.concurrent.Executor executor;
    private boolean framesObserved;

    public NetworkObserver(CdpConnection connection, TeamsAdapter adapter) {
        this(connection, adapter, null);
    }

    /** @param executor où activer l'écoute d'une cible attachée ; {@code null} : un fil de fond dédié */
    NetworkObserver(CdpConnection connection, TeamsAdapter adapter, java.util.concurrent.Executor executor) {
        this.connection = connection;
        this.adapter = adapter;
        this.executor = executor;
    }

    /**
     * Commence à écouter. Les réponses sans intérêt (ressources, télémétrie) sont écartées <b>sans
     * bruit</b> : les compter comme des manques ferait crier la sonde de santé à chaque feuille de
     * style.
     */
    public void start() {
        connection.send(CdpCommands.NETWORK_ENABLE, mapper.createObjectNode());
        connection.onSessionEvent(RESPONSE_RECEIVED, this::onResponse);
    }

    /**
     * <b>Élargit l'observation aux cadres et workers de la page</b> (F-108 / SF-108-01, §4.8) —
     * <b>filtrée sur les mêmes domaines</b>. Lève les angles morts relevés en F-100 : le lecteur
     * Stream intégré, le service worker.
     *
     * <p>L'auto-attach est demandé au navigateur ; mais un cadre ou un worker qui a quitté les
     * domaines Microsoft <b>n'est pas retenu</b> — un iframe publicitaire, une page tierce embarquée
     * ne sont jamais observés. Le filtre est ici, au moment où le cadre s'annonce, et il refait le
     * même jugement que les gestes : {@link MicrosoftDomains#isAllowed(String)}.</p>
     */
    public synchronized void observeFrames() {
        if (framesObserved) {
            return; // une fois par liaison : la synchro du soir le demande à chaque passage
        }
        framesObserved = true;
        if (executor == null) {
            // Créé seulement quand l'observation des cadres est demandée : un volet qui ne s'en sert pas
            // ne paie aucun fil de plus.
            executor = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "teams-cadres");
                thread.setDaemon(true);
                return thread;
            });
        }
        connection.onSessionEvent(ATTACHED_TO_TARGET, (parent, params) -> onAttached(params));
        ObjectNode params = mapper.createObjectNode();
        params.put("autoAttach", true);
        params.put("waitForDebuggerOnStart", false);
        params.put("flatten", true);
        connection.send(CdpCommands.SET_AUTO_ATTACH, params);
    }

    private void onAttached(JsonNode params) {
        if (params == null) {
            return;
        }
        String url = ObservedResponse.withoutQuery(params.path("targetInfo").path("url").asText(""));
        // §4.8 : un cadre ou un worker hors liste n'est pas attaché. Le refus est le défaut.
        if (url.isEmpty() || !MicrosoftDomains.isAllowed(url)) {
            return;
        }
        synchronized (this) {
            attachedFrames.add(url);
        }
        String sessionId = params.path("sessionId").asText("");
        if (sessionId.isEmpty()) {
            return;
        }
        // F-100 / SF-100-03 : un cadre ou un worker ne parle pas sur la session de l'onglet. Son réseau
        // est écouté SUR SA SESSION — les réponses du lecteur Stream intégré, du service worker —, et ses
        // corps sont demandés sur la même session. Hors liste, rien de tout cela.
        frameSessions.add(sessionId);
        java.util.concurrent.Executor runOn;
        synchronized (this) {
            runOn = executor == null ? Runnable::run : executor;
        }
        runOn.execute(() -> {
            try {
                connection.send(sessionId, CdpCommands.NETWORK_ENABLE, mapper.createObjectNode());
            } catch (RuntimeException e) {
                frameSessions.remove(sessionId); // cible déjà partie
            }
        });
    }

    /** Les cadres et workers retenus pour l'observation (domaines Microsoft uniquement). */
    public synchronized List<String> attachedFrames() {
        return List.copyOf(attachedFrames);
    }

    private void onResponse(String sessionId, JsonNode params) {
        JsonNode response = params == null ? null : params.get("response");
        if (response == null) {
            return;
        }
        String session = sessionId == null ? "" : sessionId;
        if (!session.isEmpty() && !frameSessions.contains(session)) {
            return; // une cible que nous n'avons pas retenue : jamais lue
        }
        // On ne lit QUE l'adresse. Les en-têtes sont là, à portée de main, et on n'y touche pas :
        // ils portent les cookies de la session.
        String url = ObservedResponse.withoutQuery(response.path("url").asText(""));
        noteFilePath(url);
        TeamsPayloadKind kind = adapter.classify(url);
        if (kind == TeamsPayloadKind.IGNORED || kind == TeamsPayloadKind.UNKNOWN) {
            return;
        }
        int status = response.path("status").asInt(0);
        if (status == 401 || status == 403) {
            // F-100 / SF-100-01 : une réponse REFUSÉE dit quelque chose des droits (une transcription
            // dont l'accès est refusé n'est pas une réunion vide). On la compte ; son corps — un
            // message d'erreur — n'est pas demandé.
            denied.merge(kind, 1, Integer::sum);
            return;
        }
        String requestId = params.path("requestId").asText("");
        if (!requestId.isEmpty()) {
            synchronized (pending) {
                // Les identifiants de requête sont propres à chaque cible : la clé porte la session.
                pending.put(session + '|' + requestId, new Pending(url, kind, session, requestId));
            }
        }
    }

    /**
     * Récupère les corps des réponses observées jusqu'ici, et vide la file.
     *
     * <p>La récupération se fait <b>après coup</b>, et non dans le fil de l'événement : le protocole
     * ne remet le corps que lorsque la réponse est complète, et bloquer l'événement ralentirait la
     * page de l'utilisateur.</p>
     */
    public List<ObservedResponse> collect() {
        List<Pending> waiting;
        synchronized (pending) {
            waiting = new ArrayList<>(pending.values());
            pending.clear();
        }
        List<ObservedResponse> observed = new ArrayList<>();
        for (Pending response : waiting) {
            JsonNode body = fetchBody(response.session(), response.requestId(), response.url());
            observed.add(new ObservedResponse(response.url(), response.kind(), body));
        }
        return observed;
    }

    /** Relève un chemin SharePoint / OneDrive, sans requête (déjà retirée) ni corps. */
    private synchronized void noteFilePath(String url) {
        String host = MicrosoftDomains.hostOf(url);
        if (!(host.endsWith(".sharepoint.com") || "onedrive.live.com".equals(host))
                || !MicrosoftDomains.isAllowed(url) || filePaths.size() >= MAX_FILE_PATHS) {
            return;
        }
        filePaths.add(url);
    }

    /**
     * Les chemins SharePoint / OneDrive observés depuis le rattachement (F-108 / SF-108-03) — adresse
     * <b>sans</b> requête ; aucun corps, aucun en-tête.
     */
    public synchronized List<String> observedFilePaths() {
        return List.copyOf(filePaths);
    }

    /** Réponses de cette nature refusées par le service (401/403) depuis le rattachement. */
    public int denied(TeamsPayloadKind kind) {
        return kind == null ? 0 : denied.getOrDefault(kind, 0);
    }

    /** Ce qui n'a pas pu être lu depuis le début de l'observation. */
    public List<TeamsGap> gaps() {
        return List.copyOf(gaps);
    }

    private JsonNode fetchBody(String session, String requestId, String url) {
        ObjectNode params = mapper.createObjectNode();
        params.put("requestId", requestId);
        try {
            JsonNode result = session.isEmpty() ? connection.send(CdpCommands.GET_RESPONSE_BODY, params)
                    : connection.send(session, CdpCommands.GET_RESPONSE_BODY, params);
            String raw = result == null ? "" : result.path("body").asText("");
            if (raw.isEmpty()) {
                return missing(url, "corps vide");
            }
            if (result.path("base64Encoded").asBoolean(false)) {
                byte[] decoded = java.util.Base64.getDecoder().decode(raw);
                if (decoded.length > MAX_BODY_BYTES) {
                    return missing(url, "réponse trop volumineuse");
                }
                raw = new String(decoded, StandardCharsets.UTF_8);
            } else if (raw.length() > MAX_BODY_BYTES) {
                return missing(url, "réponse trop volumineuse");
            }
            return mapper.readTree(raw);
        } catch (BrowserLinkException e) {
            throw e; // une commande refusée est une faute de programmation, pas un aléa réseau
        } catch (Exception e) {
            return missing(url, "corps déjà purgé par le navigateur");
        }
    }

    private JsonNode missing(String url, String why) {
        gaps.add(TeamsGap.of(TeamsGapKind.BODY_UNAVAILABLE, shortOf(url), why));
        return null;
    }

    /** Le dernier segment de l'adresse : de quoi situer le manque, sans recopier une URL entière. */
    private static String shortOf(String url) {
        int slash = url.lastIndexOf('/');
        return slash >= 0 && slash + 1 < url.length() ? url.substring(slash + 1) : url;
    }

    private record Pending(String url, TeamsPayloadKind kind, String session, String requestId) {
    }
}
