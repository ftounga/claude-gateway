package fr.claudegateway.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.ai.AIProviderException;
import fr.claudegateway.ai.AIProviderUnavailableException;
import fr.claudegateway.ai.AnthropicProperties;

/**
 * Implémentation Anthropic de {@link AiAgentProvider} (F-28) : relaie un tour à {@code POST /v1/messages}
 * avec {@code tools} + {@code system}, et traduit la réponse (blocs {@code text}/{@code tool_use},
 * {@code stop_reason}, {@code usage}) en {@link AgentTurn}. Le plafond de sortie est celui de l'agent
 * ({@code agent-max-tokens}), pas celui du chat : écrire un fichier consomme la sortie du modèle.
 *
 * <p><b>Cache de prompt</b> (F-39 / SF-39-01) : le préfixe stable de la requête — outils, consigne
 * système, historique déjà envoyé — porte des marqueurs {@code cache_control}. Sans eux, chaque
 * itération d'un tour renvoie tout au tarif plein et le volume facturé croît en N².</p>
 *
 * <p><b>Raisonnement</b> (F-39 / SF-39-10) : le tour peut demander un raisonnement <b>adaptatif</b>
 * ({@code thinking}) et un niveau d'<b>effort</b> ({@code output_config}). Les blocs de raisonnement
 * rendus par le fournisseur sont <b>signés</b> : ils remontent dans {@link AgentTurn#reasoning()} et
 * sont réémis tels quels quand l'appelant les replace dans le message assistant.</p>
 *
 * <p><b>Tenue longue</b> (F-39 / SF-39-11) : l'appel porte un délai HTTP propre à l'agent, et les
 * deux refus <b>temporaires</b> du fournisseur ({@code 429}, {@code 529}) sont rejoués selon
 * {@link AgentRetryPolicy}. Le corps est calculé <b>une fois</b> : un réessai rejoue l'appel, il ne
 * le reconstruit pas.</p>
 *
 * <p>Le mapping fournisseur est confiné ici ; le domaine reste neutre. La clé n'est jamais
 * journalisée.</p>
 */
