package fr.claudegateway.atelier.proof;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierPlan;
import fr.claudegateway.atelier.AtelierTurnReport;
import fr.claudegateway.atelier.dto.AtelierMessageResponse;

/**
 * F-212 / SF-212-06 — le bloc {@code proof} est écrit dans le compte rendu du tour, survit au bornage de la
 * sortie, et revient dans l'historique ({@code GET /chat}) : c'est ce que l'écran relit.
 */
class AtelierTurnReportProofTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void proofIsPersistedSurvivesBoundingAndIsReturnedByTheHistory() throws Exception {
        String captureId = "a5e23a3ae7e64d68be9266fe69080757";
        ProofBlock proof = new ProofBlock("DNS on-prem", "14", "poste", "macos", "zsh",
                List.of(new ProofBlock.Capture(captureId, 2002, 1132)),
                List.of(new ProofBlock.Command("date -u", "done", 0, 5L, "2026-10-31T11:54:23Z", false)));
        String longOutput = "x".repeat(AtelierTurnReport.MAX_BLOCK_OUTPUT_CHARS + 10);
        AtelierTurnReport.Block block = new AtelierTurnReport.Block("capturer_preuves", null, "toolu_1", null,
                longOutput, true, false, false, null, null, null, null, null, null, null, proof);

        String json = new AtelierTurnReport(1, 1, 1, false, false, AtelierPlan.EMPTY, List.of(block)).toJson();
        JsonNode persisted = mapper.readTree(json).get("blocks").get(0);
        assertThat(persisted.get("output").asText()).startsWith("… (début tronqué)");
        assertThat(persisted.get("proof").get("captures").get(0).get("id").asText()).isEqualTo(captureId);

        AtelierMessage message = AtelierMessage.builder().id(UUID.randomUUID()).role("ASSISTANT")
                .content("```message preuves=" + captureId + "\nSalut\n```").terminalJson(json).build();
        JsonNode returned = AtelierMessageResponse.from(message).terminal().get("blocks").get(0);
        assertThat(returned.get("proof").get("title").asText()).isEqualTo("DNS on-prem");
        assertThat(returned.get("proof").get("captures").get(0).get("id").asText()).isEqualTo(captureId);
        assertThat(returned.get("proof").get("commands").get(0).get("command").asText()).isEqualTo("date -u");
    }
}
