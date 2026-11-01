package fr.claudegateway.atelier.measure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentMessageSanitizer;
import fr.claudegateway.atelier.AtelierToolTrace;

/**
 * <b>L'empreinte du ruban</b> (F-229 / SF-229-01) : pour chaque bloc des {@code messages} d'une requête
 * (hors consigne système et outils), un hachage et une taille. <b>Aucun contenu</b> : le hachage est un
 * SHA-256 tronqué à 16 caractères hexadécimaux, la taille un nombre de caractères.
 *
 * <p><b>Forme canonique.</b> La séquence est d'abord assainie comme au point d'envoi
 * ({@link AgentMessageSanitizer} : tête {@code user}, blocs blancs retirés, mêmes rôles fusionnés — la
 * fusion ne change pas la suite des blocs), puis aplatie bloc par bloc. Les consignes d'effort, sans
 * contenu, ne comptent pas ; les marqueurs de cache non plus (le fournisseur les pose ailleurs à chaque
 * appel par construction). Chaque bloc est réduit à son rôle, son type et ses champs envoyés, séparés
 * par un caractère de contrôle : deux blocs de même empreinte sont deux blocs que le fournisseur reçoit
 * à l'identique. La forme ne dépend d'aucun fournisseur : elle se calcule sur les messages du domaine.</p>
 *
 * <p>Comparer deux empreintes dit où le ruban d'un tour cesse de prolonger celui du tour précédent, et
 * combien de caractères du ruban précédent le fournisseur devra réécrire.</p>
 */
public final class RibbonFingerprint {

    /** Au-delà, l'empreinte n'est pas rangée (colonne {@code NULL}) : la ligne garderait un ruban démesuré. */
    public static final int MAX_BLOCKS = 4_000;

    /** Longueur du hachage tronqué, en caractères hexadécimaux. */
    static final int HASH_CHARS = 16;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final char SEP = '\u0001';

    /** Un bloc : son hachage tronqué et sa taille en caractères. */
    public record Block(String hash, long size) {
    }

    private final List<Block> blocks;
    /** Blocs qui sont un résultat d'outil coupé au stockage — connu seulement d'un ruban calculé ici. */
    private final BitSet truncatedResults;

    private RibbonFingerprint(List<Block> blocks, BitSet truncatedResults) {
        this.blocks = List.copyOf(blocks);
        this.truncatedResults = truncatedResults;
    }

    /** Empreinte des messages tels qu'ils partent au fournisseur. Ne modifie jamais la liste reçue. */
    public static RibbonFingerprint of(List<AgentMessage> messages) {
        List<Block> blocks = new ArrayList<>();
        BitSet truncated = new BitSet();
        MessageDigest digest = sha256();
        for (AgentMessage message : AgentMessageSanitizer.sanitize(messages == null ? List.of() : messages)) {
            if (message.isEffortDirective()) {
                continue;
            }
            for (AgentContentBlock block : message.content()) {
                StringBuilder canonical = new StringBuilder(message.role()).append(SEP);
                canonical(block, canonical);
                if (block instanceof AgentContentBlock.ToolResult result
                        && AtelierToolTrace.isTruncatedResult(result.content())) {
                    truncated.set(blocks.size());
                }
                blocks.add(new Block(hash(digest, canonical), canonical.length()));
            }
        }
        return new RibbonFingerprint(blocks, truncated);
    }

