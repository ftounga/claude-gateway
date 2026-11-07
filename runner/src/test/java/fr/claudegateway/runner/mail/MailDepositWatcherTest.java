package fr.claudegateway.runner.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

/** F-249 / SF-249-02 — le dossier des mails : envoi unique, rangement, curseur, refus, occupation, filtres. */
class MailDepositWatcherTest {

    @TempDir Path root;

    private final Instant now = Instant.parse("2026-11-07T18:00:00Z");
    private final List<String> uploaded = new ArrayList<>();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private int status = 200;
    private MailDepositWatcher watcher;

    @BeforeEach
    void setUp() {
        watcher = new MailDepositWatcher(root, (name, content) -> {
            uploaded.add(name + ":" + new String(content, StandardCharsets.UTF_8));
            return new MailDepositWatcher.Response(status);
        }, () -> now, busy::get, s -> { });
        assertTrue(watcher.prepare());
    }

    private Path file(String name, String content, Instant modified) throws IOException {
        Path p = watcher.dir().resolve(name);
        Files.writeString(p, content);
        Files.setLastModifiedTime(p, FileTime.from(modified));
        return p;
    }

    private Path month() {
        return watcher.dir().resolve("envoyes").resolve(now.atZone(ZoneId.systemDefault()).toString().substring(0, 7));
    }

    @Test
    @DisplayName("Trois mails ⇒ trois envois et trois rangements ; le passage suivant n'envoie rien")
    void nominal() throws IOException {
        file("a.msg", "A", now.minusSeconds(600));
        file("b.EML", "B", now.minusSeconds(500));
        file("c.msg", "C", now.minusSeconds(400));
        MailDepositWatcher.Report r = watcher.runOnce();
        assertEquals(3, r.sent());
        assertEquals(List.of("a.msg:A", "b.EML:B", "c.msg:C"), uploaded);
        assertTrue(Files.exists(month().resolve("a.msg")));
        assertTrue(Files.exists(month().resolve("b.EML")));
        assertFalse(Files.exists(watcher.dir().resolve("a.msg")));
        uploaded.clear();
        assertEquals(0, watcher.runOnce().sent());
        assertTrue(uploaded.isEmpty());
        assertTrue(Files.exists(watcher.dir().resolve("LISEZ-MOI.txt")));
    }

    @Test
    @DisplayName("Même nom déjà rangé ⇒ suffixe (2)")
    void collision() throws IOException {
        file("a.msg", "A", now.minusSeconds(600));
        watcher.runOnce();
        file("a.msg", "A bis", now.minusSeconds(300));
        watcher.runOnce();
        assertTrue(Files.exists(month().resolve("a (2).msg")));
    }

    @Test
    @DisplayName("Rangement impossible ⇒ empreinte notée, jamais renvoyé")
    void lockedFileIsNotResent() throws IOException {
        file("a.msg", "A", now.minusSeconds(600));
        // Le dossier « envoyes » est un fichier : le rangement échoue, comme un fichier verrouillé par Outlook.
        Files.writeString(watcher.dir().resolve("envoyes"), "x");
        assertEquals(1, watcher.runOnce().sent());
        assertTrue(Files.exists(watcher.dir().resolve("a.msg")));
        assertEquals(MailDepositWatcher.sha256("A".getBytes(StandardCharsets.UTF_8)), Files.readString(watcher.dir().resolve(".envoyes")).strip());
        uploaded.clear();
        watcher.runOnce();
        assertTrue(uploaded.isEmpty());
    }

    @Test
    @DisplayName("415 ⇒ refuses/ ; 503 ⇒ laissé en place et passage arrêté")
    void refusedAndDeferred() throws IOException {
        file("a.msg", "A", now.minusSeconds(600));
        status = 415;
        assertEquals(1, watcher.runOnce().refused());
        assertTrue(Files.exists(watcher.dir().resolve("refuses").resolve("a.msg")));
        file("b.msg", "B", now.minusSeconds(600));
        file("c.msg", "C", now.minusSeconds(500));
        status = 503;
        uploaded.clear();
        MailDepositWatcher.Report r = watcher.runOnce();
        assertTrue(r.stopped());
        assertEquals(1, uploaded.size());
        assertTrue(Files.exists(watcher.dir().resolve("b.msg")));
        assertTrue(Files.exists(watcher.dir().resolve("c.msg")));
    }

