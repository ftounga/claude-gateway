package fr.claudegateway.atelier.gallery;

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
 * F-278 / SF-278-01 — le bloc {@code gallery} est écrit dans le compte rendu du tour, survit au bornage de la sortie,
 * et revient dans l'historique : c'est ce que l'écran relit au rechargement.
 */
class AtelierTurnReportGalleryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void galleryIsPersistedSurvivesBoundingAndIsReturnedByTheHistory() throws Exception {
        String imageId = "a5e23a3ae7e64d68be9266fe69080757";
        GalleryBlock gallery = new GalleryBlock("Captures en attente",
                List.of(new GalleryBlock.Item(imageId, "a.png", ".atelier/entrees/a.png", "2026-11-27T09:30:12",
                        "Erreur 403", 1600, 900)),
                List.of(new GalleryBlock.Skipped("b.webp", "format non pris en charge (webp)")));
        String longOutput = "x".repeat(AtelierTurnReport.MAX_BLOCK_OUTPUT_CHARS + 10);
        AtelierTurnReport.Block block = new AtelierTurnReport.Block("galerie_images", null, "toolu_1", null,
                longOutput, true, false, false, null, null, null, null, null, null, null, null, null, null, gallery);

        String json = new AtelierTurnReport(1, 1, 1, false, false, AtelierPlan.EMPTY, List.of(block)).toJson();
        JsonNode persisted = mapper.readTree(json).get("blocks").get(0);
        assertThat(persisted.get("output").asText()).startsWith("… (début tronqué)");
        assertThat(persisted.get("gallery").get("items").get(0).get("id").asText()).isEqualTo(imageId);

        AtelierMessage message = AtelierMessage.builder().id(UUID.randomUUID()).role("ASSISTANT")
                .content("Voici les captures.").terminalJson(json).build();
        JsonNode returned = AtelierMessageResponse.from(message).terminal().get("blocks").get(0);
        assertThat(returned.get("gallery").get("title").asText()).isEqualTo("Captures en attente");
        assertThat(returned.get("gallery").get("items").get(0).get("caption").asText()).isEqualTo("Erreur 403");
        assertThat(returned.get("gallery").get("skipped").get(0).get("reason").asText()).contains("webp");
    }
}