@Component
public class AnthropicAgentProvider implements AiAgentProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicAgentProvider.class);

    /**
     * Outils <b>exécutés par le fournisseur</b> — recherche et lecture web (F-39 / SF-39-20).
     *
     * <p>Provider-First : Claude sait chercher sur le web, et le fait chez lui. Il n'y a donc ni
     * boucle à écrire, ni requête sortante à émettre depuis la gateway, ni résultat à exécuter :
     * les réponses arrivent dans le même message, sous forme de blocs de résultat. Ne pas les
     * déclarer revenait à priver l'agent d'une capacité qu'il possède déjà — et il le disait :
     * « je n'ai pas accès au réseau ».</p>
     *
     * <p>Déclarés <b>en fin</b> de liste et <b>toujours identiques</b> : le préfixe caché
     * (SF-39-01) reste stable d'un tour à l'autre.</p>
     */
    private static final List<Map<String, Object>> SERVER_TOOLS = List.of(
            Map.of("type", "web_search_20260209", "name", "web_search"),
            Map.of("type", "web_fetch_20260209", "name", "web_fetch"));

    /** Marqueur de cache posé sur le dernier bloc d'un segment stable (F-39 / SF-39-01). */
    private static final Map<String, Object> CACHE_CONTROL = Map.of("type", "ephemeral");
    /** Stratégie d'édition de contexte du fournisseur (F-39 / SF-39-12). */
    private static final String CLEAR_TOOL_USES_EDIT = "clear_tool_uses_20250919";
    /** En-tête beta exigé par l'édition de contexte (F-39 / SF-39-12). */
    private static final String CONTEXT_MANAGEMENT_BETA = "context-management-2025-06-27";
    /** Lecture des événements SSE et reconstruction de la réponse (F-116 / SF-116-01). */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Attente d'un réessai. Séparée pour que les tests vérifient la <b>règle</b> sans dormir
     * réellement — le seul point de la boucle qui dépend du temps qui passe.
     */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final AnthropicProperties properties;
    private final RestClient restClient;
    private final AgentRetryPolicy retryPolicy;
    private final Sleeper sleeper;

    /** Constructeur d'injection — désigné explicitement, la classe en ayant deux. */
    @Autowired
    public AnthropicAgentProvider(AnthropicProperties properties, RestClient.Builder restClientBuilder) {
        this(properties, restClientBuilder, requestFactory(properties), Thread::sleep);
    }

    /**
     * Variante de construction destinée aux tests.
     *
     * @param requestFactory fabrique HTTP à poser sur le client ; {@code null} conserve celle déjà
     *                       présente sur le {@code builder} — c'est ce qui permet à un serveur
     *                       simulé de rester en place
     * @param sleeper        attente d'un réessai
     */
    AnthropicAgentProvider(AnthropicProperties properties, RestClient.Builder restClientBuilder,
            ClientHttpRequestFactory requestFactory, Sleeper sleeper) {
        this.properties = properties;
        RestClient.Builder builder = restClientBuilder.baseUrl(properties.baseUrl());
        if (requestFactory != null) {
            builder = builder.requestFactory(requestFactory);
        }
        this.restClient = builder.build();
        this.retryPolicy = new AgentRetryPolicy(properties.agentMaxAttempts());
        this.sleeper = sleeper;
    }

    /**
     * Délais de connexion et de lecture de la boucle d'agent (décision D-L6-1). Jusqu'à cette
     * subfeature, aucun délai n'était posé : un appel sans réponse bloquait le thread de la boucle
     * indéfiniment, le budget de tour n'étant vérifié qu'<b>entre</b> deux itérations.
     */
    private static ClientHttpRequestFactory requestFactory(AnthropicProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int millis = (int) Math.min(Integer.MAX_VALUE, properties.agentTimeout().toMillis());
        factory.setConnectTimeout(millis);
        factory.setReadTimeout(millis);
        return factory;
    }

    @Override
    public AgentTurn nextTurn(AgentTurnRequest request) {
        Prepared prepared = prepare(request);
        return callWithRetry(request.model(), prepared.contextEditing(),
                () -> toTurn(sendNonStreamed(prepared)));
    }

    /**
     * Variante <b>streamée</b> (F-116 / SF-116-01) : consomme le flux SSE d'Anthropic
     * ({@code stream:true}), pousse les deltas de <b>texte</b> dans {@code textListener} au fil de
     * l'eau, puis reconstitue <b>le même</b> {@link AgentTurn} qu'un appel non streamé — via le même
     * {@link #toTurn(JsonNode)}, donc avec un comptage cache/usage et un raisonnement signé identiques.
     *
     * <p>Le corps est <b>celui du chemin non streamé</b> augmenté du seul {@code stream:true} : les
     * marqueurs {@code cache_control}, le raisonnement et le retry {@code 429/529} ne bougent pas. Un
     * refus du flux ou une coupure fait <b>replier</b> sur l'appel complet (décision D5) : le tour ne
     * meurt pas parce que le fournisseur n'a pas su streamer.</p>
     */
    @Override
    public AgentTurn nextTurn(AgentTurnRequest request, AgentTextListener textListener) {
        Prepared prepared = prepare(request);
        AgentTextListener sink = textListener == null ? AgentTextListener.NONE : textListener;
        // Corps identique + `stream:true` : le préfixe caché et le retry restent ceux du non streamé.
        Map<String, Object> streamBody = new HashMap<>(prepared.body());
        streamBody.put("stream", true);
        try {
            return callWithRetry(request.model(), prepared.contextEditing(),
                    () -> streamTurn(prepared.apiKey(), streamBody, prepared.contextEditing(), sink));
        } catch (StreamingFallbackException ex) {
            // Le fournisseur a refusé le flux, l'a coupé, ou l'a rendu illisible : on retombe
            // proprement sur l'appel complet. Un refus temporaire (429/529) épuisé, lui, n'atterrit
            // pas ici — il échoue comme un non streamé l'aurait fait, sans second appel inutile.
            log.warn("Streaming indisponible pour le tour (modèle={}, {}) : repli non streamé.",
                    request.model(), ex.getMessage());
            return callWithRetry(request.model(), prepared.contextEditing(),
                    () -> toTurn(sendNonStreamed(prepared)));
        }
    }

    /** Corps de requête prêt à partir, calculé <b>une fois</b> et partagé streamé/non streamé. */
    private record Prepared(String apiKey, Map<String, Object> body, boolean contextEditing) {
    }

    /**
     * Construit le corps de requête (décision D-L6-6) : identique quel que soit le mode d'appel, pour
     * que le streaming n'altère ni le cache, ni le raisonnement signé, ni le retry.
     */
    private Prepared prepare(AgentTurnRequest request) {
        String apiKey = resolveApiKey(request.apiKey());
        if (apiKey == null || apiKey.isBlank()) {
            throw new AIProviderUnavailableException("Le fournisseur IA n'est pas configuré.");
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", request.model());
        body.put("max_tokens", properties.agentMaxTokens());
        body.put("messages", toApiMessages(request.messages()));
        if (StringUtils.hasText(request.system())) {
            // Forme « liste de blocs » : seule forme qui accepte un `cache_control`. Le contenu est
            // inchangé. L'ordre de rendu du fournisseur étant tools -> system -> messages, ce
            // marqueur couvre AUSSI les définitions d'outils qui précèdent (SF-39-01, D1).
            body.put("system", List.of(cached(Map.of("type", "text", "text", request.system()))));
        }
        if (request.tools() != null && !request.tools().isEmpty()) {
            body.put("tools", toApiTools(request.tools()));
        }
        applyReasoning(body, request.reasoning());
        boolean contextEditing = applyContextPolicy(body, request.contextPolicy());
        return new Prepared(apiKey, body, contextEditing);
    }

    /**
     * Édition de contexte du tour (F-39 / SF-39-12) : demande au fournisseur d'écarter les résultats
     * d'outils périmés quand le contexte d'entrée dépasse le seuil.
     *
     * <p>C'est ici, et <b>nulle part ailleurs</b>, que le nom du mécanisme est écrit : le domaine
     * exprime une intention ({@link AgentContextPolicy}), pas une option d'API (décision D-L6-10).</p>
     *
     * <p>{@code clear_tool_inputs} n'est volontairement pas demandé (décision D-L6-8) : savoir qu'on
     * a lancé une commande il y a vingt itérations tient en quelques tokens et évite de la relancer ;
     * c'est sa <b>sortie</b> qui pèse.</p>
     *
     * @return vrai si l'appel doit porter l'en-tête beta correspondant
     */
    private boolean applyContextPolicy(Map<String, Object> body, AgentContextPolicy policy) {
        if (policy == null || !policy.pruneToolResults()) {
            return false;
        }
        body.put("context_management", Map.of("edits", List.of(Map.of(
                "type", CLEAR_TOOL_USES_EDIT,
                "trigger", Map.of("type", "input_tokens", "value", policy.triggerInputTokens()),
                "keep", Map.of("type", "tool_uses", "value", policy.keepRecentToolResults()),
                "clear_at_least",
                Map.of("type", "input_tokens", "value", policy.clearAtLeastInputTokens())))));
        return true;
    }

    /**
     * Appelle le fournisseur, en rejouant le <b>même</b> corps sur un refus temporaire
     * (F-39 / SF-39-11).
     *
     * <p>Le corps est calculé une fois par l'appelant (décision D-L6-6) : marqueurs
     * {@code cache_control} et blocs de raisonnement signés sont donc strictement identiques d'une
     * tentative à l'autre. Le reconstruire ferait glisser le marqueur de cache et transformerait un
     * réessai en écriture de cache payée deux fois.</p>
     *
     * <p>Un dépassement de délai n'est <b>pas</b> rejoué (décision D-L6-3) : l'appel a déjà consommé
     * une part du budget de tour, et le rejouer échangerait un échec lisible contre un tour qui
     * meurt au budget — ce que l'utilisateur lit comme une panne.</p>
     */
    private AgentTurn callWithRetry(String model, boolean contextEditing, ApiCall apiCall) {
        long waited = 0L;
        for (int attempt = 1; ; attempt++) {
            try {
                return apiCall.execute();
            } catch (RestClientResponseException ex) {
                int status = ex.getStatusCode().value();
                long delay = AgentRetryPolicy.retryableStatus(status) && retryPolicy.hasAttemptLeft(attempt)
                        ? retryPolicy.delayMs(attempt, retryAfterHeader(ex), waited)
                        : AgentRetryPolicy.NO_DELAY;
                if (delay == AgentRetryPolicy.NO_DELAY) {
                    throw providerFailure(model, ex);
                }
                // Ni la clé ni le corps de la réponse : seulement de quoi comprendre l'attente.
                log.warn("Fournisseur IA temporairement indisponible (statut={}, modèle={}), "
                        + "nouvelle tentative dans {} ms (tentative {}).", status, model, delay, attempt);
                sleep(delay);
                waited += delay;
            } catch (RestClientException ex) {
                throw providerFailure(model, ex);
            }
        }
    }

    /**
     * Une tentative d'appel. Le corps est calculé une fois par l'appelant ; un réessai rejoue cette
     * fonction, il ne la reconstruit pas (décision D-L6-6). Un {@link StreamingFallbackException} qui
     * en sort n'est <b>pas</b> un refus temporaire : il traverse la boucle de retry et déclenche le
     * repli non streamé (SF-116-01).
     */
    @FunctionalInterface
    private interface ApiCall {
        AgentTurn execute() throws RestClientException;
    }

    /**
     * Le fournisseur n'a pas su streamer (refus du flux, coupure, SSE illisible). Distinct d'un refus
     * temporaire {@code 429/529} : il n'est ni rejoué par la boucle de retry, ni transformé en échec
     * du tour — il fait <b>replier</b> sur l'appel complet (F-116 / SF-116-01).
     */
    private static final class StreamingFallbackException extends RuntimeException {
        StreamingFallbackException(String message) {
            super(message);
        }
    }

    /** En-tête commun aux deux modes d'appel — même URL, même version, même beta d'édition. */
    private RestClient.RequestBodySpec requestSpec(String apiKey, boolean contextEditing) {
        RestClient.RequestBodySpec spec = restClient.post()
                .uri("/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", properties.version())
                .contentType(MediaType.APPLICATION_JSON);
        if (contextEditing) {
            spec = spec.header("anthropic-beta", CONTEXT_MANAGEMENT_BETA);
        }
        return spec;
    }

    /** Appel complet (non streamé) : la réponse entière du fournisseur en une fois. */
    private JsonNode sendNonStreamed(Prepared prepared) {
        return requestSpec(prepared.apiKey(), prepared.contextEditing())
                .body(prepared.body())
                .retrieve()
                .body(JsonNode.class);
    }

    /**
     * Appel <b>streamé</b> : consomme le flux SSE et reconstitue l'objet réponse à l'identique.
     *
     * <p>Un refus temporaire {@code 429/529} remonte en {@link RestClientResponseException} pour être
     * rejoué par {@link #callWithRetry} exactement comme le non streamé. Tout autre échec du flux
     * (statut d'erreur, coupure, SSE illisible) remonte en {@link StreamingFallbackException} pour
     * déclencher le repli.</p>
     */
    private AgentTurn streamTurn(String apiKey, Map<String, Object> streamBody, boolean contextEditing,
            AgentTextListener textListener) {
        return requestSpec(apiKey, contextEditing)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body(streamBody)
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.isError()) {
                        // 429/529 : rejoués par la boucle de retry, avec l'en-tête Retry-After.
                        if (AgentRetryPolicy.retryableStatus(status.value())) {
                            throw new RestClientResponseException("Refus temporaire du flux",
                                    status, status.toString(), response.getHeaders(),
                                    readErrorBody(response.getBody()), StandardCharsets.UTF_8);
                        }
                        // Refus permanent du flux : on replie plutôt que de tuer le tour.
                        throw new StreamingFallbackException("statut " + status.value());
                    }
                    return toTurn(parseSse(response.getBody(), textListener));
                });
    }

    /** Lit au mieux le corps d'erreur pour l'en-tête de retry ; jamais bloquant. */
    private static byte[] readErrorBody(InputStream body) {
        try (InputStream in = body) {
            return in == null ? new byte[0] : in.readAllBytes();
        } catch (IOException ex) {
            return new byte[0];
        }
    }

    /**
     * Reconstitue l'objet réponse d'Anthropic à partir de son flux SSE (F-116 / SF-116-01) et pousse
     * les deltas de <b>texte</b> dans {@code textListener} au fil de l'eau.
     *
     * <p>Les blocs sont assemblés dans l'ordre d'index : {@code text} concatène ses
     * {@code text_delta} ; {@code tool_use} concatène ses {@code input_json_delta} puis parse le JSON
     * accumulé ; {@code thinking} concatène ses {@code thinking_delta} et pose sa {@code signature}.
     * L'{@code usage} vient de {@code message_start} (entrée + cache) et de {@code message_delta}
     * (sortie finale) ; le {@code stop_reason} de {@code message_delta}. Le résultat a exactement la
     * forme qu'attend {@link #toTurn(JsonNode)} : l'équivalence avec le non streamé est mécanique.</p>
     *
     * <p>Le raisonnement <b>n'est jamais</b> poussé dans le sink : il n'est pas la réponse
     * (SF-39-10).</p>
     */
    private JsonNode parseSse(InputStream body, AgentTextListener textListener) {
        if (body == null) {
            throw new StreamingFallbackException("flux vide");
        }
        ParseState state = new ParseState(textListener);
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    // Ligne vide = fin d'un événement : on traite la donnée accumulée.
                    if (handleEvent(data, state)) {
                        break;
                    }
                } else if (line.startsWith("data:")) {
                    // Une donnée peut être répartie sur plusieurs lignes `data:` (concaténées).
                    data.append(line.substring(5).stripLeading());
                }
                // Les lignes `event:`/`id:`/`:comment` sont ignorées : le type vit dans la donnée.
            }
            // Flux clos sans ligne vide finale : la dernière donnée reste à traiter (robustesse).
            if (!state.completed) {
                handleEvent(data, state);
            }
        } catch (IOException ex) {
            throw new StreamingFallbackException("flux coupé");
        }
        if (!state.completed) {
            // Flux clos avant `message_stop` : réponse incomplète, on replie.
            throw new StreamingFallbackException("flux clos avant la fin");
        }
        return state.toMessage();
    }

    /**
     * Traite la donnée accumulée d'un événement SSE ; vide {@code data}. Retourne {@code true} sur
     * {@code message_stop} (fin du flux).
     */
    private boolean handleEvent(StringBuilder data, ParseState state) {
        if (data.length() == 0) {
            return false;
        }
        JsonNode event = readEvent(data.toString());
        data.setLength(0);
        if (event == null) {
            return false;
        }
        String type = event.path("type").asText("");
        if ("error".equals(type)) {
            throw new StreamingFallbackException("événement error");
        }
        if ("message_stop".equals(type)) {
            state.completed = true;
            return true;
        }
        state.apply(event, type);
        return false;
    }

    /**
     * État de reconstruction d'un flux SSE (F-116 / SF-116-01) : blocs assemblés par index, JSON
     * d'entrée d'outils accumulé, {@code usage}, {@code stop_reason} et éditions de contexte. Assemble
     * un objet réponse ayant exactement la forme qu'attend {@link #toTurn(JsonNode)}.
     */
    private static final class ParseState {

        private final List<ObjectNode> blocks = new ArrayList<>();
        private final List<StringBuilder> toolInputJson = new ArrayList<>();
        private final ObjectNode usage = MAPPER.createObjectNode();
        private final AgentTextListener textListener;
        private ObjectNode contextManagement;
        private String stopReason;
        private boolean completed;

        ParseState(AgentTextListener textListener) {
            this.textListener = textListener;
        }

        void apply(JsonNode event, String type) {
            switch (type) {
                case "message_start" -> {
                    copyUsage(event.path("message").path("usage"), usage, true);
                    JsonNode ctx = event.path("message").path("context_management");
                    if (ctx.isObject()) {
                        contextManagement = ctx.deepCopy();
                    }
                }
                case "content_block_start" -> {
                    JsonNode block = event.path("content_block");
                    blocks.add(block.isObject() ? block.deepCopy() : MAPPER.createObjectNode());
                    toolInputJson.add(new StringBuilder());
                }
                case "content_block_delta" -> applyDelta(event);
                case "message_delta" -> {
                    String sr = event.path("delta").path("stop_reason").asText(null);
                    if (sr != null && !sr.isEmpty()) {
                        stopReason = sr;
                    }
                    // La sortie finale (cumulée) vit ici ; l'entrée et le cache restent ceux du start.
                    copyUsage(event.path("usage"), usage, false);
                    JsonNode ctx = event.path("context_management");
                    if (ctx.isObject()) {
                        contextManagement = ctx.deepCopy();
                    }
                }
                default -> {
                    // `content_block_stop`, `ping`, types inconnus : rien à reconstituer.
                }
            }
        }

        /** Applique un {@code content_block_delta} au bloc concerné, en poussant le texte au fil de l'eau. */
        private void applyDelta(JsonNode event) {
            int index = event.path("index").asInt(-1);
            if (index < 0 || index >= blocks.size()) {
                return;
            }
            ObjectNode block = blocks.get(index);
            JsonNode delta = event.path("delta");
            switch (delta.path("type").asText("")) {
                case "text_delta" -> {
                    String piece = delta.path("text").asText("");
                    block.put("text", block.path("text").asText("") + piece);
                    // Le SEUL point où le texte part vers l'écran au fil de l'eau (F-116).
                    if (!piece.isEmpty()) {
                        textListener.onTextDelta(piece);
                    }
                }
                case "input_json_delta" ->
                        toolInputJson.get(index).append(delta.path("partial_json").asText(""));
                case "thinking_delta" ->
                        // Le raisonnement se reconstitue, mais ne rejoint JAMAIS le texte de la réponse.
                        block.put("thinking",
                                block.path("thinking").asText("") + delta.path("thinking").asText(""));
                case "signature_delta" ->
                        block.put("signature",
                                block.path("signature").asText("") + delta.path("signature").asText(""));
                default -> {
                    // Type de delta inconnu : ignoré, la reconstitution reste valide.
                }
            }
        }

        /** Assemble l'objet réponse équivalent à un appel non streamé. */
        JsonNode toMessage() {
            // Chaque bloc `tool_use` reçoit son `input` reconstitué depuis le JSON accumulé.
            for (int i = 0; i < blocks.size(); i++) {
                ObjectNode block = blocks.get(i);
                if ("tool_use".equals(block.path("type").asText(""))) {
                    block.set("input", parseToolInput(toolInputJson.get(i)));
                }
            }
            ObjectNode message = MAPPER.createObjectNode();
            ArrayNode content = message.putArray("content");
            blocks.forEach(content::add);
            message.put("stop_reason", stopReason == null ? "" : stopReason);
            message.set("usage", usage);
            if (contextManagement != null) {
                message.set("context_management", contextManagement);
            }
            return message;
        }
    }

    /**
     * Reporte l'{@code usage} du flux dans l'accumulateur. Depuis {@code message_start} on prend
     * l'entrée et le cache ; depuis {@code message_delta} la sortie finale. Ne jamais écraser une
     * valeur d'entrée déjà posée par une valeur absente : le compteur de quota doit rester identique
     * au non streamé (SF-39-01).
     */
    private static void copyUsage(JsonNode source, ObjectNode target, boolean input) {
        if (source == null || !source.isObject()) {
            return;
        }
        if (input) {
            copyIntField(source, target, "input_tokens");
            copyIntField(source, target, "cache_creation_input_tokens");
            copyIntField(source, target, "cache_read_input_tokens");
        }
        // La sortie est portée par les deux événements ; celle de `message_delta` (cumulée) prime.
        copyIntField(source, target, "output_tokens");
    }

    private static void copyIntField(JsonNode source, ObjectNode target, String field) {
        if (source.hasNonNull(field)) {
            target.put(field, source.path(field).asInt(0));
        }
    }

    /** Lit un événement SSE (une donnée JSON) ; une donnée illisible est ignorée, pas fatale. */
    private static JsonNode readEvent(String data) {
        try {
            return MAPPER.readTree(data);
        } catch (IOException ex) {
            return null;
        }
    }

    /** Parse le JSON d'entrée d'un {@code tool_use} accumulé ; vide ou illisible ⇒ objet vide. */
    private static JsonNode parseToolInput(StringBuilder json) {
        if (json == null || json.length() == 0) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode parsed = MAPPER.readTree(json.toString());
            return parsed == null || parsed.isMissingNode() ? MAPPER.createObjectNode() : parsed;
        } catch (IOException ex) {
            // Un `input` illisible rend le tour inexploitable : on replie sur l'appel complet.
            throw new StreamingFallbackException("input d'outil illisible");
        }
    }

    /** Message neutre : ni la clé ni la réponse brute du fournisseur ne remontent au client. */
    private AIProviderException providerFailure(String model, RestClientException ex) {
        log.warn("Appel agent au fournisseur IA en échec (modèle={})", model);
        return new AIProviderException("Échec de l'appel au fournisseur IA.", ex);
    }

    /** Première valeur de l'en-tête {@code Retry-After}, ou {@code null}. */
    private static String retryAfterHeader(RestClientResponseException ex) {
        HttpHeaders headers = ex.getResponseHeaders();
        return headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
    }

    /**
     * Attente d'un réessai. Une interruption n'est pas avalée : le flag est reposé pour que le
     * thread de la boucle reste interruptible, et l'échec remonte tout de suite.
     */
    private void sleep(long millis) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AIProviderException("Appel au fournisseur IA interrompu.", ex);
        }
    }

    /**
     * Réglage de raisonnement du tour (F-39 / SF-39-10).
     *
     * <p><b>Adaptatif écrit explicitement</b> (décision D-L5-2) : sur le modèle cible, omettre
     * {@code thinking} revient au même — mais sur le modèle précédent, l'omission veut dire
     * <b>aucun raisonnement</b>. Un paramètre dont le sens dépend du modèle n'en est pas un.</p>
     *
     * <p>L'effort vit dans {@code output_config}, pas à la racine de la requête.</p>
     */
    private void applyReasoning(Map<String, Object> body, AgentReasoning reasoning) {
        if (reasoning == null) {
            return;
        }
        if (reasoning.adaptive()) {
            body.put("thinking", Map.of("type", "adaptive"));
        }
        if (StringUtils.hasText(reasoning.effort())) {
            body.put("output_config", Map.of("effort", reasoning.effort()));
        }
    }

    private List<Map<String, Object>> toApiTools(List<AgentTool> tools) {
        List<Map<String, Object>> apiTools = new ArrayList<>(tools.size() + 2);
        for (AgentTool tool : tools) {
            apiTools.add(Map.of(
                    "name", tool.name(),
                    "description", tool.description(),
                    "input_schema", tool.inputSchema()));
        }
        apiTools.addAll(SERVER_TOOLS);
        return apiTools;
    }

    /**
     * Traduit l'historique, en posant un marqueur de cache sur le <b>dernier bloc du dernier
     * message</b> (F-39 / SF-39-01).
     *
     * <p>Le marqueur glisse d'une itération à l'autre : celle qui l'écrit paie l'écriture une fois,
     * toutes les suivantes relisent le segment à une fraction du prix (D2). Sur un tour de 30
     * itérations, chaque segment est écrit une fois et relu jusqu'à 29 fois.</p>
     */
    private List<Map<String, Object>> toApiMessages(List<AgentMessage> messages) {
        List<Map<String, Object>> apiMessages = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            AgentMessage message = messages.get(i);
            boolean last = i == messages.size() - 1;
            List<Map<String, Object>> blocks = new ArrayList<>(message.content().size());
            for (int j = 0; j < message.content().size(); j++) {
                Map<String, Object> block = toApiBlock(message.content().get(j));
                if (last && j == message.content().size() - 1) {
                    block = cached(block);
                }
                blocks.add(block);
            }
            apiMessages.add(Map.of("role", message.role(), "content", blocks));
        }
        return apiMessages;
    }

    /** Copie d'un bloc portant le marqueur de cache. Le bloc d'origine reste immuable. */
    private static Map<String, Object> cached(Map<String, Object> block) {
        Map<String, Object> marked = new HashMap<>(block);
        marked.put("cache_control", CACHE_CONTROL);
        return marked;
    }

    private Map<String, Object> toApiBlock(AgentContentBlock block) {
        return switch (block) {
            case AgentContentBlock.Text text -> Map.of("type", "text", "text", text.text());
            case AgentContentBlock.ToolUse use -> Map.of(
                    "type", "tool_use", "id", use.id(), "name", use.name(), "input", use.input());
            // Réémis tels quels (SF-39-10) : la signature n'est vérifiable que par le fournisseur,
            // et un bloc de raisonnement retouché est un bloc refusé.
            case AgentContentBlock.Reasoning reasoning -> {
                Map<String, Object> map = new HashMap<>();
                map.put("type", "thinking");
                map.put("thinking", reasoning.text() == null ? "" : reasoning.text());
                if (StringUtils.hasText(reasoning.signature())) {
                    map.put("signature", reasoning.signature());
                }
                yield map;
            }
            case AgentContentBlock.RedactedReasoning redacted -> Map.of(
                    "type", "redacted_thinking", "data", redacted.data() == null ? "" : redacted.data());
            case AgentContentBlock.ToolResult result -> {
                Map<String, Object> map = new HashMap<>();
                map.put("type", "tool_result");
                map.put("tool_use_id", result.toolUseId());
                map.put("content", result.content());
                if (result.isError()) {
                    map.put("is_error", true);
                }
                yield map;
            }
        };
    }

    private AgentTurn toTurn(JsonNode response) {
        if (response == null || !response.hasNonNull("content")) {
            throw new AIProviderException("Réponse vide du fournisseur IA.");
        }
        StringBuilder text = new StringBuilder();
        List<AgentToolCall> toolCalls = new ArrayList<>();
        List<AgentContentBlock> reasoning = new ArrayList<>();
        for (JsonNode block : response.get("content")) {
            String type = block.path("type").asText("");
            if ("text".equals(type)) {
                text.append(block.path("text").asText(""));
            } else if ("thinking".equals(type)) {
                // Le raisonnement n'est PAS la réponse : il ne rejoint jamais `text`. Il est conservé
                // pour être rejoué au tour suivant, signature comprise (SF-39-10, décision D-L5-3).
                String signature = block.path("signature").asText("");
                reasoning.add(new AgentContentBlock.Reasoning(block.path("thinking").asText(""),
                        signature.isEmpty() ? null : signature));
            } else if ("redacted_thinking".equals(type)) {
                reasoning.add(new AgentContentBlock.RedactedReasoning(block.path("data").asText("")));
            } else if ("tool_use".equals(type)) {
                JsonNode input = block.path("input");
                toolCalls.add(new AgentToolCall(
                        block.path("id").asText(""),
                        block.path("name").asText(""),
                        input.isMissingNode() ? MissingNode.getInstance() : input));
            }
        }
        String stopReason = response.path("stop_reason").asText("");
        boolean finished = !"tool_use".equals(stopReason);
        // « Coupé au plafond » n'est pas « terminé » (SF-28-18) : le fournisseur n'attend plus rien de
        // nous, mais sa réponse s'arrête au milieu — souvent avant même le bloc `tool_use` annoncé par
        // la phrase qui précède. La distinguer ici est le seul endroit où l'information existe.
        boolean truncated = "max_tokens".equals(stopReason);
        JsonNode usage = response.path("usage");
        // Les tokens servis par le cache ne sont PAS dans `input_tokens` (SF-39-01, D3). Ne compter
        // que ce champ ferait chuter le VOLUME traité d'environ 90 % sans que rien ne le signale.
        // Ils sont donc additionnés ici — et, depuis F-63 / SF-63-02, portés AUSSI séparément : le
        // volume reste le volume, mais le décompte les facture à leur prix (un dixième du tarif
        // d'entrée en lecture, 1,25× en écriture) au lieu du plein tarif.
        int cacheCreation = usage.path("cache_creation_input_tokens").asInt(0);
        int cacheRead = usage.path("cache_read_input_tokens").asInt(0);
        int inputTokens = usage.path("input_tokens").asInt(0) + cacheCreation + cacheRead;
        int outputTokens = usage.path("output_tokens").asInt(0);
        // Un cache qui ne prend pas ne lève aucune erreur : seuls ces compteurs le disent.
        log.debug("Tour d'agent : {} tokens d'entrée (dont {} écrits en cache, {} lus en cache).",
                inputTokens, cacheCreation, cacheRead);
        logAppliedContextEdits(response);
        return new AgentTurn(text.toString(), toolCalls, finished, inputTokens, outputTokens, truncated,
                reasoning, cacheRead, cacheCreation);
    }

    /**
     * Journalise les éditions de contexte réellement appliquées par le fournisseur
     * (F-39 / SF-39-12). Comme pour le cache, une édition ne lève aucune erreur : sans ces
     * compteurs, rien ne dirait si le mécanisme a servi, ni ce qu'il a fait gagner.
     */
    private void logAppliedContextEdits(JsonNode response) {
        if (!log.isDebugEnabled()) {
            return;
        }
        for (JsonNode edit : response.path("context_management").path("applied_edits")) {
            // Rendue telle quelle : un type inconnu d'une version future se lit encore.
            log.debug("Édition de contexte appliquée : {}", edit);
        }
    }

    /** Clé BYOK fournie pour l'appel, sinon clé plateforme. Jamais journalisée. */
    private String resolveApiKey(String overrideApiKey) {
        if (StringUtils.hasText(overrideApiKey)) {
            return overrideApiKey;
        }
        return properties.apiKey();
    }
}
