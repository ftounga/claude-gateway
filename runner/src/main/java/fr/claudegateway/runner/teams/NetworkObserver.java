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
    /** Cadres et workers réellement retenus pour l'observation — filtrés sur les domaines (§4.8). */
    private final List<String> attachedFrames = new ArrayList<>();

    public NetworkObserver(CdpConnection connection, TeamsAdapter adapter) {
        this.connection = connection;
        this.adapter = adapter;
    }

    /**
     * Commence à écouter. Les réponses sans intérêt (ressources, télémétrie) sont écartées <b>sans
     * bruit</b> : les compter comme des manques ferait crier la sonde de santé à chaque feuille de
     * style.
     */
    public void start() {
        connection.send(CdpCommands.NETWORK_ENABLE, mapper.createObjectNode());
        connection.onEvent(RESPONSE_RECEIVED, this::onResponse);
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
    public void observeFrames() {
        ObjectNode params = mapper.createObjectNode();
        params.put("autoAttach", true);
        params.put("waitForDebuggerOnStart", false);
        params.put("flatten", true);
        connection.send(CdpCommands.SET_AUTO_ATTACH, params);
        connection.onEvent(ATTACHED_TO_TARGET, this::onAttached);
    }

    private void onAttached(JsonNode params) {
        if (params == null) {
            return;
        }
        String url = ObservedResponse.withoutQuery(params.path("targetInfo").path("url").asText(""));
        // §4.8 : un cadre ou un worker hors liste n'est pas attaché. Le refus est le défaut.
        if (!url.isEmpty() && MicrosoftDomains.isAllowed(url)) {
            attachedFrames.add(url);
        }
    }

    /** Les cadres et workers retenus pour l'observation (domaines Microsoft uniquement). */
    public List<String> attachedFrames() {
        return List.copyOf(attachedFrames);
    }

    private void onResponse(JsonNode params) {
        JsonNode response = params == null ? null : params.get("response");
        if (response == null) {
            return;
        }
        // On ne lit QUE l'adresse. Les en-têtes sont là, à portée de main, et on n'y touche pas :
        // ils portent les cookies de la session.
        String url = ObservedResponse.withoutQuery(response.path("url").asText(""));
        TeamsPayloadKind kind = adapter.classify(url);
        if (kind == TeamsPayloadKind.IGNORED || kind == TeamsPayloadKind.UNKNOWN) {
            return;
        }
        String requestId = params.path("requestId").asText("");
        if (!requestId.isEmpty()) {
            pending.put(requestId, new Pending(url, kind));
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
        List<ObservedResponse> observed = new ArrayList<>();
        for (Map.Entry<String, Pending> entry : pending.entrySet()) {
            Pending waiting = entry.getValue();
            JsonNode body = fetchBody(entry.getKey(), waiting.url);
            observed.add(new ObservedResponse(waiting.url, waiting.kind, body));
        }
        pending.clear();
        return observed;
    }

    /** Ce qui n'a pas pu être lu depuis le début de l'observation. */
    public List<TeamsGap> gaps() {
        return List.copyOf(gaps);
    }

    private JsonNode fetchBody(String requestId, String url) {
        ObjectNode params = mapper.createObjectNode();
        params.put("requestId", requestId);
        try {
            JsonNode result = connection.send(CdpCommands.GET_RESPONSE_BODY, params);
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

    private record Pending(String url, TeamsPayloadKind kind) {
    }
}