    @Test
    @DisplayName("Runner occupé ⇒ rien ; activité apparue en cours ⇒ arrêt avant le fichier suivant")
    void yieldsToWork() throws IOException {
        file("a.msg", "A", now.minusSeconds(600));
        file("b.msg", "B", now.minusSeconds(500));
        busy.set(true);
        assertTrue(watcher.runOnce().skipped());
        assertTrue(uploaded.isEmpty());
        busy.set(false);
        MailDepositWatcher w = new MailDepositWatcher(root, (name, content) -> {
            uploaded.add(name);
            busy.set(true); // une commande du PO arrive pendant l'envoi
            return new MailDepositWatcher.Response(200);
        }, () -> now, busy::get, s -> { });
        MailDepositWatcher.Report r = w.runOnce();
        assertEquals(1, r.sent());
        assertTrue(r.stopped());
        assertTrue(Files.exists(watcher.dir().resolve("b.msg")));
    }

    @Test
    @DisplayName("Filtres : récent (< 1 min), caché, vide, autre extension, trop lourd")
    void filters() throws IOException {
        file("recent.msg", "R", now.minusSeconds(20));
        file(".cache.msg", "H", now.minusSeconds(600));
        file("vide.msg", "", now.minusSeconds(600));
        file("devis.pdf", "P", now.minusSeconds(600));
        Path big = watcher.dir().resolve("gros.msg");
        try (var out = Files.newOutputStream(big)) {
            out.write(new byte[(int) MailDepositWatcher.MAX_BYTES + 1]);
        }
        Files.setLastModifiedTime(big, FileTime.from(now.minusSeconds(600)));
        MailDepositWatcher.Report r = watcher.runOnce();
        assertTrue(uploaded.isEmpty());
        assertEquals(1, r.refused());
        assertTrue(Files.exists(watcher.dir().resolve("refuses").resolve("gros.msg")));
        assertTrue(Files.exists(watcher.dir().resolve("recent.msg")));
    }

    @Test
    @DisplayName("Lot : 20 fichiers par passage au plus")
    void batch() throws IOException {
        for (int i = 0; i < 25; i++) {
            file(String.format("m%02d.eml", i), "M" + i, now.minusSeconds(1000 - i));
        }
        MailDepositWatcher.Report r = watcher.runOnce();
        assertEquals(20, r.sent());
        assertEquals(5, r.deferred());
        assertEquals(5, watcher.runOnce().sent());
    }

    @Test
    @DisplayName("HTTP réel : jeton, nom neutre (l'objet du mail ne part pas en en-tête), corps exact")
    void realHttp() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> seen = new AtomicReference<>();
        server.createContext("/runner/mail-deposit", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            seen.set(exchange.getRequestHeaders().getFirst("X-Runner-Token") + "|"
                    + exchange.getRequestHeaders().getFirst("X-File-Name") + "|"
                    + new String(body, StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            MailDepositWatcher.Uploader http = MailDepositWatcher.http(HttpClient.newHttpClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/runner/mail-deposit", () -> "cgr_jeton");
            assertEquals(200, http.upload("RE Alarme Fortec confidentiel.msg", "CONTENU".getBytes(StandardCharsets.UTF_8))
                    .status());
            assertEquals("cgr_jeton|mail.msg|CONTENU", seen.get());
            assertEquals(401, MailDepositWatcher.http(HttpClient.newHttpClient(), "http://127.0.0.1:1/x", () -> null)
                    .upload("a.msg", new byte[] {1}).status());
        } finally {
            server.stop(0);
        }
    }
}
