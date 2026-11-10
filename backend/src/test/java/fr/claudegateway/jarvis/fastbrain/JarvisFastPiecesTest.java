package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.jarvis.pieces.JarvisPieceService;
import fr.claudegateway.jarvis.pieces.Piece;

/** F-237 / SF-237-08 — l'outil {@code pieces} et le bloc {@code ## Pièces} : texte borné, jamais d'autrui. */
class JarvisFastPiecesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final UUID alice = UUID.randomUUID();
    private final UUID subject = UUID.randomUUID();

    private static Piece piece(String id, Piece.Category category, Piece.Kind kind, String title, String caption,
            Integer chantier, Piece.ChantierLink link) {
        return new Piece(id, category, kind, title, caption, null, OffsetDateTime.parse("2026-11-03T10:42:00+01:00"),
                null, chantier, link, null, null, null, Piece.Availability.ONLINE);
    }

    /** F-253 / SF-253-03 — un univers à un client et deux sujets. */
    private fr.claudegateway.jarvis.universe.JarvisUniverse universe(UUID host, UUID videos, UUID empty) {
        return new fr.claudegateway.jarvis.universe.JarvisUniverse(OffsetDateTime.now(), List.of(
                new fr.claudegateway.jarvis.universe.JarvisUniverse.Client(host, "CAGIP", false, false, "ACTIVE", List.of(
                        new fr.claudegateway.jarvis.universe.JarvisUniverse.Subject(videos, "data-platform", false, false,
                                List.of()),
                        new fr.claudegateway.jarvis.universe.JarvisUniverse.Subject(empty, "agenor", false, false,
                                List.of())), List.of(), List.of(), false)), 0);
    }

    @Test
    void allBlockCountsEachSubjectAndLeavesOutEmptyOnes() {
        UUID host = UUID.randomUUID();
        UUID videos = UUID.randomUUID();
        String block = JarvisFastPieces.allBlock(universe(host, videos, UUID.randomUUID()), java.util.Map.of(videos,
                List.of(piece("video:a", Piece.Category.PRODUCTION, Piece.Kind.VIDEO, "A", null, 3, Piece.ChantierLink.MESSAGE),
                        piece("video:b", Piece.Category.PRODUCTION, Piece.Kind.VIDEO, "B", null, 3, Piece.ChantierLink.MESSAGE),
                        piece("proof:c", Piece.Category.PREUVE, Piece.Kind.IMAGE, "C", null, 3, Piece.ChantierLink.MESSAGE))));
        assertThat(block).startsWith("## Pièces de tous les sujets").contains("CAGIP / data-platform : 2 vidéos, 1 preuve")
                .doesNotContain("agenor");
        assertThat(JarvisFastPieces.allBlock(universe(host, videos, UUID.randomUUID()), java.util.Map.of())).isEmpty();
    }

    @Test
    void readAcceptsAClientNameAndSaysWhichSubjectEachPieceComesFrom() throws Exception {
        UUID host = UUID.randomUUID();
        UUID videos = UUID.randomUUID();
        JarvisPieceService service = mock(JarvisPieceService.class);
        Piece video = new Piece("video:a", Piece.Category.PRODUCTION, Piece.Kind.VIDEO, "Cours", null,
                Piece.CaptionSource.TITLE, OffsetDateTime.now(), videos, 3, Piece.ChantierLink.MESSAGE, null, null,
                java.util.Map.of(), Piece.Availability.ONLINE);
        when(service.listForClient(eq(alice), eq(host.toString()), any()))
                .thenReturn(new JarvisPieceService.Result(List.of(video), 1, false));
        String out = new JarvisFastPieces(service).read(alice, null,
                JSON.readTree("{\"client\":\"Kajip\",\"type\":\"video\"}"), universe(host, videos, UUID.randomUUID()));
        assertThat(out).contains("video:a").contains("sujet data-platform");
        assertThat(new JarvisFastPieces(service).read(alice, null, JSON.readTree("{\"client\":\"Orange\"}"),
                universe(host, videos, UUID.randomUUID()))).contains("inconnu");
    }

    @Test
    void blockCountsPerChantierWithInitialNeedAndZeroProofs() {
        String block = JarvisFastPieces.block(List.of(
                piece("need:1", Piece.Category.ENTREE, Piece.Kind.TEXT, "Besoin", null, 3, Piece.ChantierLink.MESSAGE),
                piece("dep:a", Piece.Category.ENTREE, Piece.Kind.IMAGE, "Capture", null, 3, Piece.ChantierLink.WINDOW),
                piece("video:v@v1", Piece.Category.PRODUCTION, Piece.Kind.VIDEO, "Démo", null, 3,
                        Piece.ChantierLink.MESSAGE),
                piece("video:w@v1", Piece.Category.PRODUCTION, Piece.Kind.VIDEO, "Démo 2", null, 3,
                        Piece.ChantierLink.MESSAGE),
                piece("proof:p", Piece.Category.PREUVE, Piece.Kind.IMAGE, "Santé", null, 4, Piece.ChantierLink.MESSAGE),
                piece("img:i", Piece.Category.PRODUCTION, Piece.Kind.IMAGE, "Logo", null, null,
                        Piece.ChantierLink.NONE)));
        assertThat(block).isEqualTo("""
                ## Pièces
                chantier 4 : 0 entrée, 1 preuve
                chantier 3 : besoin initial, 1 entrée, 0 preuve, 2 vidéos
                sujet (sans chantier) : 0 entrée, 0 preuve, 1 image""");
        assertThat(JarvisFastPieces.block(List.of())).isEmpty();
    }

    @Test
    void blockNeverExceedsItsCeiling() {
        List<Piece> many = new ArrayList<>();
        for (int i = 1; i <= 80; i++) {
            many.add(piece("need:" + i, Piece.Category.ENTREE, Piece.Kind.TEXT, "B", null, i,
                    Piece.ChantierLink.MESSAGE));
        }
        assertThat(JarvisFastPieces.block(many).length()).isLessThanOrEqualTo(JarvisFastPieces.MAX_BLOCK);
    }

    @Test
    void readFormatsLinesFiltersBySearchAndSaysWhenTheLinkIsDeduced() throws Exception {
        JarvisPieceService service = mock(JarvisPieceService.class);
        when(service.list(eq(alice), eq(subject), any())).thenReturn(new JarvisPieceService.Result(List.of(
                piece("video:v@v2", Piece.Category.PRODUCTION, Piece.Kind.VIDEO, "Démo du déploiement",
                        "Le déploiement sur EKS", 3, Piece.ChantierLink.WINDOW),
                piece("dep:a", Piece.Category.ENTREE, Piece.Kind.IMAGE, "Capture 502", null, 3,
                        Piece.ChantierLink.MESSAGE)), 2, false));
        String out = new JarvisFastPieces(service).read(alice, subject, JSON.readTree("""
                {"sujet":"","chantier":null,"categorie":"","type":"","recherche":"déploiement"}"""));
        assertThat(out).startsWith("1 pièce\n")
                .contains("video:v@v2 | vidéo | « Démo du déploiement » | Le déploiement sur EKS")
                .contains("chantier 3 (d'après la date)")
                .doesNotContain("Capture 502");
    }

    @Test
    void anotherAccountsSubjectIsUnknownAndAFailureNeverThrows() throws Exception {
        JarvisPieceService service = mock(JarvisPieceService.class);
        UUID foreign = UUID.randomUUID();
        when(service.list(eq(alice), eq(foreign), any())).thenThrow(new WorkspaceNotFoundException("x"));
        when(service.list(eq(alice), eq(subject), any())).thenThrow(new IllegalStateException("db"));
        JarvisFastPieces pieces = new JarvisFastPieces(service);
        assertThat(pieces.read(alice, subject, JSON.readTree("{\"sujet\":\"" + foreign + "\"}")))
                .isEqualTo(JarvisFastPieces.UNKNOWN_SUBJECT);
        assertThat(pieces.read(alice, subject, JSON.readTree("{\"sujet\":\"pas-un-uuid\"}")))
                .isEqualTo(JarvisFastPieces.UNKNOWN_SUBJECT);
        assertThat(pieces.read(alice, subject, JSON.readTree("{}"))).isEqualTo(JarvisFastPieces.FAILED);
        assertThat(pieces.block(alice, subject)).isEmpty();
        assertThat(pieces.block(alice, null)).isEmpty();
    }
}
