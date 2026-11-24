package fr.claudegateway.atelier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;

/**
 * <b>Le ruban envoyé d'un tour</b> (F-276 / SF-276-02) : les messages que le tour a ajoutés à la
 * conversation, <b>tels qu'ils sont partis au fournisseur</b> — consigne augmentée, consignes d'effort,
 * étapes (raisonnement signé, texte, appels, résultats entiers), précisions étiquetées, corrections — et
 * sa dernière réponse réelle.
 *
 * <p><b>Pourquoi.</b> Le fournisseur lie chaque bloc de raisonnement à tout ce qui le précédait quand il
 * a été produit. Rejouer un tour <b>reconstruit</b> depuis la base (parole nue, résultats coupés à
 * 8 000 caractères…) est une édition de l'historique : il écarte alors tout le raisonnement des tours
 * passés (~2 370 appels sur 7 jours en prod, 2026-11-23). Rejouer le ruban à l'octet près rend
 * l'historique <b>append-only</b>.</p>
 *
 * <p>Le JSON est sans perte sur tous les blocs neutres. Il porte aussi l'identifiant du message
 * utilisateur qui ouvre le tour et ceux des précisions rangées pendant le tour : ce sont les lignes
 * que le ruban remplace au rejeu.</p>
 *
 * @param from     message utilisateur qui ouvre le tour
 * @param rows     autres lignes du fil rangées pendant le tour et portées par le ruban (précisions)
 * @param messages les messages du tour, dans l'ordre d'envoi, dernière réponse comprise
 */
public record AtelierSentRibbon(UUID from, List<UUID> rows, List<AgentMessage> messages) {

    /** Au-delà (captures d'écran en série), le tour garde le rejeu d'avant : la base n'est pas un dépôt. */
    static final int MAX_CHARS = 4_000_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int VERSION = 1;

    public AtelierSentRibbon {
        rows = rows == null ? List.of() : List.copyOf(rows);
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    /** Le JSON à ranger, ou {@code null} s'il est vide, trop gros ou impossible à écrire. */
    public String toJson() {
        if (from == null || messages.isEmpty()) {
            return null;
        }
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("v", VERSION);
            root.put("from", from.toString());
            ArrayNode rowIds = root.putArray("rows");
            rows.forEach(id -> rowIds.add(id.toString()));
            ArrayNode list = root.putArray("messages");
            for (AgentMessage message : messages) {
                list.add(write(message));
            }
            String json = MAPPER.writeValueAsString(root);
            return json.length() <= MAX_CHARS ? json : null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException ex) {
            return null;
        }
    }

    /** Le ruban rangé, ou {@code null} s'il est absent, illisible ou d'une version inconnue. */
    public static AtelierSentRibbon parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || root.path("v").asInt(0) != VERSION || !root.path("from").isTextual()) {
                return null;
            }
            List<UUID> rows = new ArrayList<>();
            for (JsonNode id : root.path("rows")) {
                rows.add(UUID.fromString(id.asText()));
            }
            List<AgentMessage> messages = new ArrayList<>();
            for (JsonNode message : root.path("messages")) {
                messages.add(read(message));
            }
            return messages.isEmpty() ? null
                    : new AtelierSentRibbon(UUID.fromString(root.path("from").asText()), rows, messages);
        } catch (com.fasterxml.jackson.core.JacksonException | RuntimeException ex) {
            return null;
        }
    }

    private static ObjectNode write(AgentMessage message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("role", message.role());
        if (message.effort() != null) {
            node.put("effort", message.effort());
        }
        ArrayNode content = node.putArray("content");
        for (AgentContentBlock block : message.content() == null ? List.<AgentContentBlock>of() : message.content()) {
            content.add(write(block));
        }
        return node;
    }

    private static ObjectNode write(AgentContentBlock block) {
        ObjectNode node = MAPPER.createObjectNode();
        switch (block) {
            case AgentContentBlock.Text text -> {
                node.put("t", "text");
                node.put("text", text.text());
            }
            case AgentContentBlock.ToolUse use -> {
                node.put("t", "tool_use");
                node.put("id", use.id());
                node.put("name", use.name());
                if (use.input() != null && !use.input().isMissingNode()) {
                    node.set("input", use.input());
                }
            }
            case AgentContentBlock.ToolResult result -> {
                node.put("t", "tool_result");
                node.put("id", result.toolUseId());
                node.put("content", result.content());
                node.put("error", result.isError());
                if (result.blocks() != null) {
                    ArrayNode blocks = node.putArray("blocks");
                    result.blocks().forEach(inner -> blocks.add(write(inner)));
                }
            }
            case AgentContentBlock.Image image -> {
                node.put("t", "image");
                node.put("media", image.mediaType());
                node.put("data", image.base64Data());
            }
            case AgentContentBlock.Document document -> {
                node.put("t", "document");
                node.put("media", document.mediaType());
                node.put("data", document.base64Data());
            }
            case AgentContentBlock.Reasoning reasoning -> {
                node.put("t", "thinking");
                node.put("text", reasoning.text());
                node.put("signature", reasoning.signature());
            }
            case AgentContentBlock.RedactedReasoning redacted -> {
                node.put("t", "redacted_thinking");
                node.put("data", redacted.data());
            }
            case AgentContentBlock.ServerTool server -> {
                node.put("t", "server");
                node.set("raw", server.raw());
            }
        }
        return node;
    }

    private static AgentMessage read(JsonNode node) {
        List<AgentContentBlock> content = new ArrayList<>();
        for (JsonNode block : node.path("content")) {
            content.add(readBlock(block));
        }
        return new AgentMessage(node.path("role").asText(), content, text(node, "effort"));
    }

    private static AgentContentBlock readBlock(JsonNode node) {
        return switch (node.path("t").asText("")) {
            case "text" -> new AgentContentBlock.Text(text(node, "text"));
            case "tool_use" -> new AgentContentBlock.ToolUse(text(node, "id"), text(node, "name"),
                    node.has("input") ? node.get("input") : MissingNode.getInstance());
            case "tool_result" -> {
                List<AgentContentBlock> blocks = null;
                if (node.has("blocks")) {
                    blocks = new ArrayList<>();
                    for (JsonNode inner : node.get("blocks")) {
                        blocks.add(readBlock(inner));
                    }
                }
                yield new AgentContentBlock.ToolResult(text(node, "id"), text(node, "content"),
                        node.path("error").asBoolean(false), blocks);
            }
            case "image" -> new AgentContentBlock.Image(text(node, "media"), text(node, "data"));
            case "document" -> new AgentContentBlock.Document(text(node, "media"), text(node, "data"));
            case "thinking" -> new AgentContentBlock.Reasoning(text(node, "text"), text(node, "signature"));
            case "redacted_thinking" -> new AgentContentBlock.RedactedReasoning(text(node, "data"));
            case "server" -> new AgentContentBlock.ServerTool(node.get("raw"));
            default -> throw new IllegalArgumentException("bloc inconnu");
        };
    }

    /** Valeur textuelle, {@code null} préservé (un {@code null} écrit relu en {@code null}). */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
