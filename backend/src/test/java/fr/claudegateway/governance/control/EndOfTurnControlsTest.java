package fr.claudegateway.governance.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;
import fr.claudegateway.governance.GovernanceMapDestinations;
import fr.claudegateway.governance.juge.JugeAvis;
import fr.claudegateway.governance.juge.JugeIndependantService;
import fr.claudegateway.governance.juge.JugeMemo;
import fr.claudegateway.governance.juge.JugeVerdict;

/**
 * Les deux contrôles de fin de tour du premier paquet (F-52 / SF-52-02, complétés par F-93 /
 * SF-93-01).
 *
 * <p>Ce que ces tests protègent : que le juge <b>alerte</b> au lieu de laisser passer quand il ne
 * comprend pas, qu'il nomme ce qu'il faut promouvoir <b>et où</b>, qu'une promotion <b>muette sur sa
 * destination</b> soit refusée (F-93) — et que la <b>dette ne bloque plus</b> la clôture (F-125 /
 * SF-125-04 : la carte se tient en silence).</p>
 */
class EndOfTurnControlsTest {

    private final GovernanceMapDestinations destinations = mock(GovernanceMapDestinations.class);
    private final JugeFinDeTourControl juge = new JugeFinDeTourControl(destinations);
    private final PromotionDetteBloquanteControl dette =
            new PromotionDetteBloquanteControl(destinations);
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(destinations.pathsForProject(any(), any()))
                .thenReturn(List.of("README.md", "acces.md", "plateformes.md"));
    }

    private AtelierCheckpointContext reply(String text) {
        return AtelierCheckpointContext.endOfTurn(userId, workspaceId, text, List.of());
    }

    /** Fin de tour portant les chemins réellement écrits pendant le tour (F-125 / SF-125-02). */
    private AtelierCheckpointContext replyHavingWritten(String text, String... writtenPaths) {
        return AtelierCheckpointContext.endOfTurn(userId, workspaceId, text, List.of(writtenPaths));
    }

    // -------------------------------------------------------------------- identité

    @Test
    @DisplayName("les deux se déclarent sur la fin de tour, avec un identifiant stable")
    void bothDeclareTheEndOfTurn() {
        assertThat(juge.id()).isEqualTo("juge-fin-de-tour");
        assertThat(dette.id()).isEqualTo("promotion-dette-bloquante");
        assertThat(juge.kind()).isEqualTo(AtelierCheckpointKind.END_OF_TURN);
        assertThat(dette.kind()).isEqualTo(AtelierCheckpointKind.END_OF_TURN);
        assertThat(juge.description()).isNotBlank();
        assertThat(dette.description()).isNotBlank();
    }

    // ------------------------------------------------------------------------ juge

    @Test
    @DisplayName("sans marqueur, le juge ALERTE et rend la forme exacte attendue")
    void withoutAMarkerTheJudgeAlerts() {
        AtelierCheckpointVerdict verdict = juge.evaluate(reply("C'est fait."));

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.correction()).contains(FinDeTourMarker.FORME).contains("promu");
    }

    @Test
    @DisplayName("un marqueur illisible alerte comme un marqueur absent")
    void anUnreadableMarkerAlertsToo() {
        assertThat(juge.evaluate(reply("<!-- fin-de-tour: promotion=aucune; dette=beaucoup -->"))
                .blocked()).isTrue();
    }

    @Test
    @DisplayName("ce qui est déclaré durable et non promu bloque, et le refus NOMME LA DESTINATION")
    void whatIsDurableAndNotPromotedBlocks() {
        AtelierCheckpointVerdict verdict = juge.evaluate(reply(
                "<!-- fin-de-tour: promotion=le cluster atlas, le VPN client; dette=0 -->"));

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.correction())
                .contains("le cluster atlas")
                .contains("le VPN client")
                // C'est le défaut que F-93 corrige : le refus disait « la carte du projet ».
                .contains("acces.md")
                .contains("plateformes.md")
                .contains("PLAN-ACTION.md")
                .contains("STATE.md")
                .contains("promotion=aucune");
    }

    @Test
    @DisplayName("carte non listable : le juge reste correct, avec la formulation générique")
    void anUnlistableMapStillGivesAUsableRefusal() {
        when(destinations.pathsForProject(any(), any())).thenReturn(List.of());

        AtelierCheckpointVerdict verdict =
                juge.evaluate(reply("<!-- fin-de-tour: promotion=le bastion; dette=0 -->"));

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.correction()).contains(GovernanceMapDestinations.GENERIC);
    }

    @Test
    @DisplayName("rien à promouvoir : le juge passe, même avec de la dette — ce n'est pas son rôle")
    void nothingToPromotePasses() {
        assertThat(juge.evaluate(reply("Fait.\n<!-- fin-de-tour: promotion=aucune; dette=3 -->"))
                .blocked()).isFalse();
    }

    @Test
    @DisplayName("rien à promouvoir : la carte n'est même pas listée — on ne paie pas pour rien")
    void nothingToPromoteCostsNothing() {
        juge.evaluate(reply("<!-- fin-de-tour: promotion=aucune; dette=0 -->"));

        verify(destinations, never()).pathsForProject(any(), any());
    }

    @Test
    @DisplayName("un contexte absent ne fait jamais lever le juge")
    void anAbsentContextIsSurvived() {
        assertThat(juge.evaluate(null).blocked()).isTrue();
        assertThat(juge.evaluate(AtelierCheckpointContext.endOfTurn(null, null, null, List.of()))
                .blocked()).isTrue();
        assertThat(juge.evaluate(AtelierCheckpointContext.endOfTurn(null, null,
                "<!-- fin-de-tour: promotion=un serveur; dette=0 -->", List.of())).blocked())
                .isTrue();
    }

    // ------------------------------------------------------- promotion sans destination

    @Test
    @DisplayName("une promotion qui ne dit PAS OÙ est refusée, avec la forme exacte à reprendre")
    void aPromotionWithoutADestinationIsRefused() {
        AtelierCheckpointVerdict verdict = dette.evaluate(
                reply("<!-- fin-de-tour: promotion=aucune; promu=cluster atlas; dette=0 -->"));

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.correction())
                .contains("cluster atlas")
                .contains("promu=cluster atlas -> <fichier>")
                .contains("acces.md")
                .contains("- [x]")
                .contains("STATE.md");
    }

    @Test
    @DisplayName("une destination étrangère à la carte est refusée, et les vraies sont nommées")
    void aForeignDestinationIsRefused() {
        AtelierCheckpointVerdict verdict = dette.evaluate(reply(
                "<!-- fin-de-tour: promotion=aucune; promu=cluster atlas -> notes.md; dette=0 -->"));

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.correction()).contains("notes.md").contains("plateformes.md");
    }

    @Test
    @DisplayName("une destination de la carte passe — flèche, chemin relatif ou casse indifférents")
    void aRealDestinationPasses() {
        assertThat(dette.evaluate(reply(
                "<!-- fin-de-tour: promotion=aucune; promu=cluster atlas -> plateformes.md; dette=0 -->"))
                .blocked()).isFalse();
        assertThat(dette.evaluate(reply(
                "<!-- fin-de-tour: promotion=aucune; promu=VPN → ./Acces.MD; dette=0 -->"))
                .blocked()).isFalse();
        assertThat(dette.evaluate(reply(
                "<!-- fin-de-tour: promotion=aucune; promu=contact réseau vers README.md; dette=0 -->"))
                .blocked()).isFalse();
    }

    @Test
    @DisplayName("carte non listable : aucune destination n'est jugée étrangère")
    void withoutAMapNoDestinationIsForeign() {
        when(destinations.pathsForProject(any(), any())).thenReturn(List.of());

        assertThat(dette.evaluate(reply(
                "<!-- fin-de-tour: promotion=aucune; promu=cluster -> reseau.md; dette=0 -->"))
                .blocked()).isFalse();
    }

    @Test
    @DisplayName("une promotion muette est refusée AVANT la dette : l'ordre est le message")
    void theMuteDestinationComesFirst() {
        AtelierCheckpointVerdict verdict = dette.evaluate(
                reply("<!-- fin-de-tour: promotion=aucune; promu=cluster atlas; dette=4 -->"));

        assertThat(verdict.correction()).contains("sans dire où").doesNotContain("4");
    }

    // ------------------------------------ F-125 / SF-125-02 : le suivi s'appuie sur les écritures

    @Test
    @DisplayName("promotion sans destination MAIS écriture réelle dans la carte : ne bloque plus")
    void aMutePromotionBackedByARealWriteIsTolerated() {
        AtelierCheckpointVerdict verdict = dette.evaluate(replyHavingWritten(
                "<!-- fin-de-tour: promotion=aucune; promu=libellé tronqué; dette=0 -->",
                "plateformes.md"));

        assertThat(verdict.blocked()).isFalse();
    }

    @Test
    @DisplayName("écriture réelle par un chemin relatif : le nom du fichier suffit à recouper la carte")
    void aRealWriteByRelativePathStillMatchesTheMap() {
        assertThat(dette.evaluate(replyHavingWritten(
                "<!-- fin-de-tour: promotion=aucune; promu=cluster atlas -> notes.md; dette=0 -->",
                "./Plateformes.MD")).blocked()).isFalse();
    }

    @Test
    @DisplayName("promotion sans destination SANS écriture réelle : le refus « dis où » est préservé")
    void aMutePromotionWithoutAnyWriteStillBlocks() {
        assertThat(dette.evaluate(reply(
                "<!-- fin-de-tour: promotion=aucune; promu=cluster atlas; dette=0 -->"))
                .blocked()).isTrue();
    }

    @Test
    @DisplayName("écriture réelle hors de la carte : la tolérance ne se déclenche pas")
    void aWriteOutsideTheMapDoesNotTrigger() {
        assertThat(dette.evaluate(replyHavingWritten(
                "<!-- fin-de-tour: promotion=aucune; promu=cluster atlas; dette=0 -->",
                "src/Main.java")).blocked()).isTrue();
    }

    // ----------------------------------------------------------------------- dette

    @Test
    @DisplayName("F-125 / SF-125-04 : une dette non nulle ne renvoie plus l'agent au travail")
    void aDebtNoLongerBlocks() {
        assertThat(dette.evaluate(reply("<!-- fin-de-tour: promotion=aucune; dette=2 -->"))
                .blocked()).isFalse();
        assertThat(dette.evaluate(reply("<!-- fin-de-tour: promotion=aucune; dette=1 -->"))
                .blocked()).isFalse();
    }

    @Test
    @DisplayName("dette nulle : la clôture passe")
    void noDebtPasses() {
        assertThat(dette.evaluate(reply("<!-- fin-de-tour: promotion=aucune; dette=0 -->")).blocked())
                .isFalse();
    }

    @Test
    @DisplayName("sans marqueur, la dette PASSE : réclamer la forme est le travail du juge")
    void withoutAMarkerTheDebtControlPasses() {
        assertThat(dette.evaluate(reply("C'est fait.")).blocked()).isFalse();
        assertThat(dette.evaluate(null).blocked()).isFalse();
        verify(destinations, never()).pathsForProject(any(), any());
    }

    @Test
    @DisplayName("un marqueur sans « promu » reste lisible : les paquets déjà activés continuent")
    void anOldMarkerStillWorks() {
        assertThat(dette.evaluate(reply("<!-- fin-de-tour: promotion=aucune; dette=0 -->")).blocked())
                .isFalse();
        assertThat(dette.evaluate(reply("<!-- fin-de-tour: promotion=aucune; promu=aucune; dette=0 -->"))
                .blocked()).isFalse();
    }

    // ------------------------------------------------------------------ isolation

    @Test
    @DisplayName("la dette ne lit la carte QUE du couple (utilisateur, projet) du tour en cours")
    void theMapIsReadForTheCurrentProjectOnly() {
        dette.evaluate(reply("<!-- fin-de-tour: promotion=aucune; promu=x; dette=0 -->"));

        verify(destinations).pathsForProject(userId, workspaceId);
    }

    @Test
    @DisplayName("un couple absent ne fait jamais lever : promotion muette refusée, dette silencieuse")
    void anAbsentIdentityNeverThrows() {
        // Une promotion muette (sans destination) est toujours refusée, même identité absente…
        assertThat(dette.evaluate(AtelierCheckpointContext.endOfTurn(null, null,
                "<!-- fin-de-tour: promotion=aucune; promu=un serveur; dette=0 -->", List.of()))
                .blocked()).isTrue();
        // … tandis qu'une dette seule ne bloque plus (F-125 / SF-125-04) et ne lève jamais.
        String marked = "<!-- fin-de-tour: promotion=aucune; dette=4 -->";
        assertThat(dette.evaluate(AtelierCheckpointContext.endOfTurn(null, null, marked, List.of()))
                .blocked()).isFalse();
        assertThat(juge.evaluate(AtelierCheckpointContext.endOfTurn(null, null, marked, List.of()))
                .blocked()).isFalse();
    }

    // ----------------------------------------------------- composition avec F-94

    @Test
    @DisplayName("LES DEUX JUGES SE COMPOSENT : le marqueur juge une DÉCLARATION, l'audit des FICHIERS")
    void theTwoJudgesCompose() {
        // Un tour parfaitement déclaré : le juge du marqueur laisse passer, et il a raison — le
        // modèle a dit qu'il n'avait rien à promouvoir.
        String declare = "<!-- fin-de-tour: promotion=aucune; promu=aucune; dette=0 -->";
        AtelierCheckpointContext tour = AtelierCheckpointContext.endOfTurn(userId, workspaceId,
                declare, List.of("STATE.md"));
        assertThat(juge.evaluate(tour).blocked()).isFalse();
        assertThat(dette.evaluate(tour).blocked()).isFalse();

        // Le juge INDÉPENDANT, lui, a regardé les fichiers — et il a vu ce que le modèle a oublié.
        // C'est exactement le cas qu'une auto-déclaration ne peut pas attraper : un modèle qui
        // oublie de promouvoir oublie aussi de le déclarer.
        JugeIndependantService service = mock(JugeIndependantService.class);
        when(service.consulter(userId, workspaceId)).thenReturn(JugeAvis.elements(
                List.of(new JugeVerdict.Element("bastion bst-01", "p/STATE.md"))));
        JugeIndependantControl audit =
                new JugeIndependantControl(service, new JugeMemo(), destinations);

        AtelierCheckpointVerdict verdict = audit.evaluate(tour);
        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.correction()).contains("best-effort", "bastion bst-01");
    }
}
