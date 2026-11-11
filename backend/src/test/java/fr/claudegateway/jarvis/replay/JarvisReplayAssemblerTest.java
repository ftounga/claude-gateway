package fr.claudegateway.jarvis.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.jarvis.capture.JarvisAudioCapture;
import fr.claudegateway.jarvis.replay.JarvisReplay.Correlation;
import fr.claudegateway.jarvis.replay.JarvisReplay.Turn;
import fr.claudegateway.jarvis.timing.JarvisTurnTiming;
import fr.claudegateway.jarvis.trace.JarvisListenTraceEvent;

/** F-255 / SF-255-02 — l'assemblage du rejeu : découpage en tours, corrélation exacte ou estimée, messages. */
class JarvisReplayAssemblerTest {

    private static final long P = Instant.parse("2026-11-09T16:22:19Z").toEpochMilli();
    private static final UUID THREAD = UUID.randomUUID();
    private static final UUID WAF = UUID.randomUUID();
    private static final Map<UUID, String> NAMES = Map.of(THREAD, "Jarvis", WAF, "waf");

    private final List<JarvisListenTraceEvent> traces = new ArrayList<>();
    private final List<JarvisTurnTiming> timings = new ArrayList<>();
    private final List<JarvisAudioCapture> captures = new ArrayList<>();
    private final List<AtelierMessage> messages = new ArrayList<>();
    private int seq;

