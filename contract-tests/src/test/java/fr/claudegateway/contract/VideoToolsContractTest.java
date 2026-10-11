package fr.claudegateway.contract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.video.VideoToolCatalog;
import fr.claudegateway.runner.video.VideoTools;

/**
 * Regarder une vidéo (F-190 / SF-190-01) : les noms que la gateway déclare au modèle et la capacité
 * qu'elle exige sont ceux que le runner sert et annonce.
 *
 * <p>Une dérive ici ne casse rien de visible : la gateway ne verrait jamais la capacité et ne
 * déclarerait pas les outils, ou les déclarerait sous un nom que le runner refuse. L'agent
 * retomberait sur « je n'ai pas ffmpeg » — exactement la panne d'origine de F-190.</p>
 */
class VideoToolsContractTest {

    @Test
    @DisplayName("la capacité et les noms des outils vidéo sont les mêmes des deux côtés")
    void lesNomsVideoSontLesMemesDesDeuxCotes() {
        assertThat(VideoToolCatalog.CAPABILITY).isEqualTo(VideoTools.CAPABILITY);
        assertThat(VideoToolCatalog.IMAGES).isEqualTo(VideoTools.IMAGES);
        assertThat(VideoToolCatalog.STATUS).isEqualTo(VideoTools.STATUS);
        assertThat(VideoTools.handles(VideoToolCatalog.IMAGES)).isTrue();
        assertThat(VideoTools.handles(VideoToolCatalog.STATUS)).isTrue();
        assertThat(VideoToolCatalog.IMAGES).startsWith(VideoToolCatalog.PREFIX);
        assertThat(VideoToolCatalog.STATUS).startsWith(VideoToolCatalog.PREFIX);
    }
}
