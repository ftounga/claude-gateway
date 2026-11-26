package fr.claudegateway.ai.bedrock;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Transcrit le flux binaire {@code application/vnd.amazon.eventstream} de
 * {@code InvokeModelWithResponseStream} en flux SSE Anthropic (F-277 / SF-277-01).
 *
 * <p>Chaque trame {@code chunk} porte {@code {"bytes":"<base64>"}}, dont le contenu décodé est l'événement
 * Anthropic tel quel ({@code message_start}, {@code content_block_delta}…). La lecture SSE existante
 * d'{@code AnthropicAgentProvider} et d'{@code AnthropicProvider} le relit donc sans rien changer : une trame
 * devient une ligne {@code data: {...}} suivie d'une ligne vide.</p>
 *
 * <p>Une trame d'exception devient un événement {@code error} (repli non streamé existant) ; une trame
 * corrompue (CRC) lève une {@link IOException} (même repli).</p>
 */
public final class BedrockEventStream extends InputStream {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PRELUDE = 12;
    private static final int MAX_FRAME = 16 * 1024 * 1024;

    private final DataInputStream in;
    private byte[] current = new byte[0];
    private int position;
    private boolean ended;

    public BedrockEventStream(InputStream in) {
        this.in = new DataInputStream(in);
    }