    private static OffsetDateTime at(long ms) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC);
    }

    /** Un événement exact (SF-255-01) : tour, heure serveur. */
    private void exact(String turn, long atMs, String ev, String detail) {
        traces.add(JarvisListenTraceEvent.builder().pageId("page").seq(++seq).atMs(atMs).event(ev).detail(detail)
                .turnId(turn).createdAt(at(P + atMs + 2_000)).occurredAt(at(P + atMs)).build());
    }

    /** Un événement d'avant SF-255-01 : seulement l'heure de page, lot parti {@code delay} ms plus tard. */
    private void legacy(long atMs, String ev, String detail, long delay) {
        traces.add(JarvisListenTraceEvent.builder().pageId("old").seq(++seq).atMs(atMs).event(ev).detail(detail)
                .createdAt(at(P + atMs + delay)).build());
    }

    private AtelierMessage message(UUID workspace, String role, String via, long ms, String content) {
        AtelierMessage m = AtelierMessage.builder().id(UUID.randomUUID()).workspaceId(workspace).role(role).via(via)
                .createdAt(at(ms)).content(content).build();
        messages.add(m);
        return m;
    }

    private List<Turn> assemble() {
        return JarvisReplayAssembler.assemble(new JarvisReplayAssembler.Sources(traces, timings, captures, messages,
                THREAD, NAMES));
    }

    @Test
    @DisplayName("avec identifiants : tours exacts, écoute muette écartée, mesure, capture, sujet d'écran, messages")
    void exactTurns() {
        exact("a", 1_000, "listen_open", null);
        exact("a", 6_000, "listen_end", "voiced=900");
        exact("a", 7_000, "transcript", "chars=31 mode=CONV_STREAM");
        exact("a", 9_000, "brain_done", "ok said=38");
        exact("a", 10_500, "show_refused", "galerie NOT_FOUND");
        exact("b", 11_000, "listen_open", null);
        exact("b", 19_000, "listen_silence", "peak=0.02");
        exact("c", 19_500, "listen_open", null);
        exact("c", 22_000, "stream_fallback", "ouverture_fermee");
        exact("c", 25_000, "listen_end", "voiced=150");
        exact("c", 26_000, "speech_rejected", "empty chars=0 voiced=150");
        traces.add(JarvisListenTraceEvent.builder().pageId("page").seq(0).atMs(0).event("srv_converse")
                .detail("subject=" + WAF + " spec=0").turnId("a").createdAt(at(P + 7_200))
                .occurredAt(at(P + 7_200)).build());
        timings.add(JarvisTurnTiming.builder().turnId("a").createdAt(at(P + 11_000)).listenMode("CONV_STREAM")
                .brain("FAST").firstSoundMs(4_632).build());
        timings.add(JarvisTurnTiming.builder().turnId("c").createdAt(at(P + 26_000)).listenMode("CONV_BATCH")
                .rejectedKind("empty").streamFallback("ouverture_fermee").build());
        UUID capture = UUID.randomUUID();
        captures.add(JarvisAudioCapture.builder().id(capture).turnId("a").createdAt(at(P + 8_000)).seconds(5)
                .streamText("montre-moi la galerie").finalText("Montre-moi la galerie vidéo.").build());
        message(THREAD, "USER", "JARVIS", P + 7_300, "Montre-moi la galerie vidéo.");
        message(THREAD, "ASSISTANT", "JARVIS", P + 7_310, "Voici la galerie des vidéos, Monsieur.");
        AtelierMessage link = message(THREAD, "LINK", null, P + 9_500, "↗ waf — « recherche de la vidéo » · en cours");
        link.setRefWorkspaceId(WAF);
        link.setLinkStatus("EN_COURS");
        message(WAF, "ASSISTANT", "JARVIS", P + 70_000, "🎙 Je vérifie, Monsieur.");

        List<Turn> turns = assemble();

        assertThat(turns).hasSize(2);
        Turn first = turns.get(0);
        assertThat(first.correlation()).isEqualTo(Correlation.IDENTIFIANT);
        assertThat(first.at()).isEqualTo(at(P + 6_000));
        assertThat(first.measured()).isTrue();
        assertThat(first.steps().firstSoundMs()).isEqualTo(4_632);
        assertThat(first.heard().chars()).isEqualTo(31);
        assertThat(first.heard().captureId()).isEqualTo(capture);
        assertThat(first.heard().finalText()).isEqualTo("Montre-moi la galerie vidéo.");
        assertThat(first.heard().userText()).isEqualTo("Montre-moi la galerie vidéo.");
        assertThat(first.reply()).isEqualTo("Voici la galerie des vidéos, Monsieur.");
        assertThat(first.saidChars()).isEqualTo(38);
        assertThat(first.screenSubject().name()).isEqualTo("waf");
        assertThat(first.shows()).singleElement().satisfies(s -> {
            assertThat(s.action()).isEqualTo("galerie");
            assertThat(s.refused()).isTrue();
            assertThat(s.reason()).isEqualTo("NOT_FOUND");
        });
        assertThat(first.delegations()).singleElement().satisfies(d -> {
            assertThat(d.subject().name()).isEqualTo("waf");
            assertThat(d.label()).isEqualTo("recherche de la vidéo");
            assertThat(d.reply()).isEqualTo("🎙 Je vérifie, Monsieur.");
        });
        assertThat(first.events()).anyMatch(e -> e.startsWith("+4.5 s show_refused"));
        Turn second = turns.get(1);
        assertThat(second.heard().rejected()).isEqualTo("empty");
        assertThat(second.streamFallbacks()).containsExactly("ouverture_fermee");
        assertThat(second.reply()).isNull();
    }

    @Test
    @DisplayName("sans identifiants : horloge de page recalée sur les mesures (lots partis 3,3 s plus tard)")
    void estimatedTurns() {
        long delay = 3_300;
        legacy(0, "page_load", null, delay);
        legacy(500, "listen_open", null, delay);
        legacy(20_500, "listen_end", null, delay);
        legacy(21_200, "transcript", "chars=15 mode=CONV_BATCH", delay);
        legacy(25_600, "brain_done", "ok said=167", delay);
        legacy(36_400, "voice_idle", "ms=10810", delay);
        legacy(36_600, "listen_open", null, delay);
        legacy(61_300, "listen_end", null, delay);
        legacy(62_000, "transcript", "chars=0 mode=CONV_BATCH", delay);
        legacy(62_000, "speech_rejected", "empty chars=0 voiced=150", delay);
        timings.add(JarvisTurnTiming.builder().createdAt(at(P + 36_430)).listenMode("CONV_BATCH").brain("FAST")
                .firstSoundMs(5_398).build());
        timings.add(JarvisTurnTiming.builder().createdAt(at(P + 62_050)).listenMode("CONV_BATCH")
                .rejectedKind("empty").build());
        message(THREAD, "USER", "JARVIS", P + 21_400, "Le client CAGIP.");
        message(THREAD, "ASSISTANT", "JARVIS", P + 21_410, "Nous sommes déjà chez CAGIP, Monsieur.");

        assertThat(JarvisReplayAssembler.pageStartMs(traces, timings.stream()
                .map(t -> t.getCreatedAt().toInstant().toEpochMilli()).toList())).isBetween(P, P + 100);
        List<Turn> turns = assemble();

        assertThat(turns).hasSize(2);
        assertThat(turns).allMatch(t -> t.correlation() == Correlation.ESTIMEE);
        assertThat(turns.get(0).heard().userText()).isEqualTo("Le client CAGIP.");
        assertThat(turns.get(0).reply()).isEqualTo("Nous sommes déjà chez CAGIP, Monsieur.");
        assertThat(turns.get(0).steps().firstSoundMs()).isEqualTo(5_398);
        assertThat(turns.get(1).heard().rejected()).isEqualTo("empty");
        assertThat(turns.get(1).measured()).isTrue();
        assertThat(turns.get(1).reply()).isNull();
    }

    @Test
    @DisplayName("la copie déléguée écrite en retard (file d'attente) n'est pas prêtée à un tour sans délégation")
    void lateDelegatedCopyIsNotBorrowed() {
        exact("a", 1_000, "listen_open", null);
        exact("a", 5_000, "listen_end", null);
        exact("a", 6_000, "transcript", "chars=0 mode=CONV_STREAM");
        exact("a", 6_000, "speech_rejected", "empty chars=0 voiced=384");
        message(WAF, "USER", "JARVIS", P + 7_000, "Allons-y.");
        assertThat(assemble()).singleElement().satisfies(t -> assertThat(t.heard().userText()).isNull());
    }

    @Test
    @DisplayName("une parole sans mesure (page rechargée) reste un tour, non mesuré")
    void unmeasuredTurn() {
        exact("a", 1_000, "listen_open", null);
        exact("a", 5_000, "listen_end", null);
        exact("a", 6_000, "transcript", "chars=55 mode=CONV_BATCH");
        assertThat(assemble()).singleElement().satisfies(t -> {
            assertThat(t.measured()).isFalse();
            assertThat(t.steps().firstSoundMs()).isNull();
        });
    }
}
