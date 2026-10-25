package fr.claudegateway.contract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import fr.claudegateway.runner.toolchain.VideoToolchainController;
import fr.claudegateway.runner.toolchain.VideoToolchainStore;
import fr.claudegateway.runner.video.render.VideoToolchainBundle;

/**
 * La chaîne vidéo servie par le portail (F-215 / SF-215-06) : la route que le runner appelle, l'en-tête qui porte son
 * jeton et les plateformes qu'il demande sont ceux que la gateway sert.
 *
 * <p>Une dérive ne casserait rien de visible côté gateway : le runner recevrait un 404 ou un 401, et le poste
 * retomberait sur « le portail ne sert pas la chaîne vidéo » — exactement l'impasse que SF-215-06 supprime.</p>
 */
class VideoToolchainContractTest {

    @Test
    @DisplayName("la route, l'en-tête du jeton et les plateformes sont les mêmes des deux côtés")
    void routeHeaderAndPlatformsMatch() {
        String mapping = VideoToolchainController.class.getAnnotation(RequestMapping.class).value()[0];
        assertThat(VideoToolchainBundle.ROUTE).isEqualTo(mapping + "/");
        assertThat(VideoToolchainBundle.TOKEN_HEADER).isEqualTo(VideoToolchainController.TOKEN_HEADER);
        assertThat(VideoToolchainBundle.PLATFORMS).containsExactlyElementsOf(VideoToolchainStore.PLATFORMS);
    }

    @Test
    @DisplayName("la version épinglée par le runner est une version que la gateway accepte de servir")
    void pinnedVersionIsServable() {
        assertThat(VideoToolchainStore.validVersion(VideoToolchainBundle.VERSION)).isTrue();
        VideoToolchainBundle.PLATFORMS.forEach(p -> assertThat(VideoToolchainStore.validPlatform(p)).isTrue());
    }
}
