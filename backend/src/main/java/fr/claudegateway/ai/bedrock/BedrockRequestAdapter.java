package fr.claudegateway.ai.bedrock;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.ai.AIProviderUnavailableException;

/**
 * Adaptateur d'enveloppe du canal Bedrock (F-277 / SF-277-01, cadrage D2).
 *
 * <p>Posé comme intercepteur sur le client HTTP d'une <b>seconde instance</b> des fournisseurs Anthropic, il
 * reçoit la requête {@code POST /v1/messages} telle qu'Anthropic l'aurait reçue et la récrit pour
 * {@code InvokeModel} / {@code InvokeModelWithResponseStream} : profil d'inférence dans l'URL, corps nettoyé des
 * capacités que Bedrock refuse, bêtas dans le corps, signature SigV4. En retour, il transcrit le flux
 * event-stream en SSE et les erreurs AWS en erreurs de forme Anthropic. Tout le reste — sérialisation, lecture,
 * relances, repli non streamé — est celui du canal Anthropic, réutilisé tel quel.</p>
 */
public final class BedrockRequestAdapter implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(BedrockRequestAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Version d'API exigée par Bedrock dans le corps. */
    static final String BEDROCK_VERSION = "bedrock-2023-05-31";
    /** Champs de la racine refusés par Bedrock (« Extra inputs are not permitted »), ou propres à l'API directe. */
    private static final Set<String> DROPPED_FIELDS = Set.of("model", "stream", "diagnostics", "fallbacks");
    /** Outils exécutés chez Anthropic : absents de Bedrock (« not supported for this model »). */
    private static final List<String> SERVER_TOOL_PREFIXES = List.of("web_search_", "web_fetch_",
            "code_execution_");
    /** Texte posé à la place d'une pièce jointe Files API (cadrage D4). */
    static final String ATTACHMENT_UNAVAILABLE =
            "[Pièce jointe indisponible sur le canal de secours Bedrock : le fichier n'a pas pu être transmis.]";
    /** Texte posé dans un message dont il ne restait que des blocs de recherche web (cadrage D3). */
    static final String SERVER_BLOCKS_OMITTED = "(résultats de recherche web omis sur le canal de secours)";

    private static final long SERVER_TOOLS_LOG_PERIOD_MS = 10 * 60_000L;

    private final BedrockProperties properties;
    private final BedrockSigner signer;
    private final java.util.concurrent.atomic.AtomicLong lastServerToolsLog =
            new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE / 2);

    public BedrockRequestAdapter(BedrockProperties properties, BedrockSigner signer) {
        this.properties = properties;
        this.signer = signer;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String path = request.getURI().getPath();
        if (request.getMethod() != HttpMethod.POST || path == null || !path.endsWith("/v1/messages")) {
            // Files API, modèles… : rien de tel sur Bedrock. Refus propre, aucun appel émis.
            throw new AIProviderUnavailableException("Opération indisponible sur le canal Bedrock.");
        }
        JsonNode parsed = MAPPER.readTree(body);
        if (!(parsed instanceof ObjectNode json)) {
            throw new AIProviderUnavailableException("Corps de requête illisible pour le canal Bedrock.");
        }
        String model = json.path("model").asText("");
        boolean stream = json.path("stream").asBoolean(false);
        byte[] bedrockBody = MAPPER.writeValueAsBytes(clean(json, betas(request.getHeaders())));

        URI uri = URI.create(properties.baseUrl() + "/model/"
                + URLEncoder.encode(properties.profileFor(model), StandardCharsets.UTF_8)
                + (stream ? "/invoke-with-response-stream" : "/invoke"));
        Map<String, List<String>> toSign = new LinkedHashMap<>();
        toSign.put("Content-Type", List.of(MediaType.APPLICATION_JSON_VALUE));
        toSign.put("Accept", List.of(stream ? "application/vnd.amazon.eventstream" : MediaType.APPLICATION_JSON_VALUE));
        Map<String, List<String>> signed = signer.sign(uri, toSign, bedrockBody);

        HttpHeaders headers = new HttpHeaders();
        signed.forEach((name, values) -> {
            if (!"host".equalsIgnoreCase(name) && !"content-length".equalsIgnoreCase(name)) {
                headers.put(name, values);
            }
        });
        toSign.forEach(headers::putIfAbsent);
        headers.setContentLength(bedrockBody.length);
        ClientHttpResponse response = execution.execute(new RewrittenRequest(uri, headers), bedrockBody);
        return new BedrockResponse(response, stream);
    }

    /** Bêtas de l'en-tête {@code anthropic-beta}, filtrées par la liste autorisée. */
    private List<String> betas(HttpHeaders headers) {
        List<String> kept = new ArrayList<>();
        List<String> values = headers.get("anthropic-beta");
        if (values == null) {
            return kept;
        }
        for (String value : values) {
            for (String beta : value.split(",")) {
                String b = beta.strip();
                if (b.isEmpty() || kept.contains(b)) {
                    continue;
                }
                if (properties.allowedBetas().contains(b)) {
                    kept.add(b);
                } else {
                    log.debug("Bêta non transmise au canal Bedrock : {}", b);
                }
            }
        }
        return kept;
    }

    /**
     * Le corps Anthropic, rendu acceptable par Bedrock. Visible des tests : c'est là que se décide ce que le
     * canal sait faire.
     */
    ObjectNode clean(ObjectNode body, List<String> betas) {
        ObjectNode out = body.deepCopy();
        DROPPED_FIELDS.forEach(out::remove);
        if (out.get("thinking") instanceof ObjectNode thinking) {
            thinking.remove("block_binding");
        }
        int removedTools = cleanTools(out);
        int removedBlocks = 0;
        if (out.get("messages") instanceof ArrayNode messages) {
            for (JsonNode message : messages) {
                if (message instanceof ObjectNode m && m.get("content") instanceof ArrayNode content) {
                    removedBlocks += cleanContent(m, content);
                }
            }
        }
        if ((removedTools > 0 || removedBlocks > 0) && shouldLogServerTools()) {
            log.info("Recherche web non proposée sur le canal Bedrock (outils serveur retirés={}, blocs web "
                    + "rejoués retirés={}).", removedTools, removedBlocks);
        }
        out.put("anthropic_version", BEDROCK_VERSION);
        if (!betas.isEmpty()) {
            ArrayNode array = out.putArray("anthropic_beta");
            betas.forEach(array::add);
        }
        return out;
    }

    /** Une note par période : chaque appel de la boucle la répéterait sinon. */
    private boolean shouldLogServerTools() {
        long now = System.currentTimeMillis();
        long last = lastServerToolsLog.get();
        return now - last >= SERVER_TOOLS_LOG_PERIOD_MS && lastServerToolsLog.compareAndSet(last, now);
    }

    /** Retire les outils serveur ; garde le marqueur de cache sur le nouveau dernier outil. */
    private static int cleanTools(ObjectNode body) {
        if (!(body.get("tools") instanceof ArrayNode tools)) {
            return 0;
        }
        int removed = 0;
        JsonNode cacheControl = null;
        for (Iterator<JsonNode> it = tools.elements(); it.hasNext();) {
            JsonNode tool = it.next();
            String type = tool.path("type").asText("");
            if (SERVER_TOOL_PREFIXES.stream().anyMatch(type::startsWith)) {
                if (tool.has("cache_control")) {
                    cacheControl = tool.get("cache_control");
                }
                it.remove();
                removed++;
            }
        }
        if (tools.isEmpty()) {
            body.remove("tools");
            body.remove("tool_choice");
        } else if (cacheControl != null && tools.get(tools.size() - 1) instanceof ObjectNode last
                && !last.has("cache_control")) {
            last.set("cache_control", cacheControl);
        }
        return removed;
    }

    /** Retire les blocs serveur rejoués et remplace les sources Files API / URL ; rend le nombre de blocs retirés. */
    private static int cleanContent(ObjectNode message, ArrayNode content) {
        int removed = 0;
        for (int i = content.size() - 1; i >= 0; i--) {
            JsonNode block = content.get(i);
            String type = block.path("type").asText("");
            if (isServerBlock(type)) {
                content.remove(i);
                removed++;
                continue;
            }
            if (block instanceof ObjectNode b) {
                b.remove("citations");
                if (unsupportedSource(b)) {
                    content.set(i, textBlock(ATTACHMENT_UNAVAILABLE, b.get("cache_control")));
                } else if ("tool_result".equals(type) && b.get("content") instanceof ArrayNode inner) {
                    for (int j = 0; j < inner.size(); j++) {
                        if (inner.get(j) instanceof ObjectNode ib && unsupportedSource(ib)) {
                            inner.set(j, textBlock(ATTACHMENT_UNAVAILABLE, ib.get("cache_control")));
                        }
                    }
                }
            }
        }
        if (content.isEmpty()) {
            content.add(textBlock(SERVER_BLOCKS_OMITTED, null));
        }
        return removed;
    }

    private static boolean isServerBlock(String type) {
        return "server_tool_use".equals(type) || (type.endsWith("_tool_result") && !"tool_result".equals(type));
    }

    private static boolean unsupportedSource(ObjectNode block) {
        String source = block.path("source").path("type").asText("");
        return "file".equals(source) || "url".equals(source);
    }

    private static ObjectNode textBlock(String text, JsonNode cacheControl) {
        ObjectNode block = MAPPER.createObjectNode();
        block.put("type", "text");
        block.put("text", text);
        if (cacheControl != null) {
            block.set("cache_control", cacheControl);
        }
        return block;
    }

    /** Requête récrite : URL Bedrock et en-têtes signés, rien d'autre. */
    private record RewrittenRequest(URI uri, HttpHeaders headers) implements HttpRequest {
        @Override
        public HttpMethod getMethod() {
            return HttpMethod.POST;
        }

        @Override
        public URI getURI() {
            return uri;
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return new LinkedHashMap<>();
        }
    }

    /**
     * Réponse Bedrock vue comme une réponse Anthropic : flux event-stream transcrit en SSE, erreur AWS traduite
     * en {@code {"type":"error","error":{...}}}. Un 503 (service indisponible) devient un 529 « overloaded »,
     * rejoué par les relances existantes.
     */
    static final class BedrockResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final boolean stream;
        private HttpStatusCode status;
        private byte[] errorBody;

        BedrockResponse(ClientHttpResponse delegate, boolean stream) {
            this.delegate = delegate;
            this.stream = stream;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            translateErrorIfNeeded();
            return status;
        }

        @Override
        public String getStatusText() throws IOException {
            translateErrorIfNeeded();
            return errorBody == null ? delegate.getStatusText() : status.toString();
        }

        @Override
        public HttpHeaders getHeaders() {
            HttpHeaders copy = new HttpHeaders();
            copy.putAll(delegate.getHeaders());
            try {
                translateErrorIfNeeded();
            } catch (IOException ex) {
                // Les en-têtes restent ceux de Bedrock ; le corps dira l'échec.
            }
            if (errorBody != null) {
                copy.remove(HttpHeaders.CONTENT_LENGTH);
                copy.setContentType(MediaType.APPLICATION_JSON);
            } else if (stream) {
                copy.remove(HttpHeaders.CONTENT_LENGTH);
                copy.setContentType(MediaType.TEXT_EVENT_STREAM);
            }
            return copy;
        }

        @Override
        public InputStream getBody() throws IOException {
            translateErrorIfNeeded();
            if (errorBody != null) {
                return new ByteArrayInputStream(errorBody);
            }
            InputStream body = delegate.getBody();
            return stream && body != null ? new BedrockEventStream(body) : body;
        }

        @Override
        public void close() {
            delegate.close();
        }

        private void translateErrorIfNeeded() throws IOException {
            if (status != null) {
                return;
            }
            HttpStatusCode raw = delegate.getStatusCode();
            if (!raw.isError()) {
                status = raw;
                return;
            }
            String awsType = awsErrorType(delegate.getHeaders());
            String message = "";
            try (InputStream in = delegate.getBody()) {
                byte[] bytes = in == null ? new byte[0] : in.readAllBytes();
                if (bytes.length > 0) {
                    JsonNode node = MAPPER.readTree(bytes);
                    message = node.path("message").asText(node.path("Message").asText(""));
                    if (awsType.isEmpty()) {
                        awsType = node.path("__type").asText("");
                    }
                }
            } catch (IOException ex) {
                message = "";
            }
            Translated translated = translate(raw.value(), awsType, message);
            status = HttpStatusCode.valueOf(translated.status());
            ObjectNode error = MAPPER.createObjectNode();
            error.put("type", "error");
            ObjectNode inner = error.putObject("error");
            inner.put("type", translated.type());
            inner.put("message", translated.message());
            errorBody = MAPPER.writeValueAsBytes(error);
        }
    }

    record Translated(int status, String type, String message) {
    }

    /** {@code x-amzn-ErrorType} sans son suffixe d'espace de noms ({@code ValidationException:http://…}). */
    static String awsErrorType(HttpHeaders headers) {
        String value = headers.getFirst("x-amzn-ErrorType");
        if (value == null) {
            return "";
        }
        int colon = value.indexOf(':');
        String type = colon >= 0 ? value.substring(0, colon) : value;
        int hash = type.lastIndexOf('#');
        return hash >= 0 ? type.substring(hash + 1) : type;
    }

    /** Erreur AWS → statut et type d'erreur Anthropic (visible des tests). */
    static Translated translate(int status, String awsType, String message) {
        String msg = message == null ? "" : message;
        String type = awsType == null ? "" : awsType;
        String lower = msg.toLowerCase(Locale.ROOT);
        if (type.startsWith("Validation") || status == 400) {
            if (lower.contains("too long") || lower.contains("too many tokens")
                    || (lower.contains("exceed") && lower.contains("context"))) {
                // La compaction existante (F-117) se déclenche sur ce libellé.
                return new Translated(400, "invalid_request_error",
                        msg.toLowerCase(Locale.ROOT).startsWith("prompt is too long") ? msg
                                : "prompt is too long: " + msg);
            }
            return new Translated(400, "invalid_request_error", msg);
        }
        if (type.startsWith("AccessDenied") || status == 403) {
            return new Translated(403, "permission_error", msg);
        }
        if (type.startsWith("Unrecognized") || type.startsWith("ExpiredToken") || status == 401) {
            return new Translated(401, "authentication_error", msg);
        }
        if (type.startsWith("Throttling") || type.startsWith("ServiceQuota") || status == 429) {
            return new Translated(429, "rate_limit_error", msg);
        }
        if (type.startsWith("ServiceUnavailable") || type.startsWith("ModelNotReady") || status == 503) {
            return new Translated(529, "overloaded_error", msg);
        }
        if (type.startsWith("ResourceNotFound") || status == 404) {
            return new Translated(404, "not_found_error", msg);
        }
        return new Translated(status, "api_error", msg);
    }
}
