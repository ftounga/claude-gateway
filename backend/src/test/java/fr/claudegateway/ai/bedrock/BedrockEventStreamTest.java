package fr.claudegateway.ai.bedrock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** F-277 / SF-277-01 : les trames event-stream de Bedrock deviennent des lignes SSE Anthropic. */
class BedrockEventStreamTest {

    private static String read(byte[]... frames) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] frame : frames) {
            all.writeBytes(frame);
        }
        try (BedrockEventStream in = new BedrockEventStream(new ByteArrayInputStream(all.toByteArray()))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void turnsChunksIntoSseDataLines() throws IOException {
        String sse = read(BedrockEventStream.chunk("{\"type\":\"message_start\",\"message\":{}}"),
                BedrockEventStream.chunk("{\"type\":\"message_stop\"}"));

        assertThat(sse).isEqualTo("data: {\"type\":\"message_start\",\"message\":{}}\n\n"
                + "data: {\"type\":\"message_stop\"}\n\n");
    }

    @Test
    void turnsAnExceptionFrameIntoAnErrorEvent() throws IOException {
        byte[] exception = BedrockEventStream.frame(
                Map.of(":message-type", "exception", ":exception-type", "throttlingException"),
                "{\"message\":\"Too many requests\"}".getBytes(StandardCharsets.UTF_8));

        String sse = read(exception);

        assertThat(sse).startsWith("data: {\"type\":\"error\"").contains("throttlingException")
                .contains("Too many requests");
    }

    @Test
    void ignoresFramesThatAreNotChunks() throws IOException {
        byte[] other = BedrockEventStream.frame(Map.of(":message-type", "event", ":event-type", "initial-response"),
                "{}".getBytes(StandardCharsets.UTF_8));

        assertThat(read(other, BedrockEventStream.chunk("{\"type\":\"ping\"}")))
                .isEqualTo("data: {\"type\":\"ping\"}\n\n");
    }

    @Test
    void refusesACorruptedFrame() {
        byte[] frame = BedrockEventStream.chunk("{\"type\":\"ping\"}");
        frame[frame.length - 6] ^= 0x01;

        assertThatThrownBy(() -> read(frame)).isInstanceOf(IOException.class);
    }

    @Test
    void refusesATruncatedFrame() {
        byte[] frame = BedrockEventStream.chunk("{\"type\":\"ping\"}");
        byte[] cut = java.util.Arrays.copyOf(frame, frame.length - 3);

        assertThatThrownBy(() -> read(cut)).isInstanceOf(IOException.class);
    }
}