    /**
     * Relit une empreinte rangée. Une empreinte absente, illisible ou hors contrat (hachage mal formé,
     * taille négative, trop de blocs) rend {@code null} : le tour se compare alors à « rien ».
     */
    public static RibbonFingerprint parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isArray() || root.size() > MAX_BLOCKS) {
                return null;
            }
            List<Block> blocks = new ArrayList<>(root.size());
            for (JsonNode pair : root) {
                if (!pair.isArray() || pair.size() != 2 || !pair.get(0).isTextual()
                        || !pair.get(1).canConvertToLong()) {
                    return null;
                }
                String hash = pair.get(0).asText();
                long size = pair.get(1).asLong();
                if (!isHash(hash) || size < 0) {
                    return null;
                }
                blocks.add(new Block(hash, size));
            }
            return new RibbonFingerprint(blocks, new BitSet());
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            return null;
        }
    }

    /** Le JSON {@code [[hachage, taille], …]} à ranger, ou {@code null} au-delà de {@link #MAX_BLOCKS}. */
    public String toJson() {
        if (tooLarge()) {
            return null;
        }
        ArrayNode root = MAPPER.createArrayNode();
        for (Block block : blocks) {
            root.addArray().add(block.hash()).add(block.size());
        }
        return root.toString();
    }

    public List<Block> blocks() {
        return blocks;
    }

    public int size() {
        return blocks.size();
    }

    /** Vrai si l'empreinte dépasse {@link #MAX_BLOCKS} : elle n'est ni rangée, ni comparée. */
    public boolean tooLarge() {
        return blocks.size() > MAX_BLOCKS;
    }

    /**
     * Premier bloc du ruban {@code previous} que ce ruban ne reprend pas à l'identique, ou {@code -1}
     * si ce ruban le prolonge intégralement. Un ruban plus court que le précédent diverge à sa fin.
     */
    public int divergenceFrom(RibbonFingerprint previous) {
        List<Block> before = previous.blocks;
        for (int i = 0; i < before.size(); i++) {
            if (i >= blocks.size() || !before.get(i).equals(blocks.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Caractères des blocs à partir de {@code index} (inclus) : ce qui suit une divergence. */
    public long charsFrom(int index) {
        long chars = 0L;
        for (int i = Math.max(0, index); i < blocks.size(); i++) {
            chars += blocks.get(i).size();
        }
        return chars;
    }

    /** Vrai si le bloc {@code index} est un résultat d'outil coupé au stockage (marqueur de coupe). */
    public boolean isTruncatedResult(int index) {
        return index >= 0 && truncatedResults.get(index);
    }

    private static boolean isHash(String hash) {
        if (hash.length() != HASH_CHARS) {
            return false;
        }
        for (int i = 0; i < hash.length(); i++) {
            if (Character.digit(hash.charAt(i), 16) < 0 || Character.isUpperCase(hash.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Forme canonique d'un bloc : son type puis ses champs envoyés, dans un ordre fixe. */
    private static void canonical(AgentContentBlock block, StringBuilder out) {
        switch (block) {
            case AgentContentBlock.Text text -> out.append("text").append(SEP).append(text.text());
            case AgentContentBlock.ToolUse use -> out.append("tool_use").append(SEP).append(use.id()).append(SEP)
                    .append(use.name()).append(SEP).append(use.input() == null ? "" : use.input().toString());
            case AgentContentBlock.Reasoning reasoning -> out.append("thinking").append(SEP)
                    .append(reasoning.text() == null ? "" : reasoning.text()).append(SEP)
                    .append(reasoning.signature() == null ? "" : reasoning.signature());
            case AgentContentBlock.RedactedReasoning redacted -> out.append("redacted_thinking").append(SEP)
                    .append(redacted.data() == null ? "" : redacted.data());
            case AgentContentBlock.ServerTool server -> out.append("server_tool").append(SEP)
                    .append(server.raw() == null ? "" : server.raw().toString());
            case AgentContentBlock.Image image -> out.append("image").append(SEP).append(image.mediaType())
                    .append(SEP).append(image.base64Data() == null ? "" : image.base64Data());
            case AgentContentBlock.Document document -> out.append("document").append(SEP)
                    .append(document.mediaType()).append(SEP)
                    .append(document.base64Data() == null ? "" : document.base64Data());
            case AgentContentBlock.ToolResult result -> {
                out.append("tool_result").append(SEP).append(result.toolUseId()).append(SEP)
                        .append(result.isError()).append(SEP);
                if (result.blocks() != null && !result.blocks().isEmpty()) {
                    for (AgentContentBlock inner : result.blocks()) {
                        out.append('[');
                        canonical(inner, out);
                        out.append(']');
                    }
                } else {
                    out.append(result.content());
                }
            }
        }
    }

    private static String hash(MessageDigest digest, CharSequence canonical) {
        digest.reset();
        byte[] bytes = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(bytes).substring(0, HASH_CHARS);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 indisponible", ex);
        }
    }
}
