package fr.claudegateway.governance.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

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
 * Les contrôles de fin de tour du premier paquet, après le <b>correctif de fond F-125 / SF-125-06b</b>.
 *
 * <p>Ce que ces tests protègent : que <b>ni</b> {@code juge-fin-de-tour} <b>ni</b>
 * {@code promotion-dette-bloquante} ne sanctionnent plus quoi que ce soit — plus aucune dépendance à
 * un marqueur émis par le modèle — et que le suivi de la promotion reste garanti <b>côté serveur</b>
 * par le juge indépendant, qui lit les fichiers et bloque sur un durable cité-mais-absent (la carte
 * reste alimentée sans rituel du modèle).</p>
 */
class EndOfTurnControlsTest {

    private final GovernanceMapDestinations destinations = mock(GovernanceMapDestinations.class);
    private final JugeFinDeTourControl juge = new JugeFinDeTourControl();
    private final PromotionDetteBloquanteControl dette = new PromotionDetteBloquanteControl();
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private AtelierCheckpointContext reply(String text) {
        return AtelierCheckpointContext.endOfTurn(userId, workspaceId, text, List.of());
    }

    private AtelierCheckpointContext replyHavingWritten(String text, String... writtenPaths) {
        return AtelierCheckpointContext.endOfTurn(userId, workspaceId, text, List.of(writtenPaths));
    }

    // -------------------------------------------------------------------- identité

    @Test
    @DisplayName("les deux se déclarent toujours sur la fin de tour, avec un identifiant stable")
    void bothDeclareTheEndOfTurn() {
        assertThat(juge.id()).isEqualTo("juge-fin-de-tour");
        assertThat(dette.id()).isEqualTo("promotion-dette-bloquante");
        assertThat(juge.kind()).isEqualTo(AtelierCheckpointKind.END_OF_TURN);
        assertThat(dette.kind()).isEqualTo(AtelierCheckpointKind.END_OF_TURN);
        assertThat(juge.description()).isNotBlank();
        assertThat(dette.description()).isNotBlank();
    }

    // ------------------------------------------------ SF-125-06b : plus aucune sanction du modèle

    @Test
    @DisplayName("sans marqueur, le juge NE SANCTIONNE PLUS : il passe")
    void withoutAMarkerTheJudgeProceeds() {
        assertThat(juge.evaluate(reply("C'est fait.")).blocked()).isFalse();
    }

    @Test
    @DisplayName("avec un marqueur, un durable non promu ne bloque plus : la déclaration n'est plus lue")
    void aDeclaredNonPromotedDurableNoLongerBlocks() {
        assertThat(juge.evaluate(reply(
                "<!-- fin-de-tour: promotion=le cluster atlas, le VPN client; dette=0 -->"))
                .blocked()).isFalse();
    }

    @Test
    @DisplayName("une promotion sans destination ne bloque plus : la carte se tient en silence")
    void aPromotionWithoutADestinationNoLongerBlocks() {
        assertThat(dette.evaluate(reply(
                "<!-- fin-de-tour: promotion=aucune; promu=cluster atlas; dette=0 -->"))
                .blocked()).isFalse();
    }

    @Test
    @DisplayName("une dette non nulle ne renvoie plus l'agent au travail")
    void aDebtNoLongerBlocks() {
        assertThat(dette.evaluate(reply("<!-- fin-de-tour: promotion=aucune; dette=5 -->"))
                .blocked()).isFalse();
        assertThat(juge.evaluate(reply("<!-- fin-de-tour: promotion=aucune; dette=5 -->"))
                .blocked()).isFalse();
    }

    @Test
    @DisplayName("les deux passent quel que soit l'entrant : texte libre, écriture réelle, rien")
    void bothProceedWhateverTheInput() {
        assertThat(juge.evaluate(reply("Réponse de fond, sans plomberie.")).blocked()).isFalse();
        assertThat(dette.evaluate(reply("Réponse de fond, sans plomberie.")).blocked()).isFalse();
        assertThat(dette.evaluate(replyHavingWritten("Fait.", "plateformes.md")).blocked()).isFalse();
        assertThat(juge.evaluate(replyHavingWritten("Fait.", "src/Main.java")).blocked()).isFalse();
    }

    @Test
    @DisplayName("les contrôles ne lisent JAMAIS la carte : plus aucun coût de fin de tour")
    void theControlsNeverReadTheMap() {
        juge.evaluate(reply("<!-- fin-de-tour: promotion=un serveur; dette=3 -->"));
        dette.evaluate(reply("<!-- fin-de-tour: promotion=aucune; promu=x; dette=3 -->"));

        org.mockito.Mockito.verifyNoInteractions(destinations);
    }

    @Test
    @DisplayName("un contexte absent ou une identité nulle ne fait jamais lever, et ne bloque pas")
    void anAbsentContextIsSurvived() {
        assertThat(juge.evaluate(null).blocked()).isFalse();
        assertThat(dette.evaluate(null).blocked()).isFalse();
        assertThat(juge.evaluate(AtelierCheckpointContext.endOfTurn(null, null, null, List.of()))
                .blocked()).isFalse();
        assertThat(dette.evaluate(AtelierCheckpointContext.endOfTurn(null, null,
                "<!-- fin-de-tour: promotion=un serveur; dette=0 -->", List.of())).blocked())
                .isFalse();
    }

    // ---------------------------- le suivi reste SERVEUR : le juge indépendant lit les fichiers

    @Test
    @DisplayName("le suivi reste garanti côté serveur : le juge INDÉPENDANT bloque sur un durable "
            + "cité-mais-absent, en lisant les FICHIERS et non une déclaration du modèle")
    void theServerSideAuditStillCatchesAForgottenDurable() {
        // Un tour a écrit ; aucun marqueur n'est nécessaire — le juge indépendant regarde les fichiers.
        AtelierCheckpointContext tour = AtelierCheckpointContext.endOfTurn(userId, workspaceId,
                "Réponse de fond.", List.of("STATE.md"));

        JugeIndependantService service = mock(JugeIndependantService.class);
        when(service.consulter(userId, workspaceId)).thenReturn(JugeAvis.elements(
                List.of(new JugeVerdict.Element("bastion bst-01", "p/STATE.md"))));
        when(destinations.citedForProject(any(), any())).thenReturn("acces.md, plateformes.md");
        JugeIndependantControl audit =
                new JugeIndependantControl(service, new JugeMemo(), destinations);

        AtelierCheckpointVerdict verdict = audit.evaluate(tour);
        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.correction()).contains("best-effort", "bastion bst-01");
    }
}
