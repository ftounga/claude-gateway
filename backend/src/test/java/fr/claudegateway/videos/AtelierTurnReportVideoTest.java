package fr.claudegateway.videos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.AtelierPlan;
import fr.claudegateway.atelier.AtelierTurnReport;

/** Les blocs vidéo et audio dans le compte rendu de tour (F-213 / SF-213-01). */
class AtelierTurnReportVideoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void videoAndAudioBlocksAreSerializedAndSurviveBounding() throws Exception {
        UUID videoId = UUID.randomUUID();
        VideoBlock video = new VideoBlock(videoId, "Cours", null, 2, 154, List.of(new VideoChapter(0, "Intro")),
                null, null, null, false);
        AudioBlock audio = new AudioBlock("Auditions", List.of(new AudioBlock.Track(UUID.randomUUID(), "Voix 1", 1,
                "audio/mpeg")));
        String longOutput = "x".repeat(AtelierTurnReport.MAX_BLOCK_OUTPUT_CHARS + 10);
        AtelierTurnReport.Block videoBlock = new AtelierTurnReport.Block("video_publier", null, "c1", null, longOutput,
                true, false, false, null, null, null, null, null, null, null, null, video, null);
        AtelierTurnReport.Block audioBlock = new AtelierTurnReport.Block("audio_publier", null, "c2", null, "ok",
                true, false, false, null, null, null, null, null, null, null, null, null, audio);

        String json = new AtelierTurnReport(1, 1, 1, false, false, AtelierPlan.EMPTY, List.of(videoBlock, audioBlock))
                .toJson();
        JsonNode blocks = mapper.readTree(json).get("blocks");

        assertThat(blocks.get(0).get("video").get("videoId").asText()).isEqualTo(videoId.toString());
        assertThat(blocks.get(0).get("video").get("chapters").get(0).get("titre").asText()).isEqualTo("Intro");
        assertThat(blocks.get(0).get("output").asText()).startsWith("… (début tronqué)");
        assertThat(blocks.get(1).get("audio").get("tracks").get(0).get("label").asText()).isEqualTo("Voix 1");
    }

    @Test
    void previousShapeHasNoVideo() {
        AtelierTurnReport.Block block = new AtelierTurnReport.Block("bash", null, "c", null, "o", true, false, false,
                null, null, null, null, null, null, null, null);
        assertThat(block.video()).isNull();
        assertThat(block.audio()).isNull();
    }
}
