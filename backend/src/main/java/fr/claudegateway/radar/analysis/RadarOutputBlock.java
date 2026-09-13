package fr.claudegateway.radar.analysis;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;

/**
 * Le <b>bloc final strict</b> d'une réponse de lecture (F-101), sur le modèle de {@code JugeVerdict}
 * (F-94) : le modèle raisonne librement, puis pose un marqueur suivi d'<b>un seul objet JSON</b>.
 *
 * <p><b>Le dernier marqueur fait foi</b> — une réponse peut citer la forme attendue avant de la poser.
 * Ce qui précède n'existe pas. Des clôtures de bloc de code autour de l'objet sont tolérées ; tout le
 * reste (texte après l'objet, second objet, commentaire) rend le bloc illisible.</p>
 */
final class RadarOutputBlock {

    /** Lecteur strict : pas de commentaires, pas de guillemets simples, pas de doublons de clé. */
    private static final ObjectMapper STRICT = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private RadarOutputBlock() {
    }

    /**
     * L'objet JSON qui suit le dernier marqueur.
     *
     * @return l'objet, ou vide si le marqueur manque ou si ce qui le suit n'est pas exactement un objet
     */
    static Optional<JsonNode> read(String response, String marker) {
        if (response == null || response.isBlank()) {
            return Optional.empty();
        }
        int at = response.lastIndexOf(marker);
        if (at < 0) {
            return Optional.empty();
        }
        String block = response.substring(at + marker.length()).strip();
        if (block.startsWith("```") || block.startsWith("~~~")) {
            int firstLine = block.indexOf('\n');
            if (firstLine < 0) {
                return Optional.empty();
            }
            block = block.substring(firstLine + 1).strip();
            if (block.endsWith("```") || block.endsWith("~~~")) {
                block = block.substring(0, block.length() - 3).strip();
            }
        }
        if (!block.startsWith("{")) {
            return Optional.empty();
        }
        try {
            JsonNode node = STRICT.readTree(block);
            return node != null && node.isObject() ? Optional.of(node) : Optional.empty();
        } catch (Exception ex) {
            return Optional.empty();
        }
    }
}
