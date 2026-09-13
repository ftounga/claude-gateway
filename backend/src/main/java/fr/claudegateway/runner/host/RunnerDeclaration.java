package fr.claudegateway.runner.host;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Ce que le runner déclare de lui-même dans sa trame {@code ready} (F-81 / SF-81-03, étendu par
 * F-111 / SF-111-01) : version, niveau de contrat, Java, lanceur, capacités.
 *
 * <p>Tout vient d'un client : chaque champ qui n'a pas la forme attendue est <b>écarté</b>, jamais
 * tronqué ni deviné. Un champ absent vaut {@code null} — c'est la réponse exacte pour un runner
 * antérieur qui ne le déclarait pas.</p>
 *
 * @param version      identifiant de version, ou {@code null}
 * @param contract     niveau de contrat (1..1000), ou {@code null}
 * @param javaVersion  version majeure de Java (8..1000), ou {@code null}
 * @param launcher     vrai si le runner tourne sous le lanceur, ou {@code null}
 * @param capabilities capacités déclarées, vide si aucune n'est lisible
 */
public record RunnerDeclaration(String version, Integer contract, Integer javaVersion,
        Boolean launcher, List<String> capabilities) {

    private static final Pattern CAPABILITY = Pattern.compile("[a-z0-9_-]{1,32}");

    public RunnerDeclaration {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }

    /** Lit la déclaration d'une trame {@code ready}. Ne lève jamais. */
    public static RunnerDeclaration fromReadyFrame(JsonNode frame) {
        if (frame == null) {
            return new RunnerDeclaration(null, null, null, null, List.of());
        }
        JsonNode version = frame.path("runnerVersion");
        List<String> capabilities = new ArrayList<>();
        JsonNode declared = frame.path("capabilities");
        if (declared.isArray()) {
            declared.forEach(entry -> {
                if (entry.isTextual() && CAPABILITY.matcher(entry.asText()).matches()
                        && !capabilities.contains(entry.asText())) {
                    capabilities.add(entry.asText());
                }
            });
        }
        return new RunnerDeclaration(
                version.isTextual() && !version.asText().isBlank() ? version.asText().trim() : null,
                boundedInt(frame.path("contract"), 1, 1000),
                boundedInt(frame.path("javaVersion"), 8, 1000),
                frame.path("launcher").isBoolean() ? frame.path("launcher").asBoolean() : null,
                capabilities);
    }

    /** Les capacités jointes par des virgules, ou {@code null} si vides ou trop longues. */
    public String joinedCapabilities() {
        if (capabilities.isEmpty()) {
            return null;
        }
        String joined = String.join(",", capabilities);
        return joined.length() > RunnerHost.MAX_RUNNER_CAPABILITIES_LENGTH ? null : joined;
    }

    private static Integer boundedInt(JsonNode node, int min, int max) {
        if (!node.isInt()) {
            return null;
        }
        int value = node.asInt();
        return value < min || value > max ? null : value;
    }
}