    @Override
    public int read() throws IOException {
        if (!fill()) {
            return -1;
        }
        return current[position++] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (!fill()) {
            return -1;
        }
        int n = Math.min(length, current.length - position);
        System.arraycopy(current, position, buffer, offset, n);
        position += n;
        return n;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    /** Charge la trame suivante si la courante est épuisée ; faux en fin de flux. */
    private boolean fill() throws IOException {
        while (position >= current.length) {
            if (ended) {
                return false;
            }
            byte[] next = nextEvent();
            if (next == null) {
                ended = true;
                return false;
            }
            current = next;
            position = 0;
        }
        return true;
    }

    /** Lit une trame et rend sa ligne SSE ({@code null} en fin de flux, vide si la trame est ignorée). */
    private byte[] nextEvent() throws IOException {
        byte[] prelude = new byte[PRELUDE];
        int first = in.read();
        if (first < 0) {
            return null;
        }
        prelude[0] = (byte) first;
        try {
            in.readFully(prelude, 1, PRELUDE - 1);
        } catch (EOFException ex) {
            throw new IOException("trame event-stream tronquée", ex);
        }
        ByteBuffer head = ByteBuffer.wrap(prelude);
        int total = head.getInt();
        int headersLength = head.getInt();
        int preludeCrc = head.getInt();
        if (crc(prelude, 0, 8) != preludeCrc) {
            throw new IOException("CRC du prélude event-stream invalide");
        }
        if (total < PRELUDE + 4 || total > MAX_FRAME || headersLength < 0 || headersLength > total - PRELUDE - 4) {
            throw new IOException("longueur de trame event-stream invalide");
        }
        byte[] rest = new byte[total - PRELUDE];
        in.readFully(rest);
        CRC32 messageCrc = new CRC32();
        messageCrc.update(prelude);
        messageCrc.update(rest, 0, rest.length - 4);
        if ((int) messageCrc.getValue() != ByteBuffer.wrap(rest, rest.length - 4, 4).getInt()) {
            throw new IOException("CRC de trame event-stream invalide");
        }
        Map<String, String> headers = headers(rest, headersLength);
        byte[] payload = new byte[rest.length - 4 - headersLength];
        System.arraycopy(rest, headersLength, payload, 0, payload.length);
        return toSse(headers, payload);
    }

    private static byte[] toSse(Map<String, String> headers, byte[] payload) throws IOException {
        String messageType = headers.getOrDefault(":message-type", "event");
        if ("event".equals(messageType)) {
            if (!"chunk".equals(headers.get(":event-type"))) {
                return new byte[0];
            }
            JsonNode chunk = MAPPER.readTree(payload);
            String encoded = chunk.path("bytes").asText("");
            if (encoded.isEmpty()) {
                return new byte[0];
            }
            String json = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            return line(json.replace('\r', ' ').replace('\n', ' '));
        }
        // Exception (ou erreur) en cours de flux : un événement `error` de forme Anthropic.
        String type = headers.getOrDefault(":exception-type", headers.getOrDefault(":error-code", "api_error"));
        String message = "";
        try {
            JsonNode body = MAPPER.readTree(payload);
            message = body.path("message").asText(body.path("Message").asText(""));
        } catch (IOException ex) {
            message = "";
        }
        ObjectNode event = MAPPER.createObjectNode();
        event.put("type", "error");
        ObjectNode error = event.putObject("error");
        error.put("type", type);
        error.put("message", message);
        return line(MAPPER.writeValueAsString(event));
    }

    private static byte[] line(String json) {
        return ("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
    }

    /** En-têtes de la trame ; seules les valeurs chaîne sont gardées, les autres sont sautées. */
    private static Map<String, String> headers(byte[] data, int length) throws IOException {
        Map<String, String> headers = new HashMap<>();
        int i = 0;
        try {
            while (i < length) {
                int nameLength = data[i++] & 0xFF;
                String name = new String(data, i, nameLength, StandardCharsets.UTF_8);
                i += nameLength;
                int type = data[i++] & 0xFF;
                switch (type) {
                    case 0, 1 -> { }
                    case 2 -> i += 1;
                    case 3 -> i += 2;
                    case 4 -> i += 4;
                    case 5, 8 -> i += 8;
                    case 9 -> i += 16;
                    case 6, 7 -> {
                        int valueLength = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
                        i += 2;
                        if (type == 7) {
                            headers.put(name, new String(data, i, valueLength, StandardCharsets.UTF_8));
                        }
                        i += valueLength;
                    }
                    default -> throw new IOException("type d'en-tête event-stream inconnu : " + type);
                }
            }
        } catch (IndexOutOfBoundsException ex) {
            throw new IOException("en-têtes event-stream illisibles", ex);
        }
        return headers;
    }

    private static int crc(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return (int) crc.getValue();
    }

    /** Fabrique une trame (tests et banc) : en-têtes chaîne, charge utile brute. */
    public static byte[] frame(Map<String, String> headers, byte[] payload) {
        ByteArrayOutputStream encodedHeaders = new ByteArrayOutputStream();
        headers.forEach((name, value) -> {
            byte[] n = name.getBytes(StandardCharsets.UTF_8);
            byte[] v = value.getBytes(StandardCharsets.UTF_8);
            encodedHeaders.write(n.length);
            encodedHeaders.writeBytes(n);
            encodedHeaders.write(7);
            encodedHeaders.write((v.length >> 8) & 0xFF);
            encodedHeaders.write(v.length & 0xFF);
            encodedHeaders.writeBytes(v);
        });
        byte[] h = encodedHeaders.toByteArray();
        int total = PRELUDE + h.length + payload.length + 4;
        ByteBuffer buffer = ByteBuffer.allocate(total);
        buffer.putInt(total).putInt(h.length);
        buffer.putInt(crc(buffer.array(), 0, 8));
        buffer.put(h).put(payload);
        buffer.putInt(crc(buffer.array(), 0, total - 4));
        return buffer.array();
    }

    /** Trame {@code chunk} portant un événement Anthropic (tests et banc). */
    public static byte[] chunk(String anthropicEventJson) {
        String bytes = Base64.getEncoder().encodeToString(anthropicEventJson.getBytes(StandardCharsets.UTF_8));
        return frame(Map.of(":message-type", "event", ":event-type", "chunk", ":content-type", "application/json"),
                ("{\"bytes\":\"" + bytes + "\"}").getBytes(StandardCharsets.UTF_8));
    }
}
