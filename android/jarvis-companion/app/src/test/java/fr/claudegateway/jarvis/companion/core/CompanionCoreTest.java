package fr.claudegateway.jarvis.companion.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/** Le cœur des annonces (F-232 / SF-232-03) : JSON, client, fenêtre de réponse, attente croissante, lien. */
public class CompanionCoreTest {

    @Test
    public void miniJsonLitLesReponsesDeLaGateway() {
        Object o = MiniJson.parse("{\"a\":[1,true,null,\"\\u00e9t\\u00e9\\n\"],\"b\":{\"c\":-2.5e1}}");
        Map<?, ?> m = (Map<?, ?>) o;
        List<?> a = (List<?>) m.get("a");
        assertEquals(1.0, a.get(0));
        assertEquals(Boolean.TRUE, a.get(1));
        assertNull(a.get(2));
        assertEquals("été\n", a.get(3));
        assertEquals(-25.0, ((Map<?, ?>) m.get("b")).get("c"));
        assertEquals("\"a\\\"b\\\\c\\n\"", MiniJson.quote("a\"b\\c\n"));
        try {
            MiniJson.parse("{\"a\":");
            fail("JSON tronqué accepté");
        } catch (IllegalArgumentException expected) {
            // attendu
        }
    }

    @Test
    public void lesAnnoncesSontLuesSansLesElementsVides() throws IOException {
        CompanionApi.Feed feed = CompanionApi.parseFeed("{\"cursor\":\"2026-11-01T12:00:00Z\",\"items\":["
                + "{\"id\":\"1\",\"kind\":\"TURN\",\"text\":\"C'est fait.\",\"link\":\"/jarvis?sujet=x\",\"at\":\"…\"},"
                + "{\"id\":\"2\",\"kind\":\"TURN\",\"text\":\"\"}]}");
        assertEquals("2026-11-01T12:00:00Z", feed.cursor);
        assertEquals(1, feed.items.size());
        assertEquals("C'est fait.", feed.items.get(0).text);
        assertEquals("/jarvis?sujet=x", feed.items.get(0).link);
    }

    @Test
    public void laFenetreDeReponseNeVautQuHuitSecondesEtQuUnMot() {
        ReplyWindow window = new ReplyWindow();
        assertNull("fermée au départ", window.onWord(0));
        window.open(1_000, "/jarvis?sujet=1");
        assertTrue(window.isOpen(8_999));
        assertEquals("/jarvis?sujet=1", window.onWord(5_000));
        assertNull("un seul mot suffit", window.onWord(5_100));
        window.open(10_000, "/jarvis");
        assertFalse(window.isOpen(18_000));
        assertNull(window.onWord(18_000));
    }

    @Test
    public void lAttenteCroitJusquACinqMinutes() {
        Backoff backoff = new Backoff();
        assertEquals(5_000, backoff.next());
        assertEquals(10_000, backoff.next());
        for (int i = 0; i < 10; i++) {
            backoff.next();
        }
        assertEquals(Backoff.MAX_MS, backoff.next());
        backoff.reset();
        assertEquals(5_000, backoff.next());
    }

    @Test
    public void leLienDUneAnnonceOuvreJarvisEnModeCompagnon() {
        String g = "https://portal.example";
        assertEquals(g + "/jarvis?sujet=42&source=compagnon&compagnon=3", JarvisLink.announcementUrl(g, "/jarvis?sujet=42", 3));
        assertEquals(g + "/atelier/x?source=compagnon&compagnon=3", JarvisLink.announcementUrl(g, "/atelier/x", 3));
        assertEquals(JarvisLink.jarvisUrl(g, 3), JarvisLink.announcementUrl(g, "//ailleurs/x", 3));
        assertEquals(JarvisLink.jarvisUrl(g, 3), JarvisLink.announcementUrl(g, "https://ailleurs/x", 3));
        assertEquals(JarvisLink.jarvisUrl(g, 3), JarvisLink.announcementUrl(g, null, 3));
    }

    @Test
    public void leClientAppaireEtLitLesAnnoncesAvecSonJeton() throws Exception {
        AtomicReference<String> seenToken = new AtomicReference<>();
        AtomicReference<String> seenBody = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            Thread loop = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket s = server.accept()) {
                        Request r = Request.read(s);
                        if (r.path.startsWith("/api/companion/pair")) {
                            seenBody.set(r.body);
                            boolean ok = r.body.contains("ABCDEFGH");
                            reply(s, ok ? 200 : 400, ok ? "{\"deviceId\":\"d1\",\"token\":\"jcp_x\",\"name\":\"P\"}"
                                    : "{\"error\":\"companion_pairing_invalid\",\"message\":\"…\"}");
                        } else {
                            seenToken.set(r.headers.get("x-companion-token"));
                            if (!"jcp_x".equals(seenToken.get())) {
                                reply(s, 401, "{\"error\":\"companion_token_invalid\"}");
                            } else {
                                reply(s, 200, "{\"cursor\":\"c2\",\"items\":[{\"id\":\"1\",\"kind\":\"ALERT\","
                                        + "\"text\":\"Agenor : AG-2.\",\"link\":\"/jarvis?alerte=1\"}]}");
                            }
                        }
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            loop.setDaemon(true);
            loop.start();
            String gateway = "http://127.0.0.1:" + server.getLocalPort();

            CompanionApi.Paired paired = CompanionApi.pair(gateway, "ABCDEFGH", "Pixel \"8\"");
            assertEquals("jcp_x", paired.token);
            assertTrue(seenBody.get().contains("\"name\":\"Pixel \\\"8\\\"\""));
            try {
                CompanionApi.pair(gateway, "MAUVAIS1", "P");
                fail("code refusé accepté");
            } catch (CompanionApi.Refused refused) {
                assertEquals("companion_pairing_invalid", refused.code);
            }

            CompanionApi.Feed feed = CompanionApi.announcements(gateway, "jcp_x", "c1");
            assertEquals("c2", feed.cursor);
            assertEquals("Agenor : AG-2.", feed.items.get(0).text);
            try {
                CompanionApi.announcements(gateway, "jcp_revoque", null);
                fail("jeton révoqué accepté");
            } catch (CompanionApi.Unauthorized expected) {
                assertEquals("jcp_revoque", seenToken.get());
            }
        }
    }

    /** Une requête HTTP/1.1 minimale : ligne, en-têtes (clés en minuscules), corps selon Content-Length. */
    private static final class Request {
        String path;
        final Map<String, String> headers = new HashMap<>();
        String body = "";

        static Request read(Socket s) throws IOException {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            Request r = new Request();
            String line = in.readLine();
            r.path = line == null ? "" : line.split(" ")[1];
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                r.headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            int length = Integer.parseInt(r.headers.getOrDefault("content-length", "0"));
            char[] buf = new char[length];
            int read = 0;
            while (read < length) {
                int n = in.read(buf, read, length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            // Corps en ASCII dans ces tests : caractères = octets.
            r.body = new String(buf, 0, read);
            return r;
        }
    }

    private static void reply(Socket s, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length
                + "\r\nConnection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }
}
