package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.pieces.JarvisPieceService;
import fr.claudegateway.jarvis.pieces.Piece;

/**
 * F-272 / SF-272-02 — les ordres d'écran vérifiés avant que le modèle parle (démo du 17/11 : « Voici… » puis
 * « Pardon » de l'écran, NOT_FOUND et NOT_APPLICABLE).
 */
class JarvisShowVerifierTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final UUID alice = UUID.randomUUID();
    private final UUID terminal = UUID.randomUUID();
    private final UUID dataPlatform = UUID.randomUUID();
    private final UUID bobSubject = UUID.randomUUID();
    private JarvisPieceService pieces;
    private JarvisShowVerifier verifier;

    @BeforeEach
    void setUp() {
        pieces = mock(JarvisPieceService.class);
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        when(workspaces.findByIdAndUserId(any(), any())).thenReturn(Optional.empty());
        when(workspaces.findByIdAndUserId(terminal, alice))
                .thenReturn(Optional.of(Workspace.builder().id(terminal).name("Terminal du poste").build()));
        when(workspaces.findByIdAndUserId(dataPlatform, alice))
                .thenReturn(Optional.of(Workspace.builder().id(dataPlatform).name("data-platform").build()));
        when(pieces.listFresh(alice, terminal)).thenReturn(List.of(piece("page:p1", Piece.Kind.PAGE, terminal)));
        when(pieces.listFresh(alice, dataPlatform)).thenReturn(List.of(
                piece("diag:old", Piece.Kind.ARCHITECTURE, dataPlatform, "2026-11-01T10:00:00Z"),
                piece("diag:a1", Piece.Kind.ARCHITECTURE, dataPlatform, "2026-11-10T10:00:00Z"),
                piece("proof:b2", Piece.Kind.IMAGE, dataPlatform, "2026-11-10T11:00:00Z")));
        when(pieces.listFresh(alice, bobSubject)).thenThrow(new RuntimeException("404"));
        verifier = new JarvisShowVerifier(pieces, workspaces);
    }

    @Test
    @DisplayName("démo 00:01:22 — pièce lue dans data-platform, écran sur un autre sujet : relayée avec le bon sujet")
    void namedPieceFoundInTheReadSubject() throws Exception {
        JarvisShowVerifier.Verified v = verify("{\"action\":\"piece\",\"pieces\":[\"diag:a1\"],\"raison\":\"demande\"}",
                terminal, dataPlatform, null);

        assertThat(v.refused()).isFalse();
        assertThat(v.commands()).hasSize(1);
        assertThat(v.commands().get(0).pieces()).containsExactly("diag:a1");
        assertThat(v.commands().get(0).scope().subjectId()).isEqualTo(dataPlatform.toString());
        assertThat(v.toolResult()).isEqualTo("ok");
    }

    @Test
    @DisplayName("pièce inexistante : rien de relayé, refus avec les pièces disponibles du même genre")
    void unknownPieceIsRefusedWithCandidates() throws Exception {
        JarvisShowVerifier.Verified v = verify("{\"action\":\"piece\",\"pieces\":[\"diag:zz\"],"
                + "\"portee\":{\"sujet\":\"" + dataPlatform + "\"},\"raison\":\"demande\"}", terminal, null, null);

        assertThat(v.refused()).isTrue();
        assertThat(v.commands()).isEmpty();
        assertThat(v.toolResult()).startsWith("refusé").contains("« data-platform »").contains("diag:a1")
                .doesNotContain("proof:b2");
    }

    @Test
    @DisplayName("démo 00:03:00 — « en 3D » sans schéma ouvert : le plus récent est ouvert d'abord, puis la vue 3D")
    void viewOpensTheLatestArchitectureFirst() throws Exception {
        JarvisShowVerifier.Verified v = verify("{\"action\":\"vue\",\"vue\":\"3d\",\"raison\":\"demande\"}",
                dataPlatform, null, "## Écran\nÉcran : univers");

        assertThat(v.refused()).isFalse();
        assertThat(v.commands()).extracting(JarvisShowCommand::action).containsExactly("piece", "vue");
        assertThat(v.commands().get(0).pieces()).containsExactly("diag:a1");
        assertThat(v.commands().get(0).scope().subjectId()).isEqualTo(dataPlatform.toString());
        assertThat(v.commands().get(1).view()).isEqualTo("3d");
        assertThat(v.toolResult()).contains("ouvert").contains("vue 3d");
    }

    @Test
    @DisplayName("« en 3D » avec un schéma déjà ouvert (ligne Architecture du bloc Écran) : relayé tel quel")
    void viewWithAnOpenArchitecturePasses() throws Exception {
        JarvisShowVerifier.Verified v = verify("{\"action\":\"vue\",\"vue\":\"3d\",\"raison\":\"demande\"}",
                dataPlatform, null, "## Écran\nGalerie : Pièces (1 pièce) ; sélection : 1\nArchitecture « x » ; focus : aucun");

        assertThat(v.commands()).extracting(JarvisShowCommand::action).containsExactly("vue");
        assertThat(v.toolResult()).isEqualTo("ok");
    }

    @Test
    @DisplayName("« en 3D » sans aucun schéma : refus « propose d'en dessiner un »")
    void viewWithoutArchitectureIsRefused() throws Exception {
        JarvisShowVerifier.Verified v = verify("{\"action\":\"vue\",\"vue\":\"3d\",\"raison\":\"demande\"}",
                terminal, null, null);

        assertThat(v.refused()).isTrue();
        assertThat(v.toolResult()).contains("aucun schéma d'architecture").contains("dessiner");
    }

    @Test
    @DisplayName("galerie d'architectures d'un sujet qui n'en a pas : « rien à montrer » ; sans sujet : refus")
    void emptyScopedGalleryIsRefused() throws Exception {
        JarvisShowVerifier.Verified none = verify("{\"action\":\"galerie\",\"portee\":{\"type\":\"architecture\"},"
                + "\"raison\":\"demande\"}", terminal, null, null);
        JarvisShowVerifier.Verified some = verify("{\"action\":\"galerie\",\"portee\":{\"type\":\"architecture\"},"
                + "\"raison\":\"demande\"}", dataPlatform, null, null);
        JarvisShowVerifier.Verified nobody = verify("{\"action\":\"galerie\",\"portee\":{\"categorie\":\"preuves\"},"
                + "\"raison\":\"demande\"}", null, null, null);

        assertThat(none.refused()).isTrue();
        assertThat(none.toolResult()).startsWith("rien à montrer").contains("« Terminal du poste »");
        assertThat(some.refused()).isFalse();
        assertThat(nobody.refused()).isTrue();
        assertThat(nobody.toolResult()).contains("aucun sujet en vue");
    }

    @Test
    @DisplayName("isolation : un sujet d'un autre compte dans la portée n'est jamais lu, l'ordre est refusé")
    void otherAccountSubjectIsRefused() throws Exception {
        JarvisShowVerifier.Verified v = verify("{\"action\":\"piece\",\"pieces\":[\"diag:a1\"],"
                + "\"portee\":{\"sujet\":\"" + bobSubject + "\"},\"raison\":\"demande\"}", null, null, null);

        assertThat(v.refused()).isTrue();
        assertThat(v.commands()).isEmpty();
        assertThat(v.toolResult()).doesNotContain("diag:a1");
    }

    @Test
    @DisplayName("d'initiative, la pièce d'un autre sujet que celui en vue n'est pas cherchée ailleurs")
    void appuiStaysInTheSubjectInView() throws Exception {
        JarvisShowVerifier.Verified v = verify("{\"action\":\"piece\",\"pieces\":[\"diag:a1\"],\"raison\":\"appui\"}",
                terminal, dataPlatform, null);

        assertThat(v.refused()).isTrue();
    }

    @Test
    @DisplayName("courriel et agenda : jamais vérifiés ici ; version d'une page : reconnue")
    void outsideIndexAndVersions() throws Exception {
        assertThat(verify("{\"action\":\"piece\",\"pieces\":[\"courriel:abc\"],\"raison\":\"demande\"}", terminal,
                null, null).refused()).isFalse();
        assertThat(verify("{\"action\":\"piece\",\"pieces\":[\"page:p1@v2\"],\"raison\":\"demande\"}", terminal,
                null, null).refused()).isFalse();
    }

    private JarvisShowVerifier.Verified verify(String json, UUID current, UUID read, String screen) throws Exception {
        JsonNode input = JSON.readTree(json);
        JarvisShowCommand.Parsed parsed = JarvisShowCommand.parse(input, JarvisShowCommand.MAX_APPUI);
        return verifier.verify(parsed, alice, current, read, screen);
    }

    private static Piece piece(String id, Piece.Kind kind, UUID subject) {
        return piece(id, kind, subject, "2026-11-10T10:00:00Z");
    }

    private static Piece piece(String id, Piece.Kind kind, UUID subject, String at) {
        return new Piece(id, Piece.Category.PRODUCTION, kind, "Titre " + id, null, null, OffsetDateTime.parse(at),
                subject, null, Piece.ChantierLink.NONE, null, null, Map.of(), Piece.Availability.ONLINE);
    }
}
