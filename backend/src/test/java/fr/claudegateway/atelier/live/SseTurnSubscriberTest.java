package fr.claudegateway.atelier.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

/**
 * Le battement de cœur d'un spectateur SSE (F-170 / SF-170-01) : il émet un <b>commentaire</b> SSE
 * (ligne {@code : ...}, ignorée des clients SSE), et sur un flux mort il rend {@code false} sans jamais
 * lever d'exception.
 */
class SseTurnSubscriberTest {

    @Test
    void leBattementEmetUnCommentaireSseEtRendVrai() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        SseTurnSubscriber subscriber = new SseTurnSubscriber(emitter);

        boolean sent = subscriber.heartbeat();

        assertThat(sent).isTrue();
        ArgumentCaptor<SseEventBuilder> captor = ArgumentCaptor.forClass(SseEventBuilder.class);
        verify(emitter).send(captor.capture());
        String frame = captor.getValue().build().stream()
                .map(part -> String.valueOf(part.getData()))
                .collect(Collectors.joining());
        assertThat(frame).as("un commentaire SSE").contains(":ping");
        assertThat(frame).as("ni données ni événement nommé : rien à afficher").doesNotContain("data:");
        assertThat(frame).doesNotContain("event:");
    }

    @Test
    void leBattementSurUnFluxMortRendFauxSansException() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("Broken pipe")).when(emitter).send(any(SseEventBuilder.class));
        SseTurnSubscriber subscriber = new SseTurnSubscriber(emitter);

        // Un broken pipe à l'écriture ne doit jamais remonter : le spectateur se contente de dire
        // « je suis parti » (false) et le tour le détache.
        assertThat(subscriber.heartbeat()).isFalse();
    }
}
