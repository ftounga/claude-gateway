package fr.claudegateway.contract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.video.VideoRenderToolCatalog;
import fr.claudegateway.atelier.video.VideoToolCatalog;
import fr.claudegateway.runner.video.VideoTools;
import fr.claudegateway.runner.video.render.RenderTools;

/**
 * Rendre une vidéo (F-215 / SF-215-02) : les noms que la gateway déclare au modèle et la capacité qu'elle exige
 * sont ceux que le runner sert et annonce.
 *
 * <p>Une dérive ne casserait rien de visible : la gateway ne verrait jamais la capacité et ne déclarerait pas
 * les outils, ou les déclarerait sous un nom que le runner refuse. Les outils de rendu partagent aussi le
 * préfixe {@code video_} des outils F-190 sans en faire partie : ni la gateway ni le runner ne doivent les
 * confondre.</p>
 */
class VideoRenderToolsContractTest {

    @Test
    @DisplayName("la capacité et les noms des outils de rendu sont les mêmes des deux côtés")
    void lesNomsDuRenduSontLesMemesDesDeuxCotes() {
        assertThat(VideoRenderToolCatalog.CAPABILITY).isEqualTo(RenderTools.CAPABILITY);
        assertThat(VideoRenderToolCatalog.RENDER).isEqualTo(RenderTools.RENDER);
        assertThat(VideoRenderToolCatalog.STATUS).isEqualTo(RenderTools.STATUS);
        assertThat(RenderTools.handles(VideoRenderToolCatalog.RENDER)).isTrue();
        assertThat(RenderTools.handles(VideoRenderToolCatalog.STATUS)).isTrue();
    }

    @Test
    @DisplayName("les outils de rendu ne sont pas confondus avec ceux qui regardent une vidéo")
    void lesOutilsDeRenduNeSontPasLesOutilsVideo() {
        assertThat(VideoToolCatalog.isVideoTool(VideoRenderToolCatalog.RENDER)).isFalse();
        assertThat(VideoTools.handles(RenderTools.RENDER)).isFalse();
        assertThat(VideoTools.handles(RenderTools.STATUS)).isFalse();
        assertThat(RenderTools.CAPABILITY).isNotEqualTo(VideoTools.CAPABILITY);
    }
}
