package fr.claudegateway.runner.channel;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import jakarta.websocket.server.ServerContainer;

/**
 * Bornes de trame du canal runner (F-38 / SF-38-05, contrat de messages §5).
 *
 * <p>Sans ce réglage, le défaut du conteneur est de 8 192 octets : la première lecture de fichier un
 * peu grosse couperait la socket, avec une erreur illisible et très loin de sa cause. Le second test
 * garde l'autre moitié du compromis : ce réglage ne doit jamais faire tomber un contexte qui n'a
 * aucun conteneur WebSocket (tout test en environnement MOCK).</p>
 */
class RunnerWebSocketConfigTest {

    private final RunnerWebSocketConfig config = new RunnerWebSocketConfig(null, null);

    @Test
    void appliesTheOneMebibyteFrameBoundsOnTheRealContainer() throws Exception {
        ServerContainer serverContainer = mock(ServerContainer.class);
        MockServletContext servletContext = new MockServletContext();
        servletContext.setAttribute("jakarta.websocket.server.ServerContainer", serverContainer);

        ServletServerContainerFactoryBean bean = config.runnerWebSocketContainer();
        bean.setServletContext(servletContext);
        bean.afterPropertiesSet();

        verify(serverContainer).setDefaultMaxTextMessageBufferSize(RunnerWebSocketConfig.MAX_MESSAGE_BYTES);
        verify(serverContainer).setDefaultMaxBinaryMessageBufferSize(RunnerWebSocketConfig.MAX_MESSAGE_BYTES);
    }

    @Test
    void staysSilentWhenTheContextHasNoWebSocketContainer() {
        ServletServerContainerFactoryBean bean = config.runnerWebSocketContainer();
        bean.setServletContext(new MockServletContext());

        assertThatCode(bean::afterPropertiesSet).doesNotThrowAnyException();
    }
}
