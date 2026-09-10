package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;

/**
 * F-51 / SF-51-04 — <b>qui</b> est interrogé quand la boucle s'arrête sur un crochet de F-50.
 *
 * <p>Ce que ce test protège : seuls les contrôles cités par les paquets <b>actifs sur ce projet</b>
 * sont interrogés, le premier blocage court-circuite les suivants, et rien de ce qui manque ou casse
 * ne condamne le tour.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GovernanceCheckpointDelegateTest {

    @Mock
    private GovernanceActivationService activationService;
    @Mock
    private GovernancePackageService packageService;

    private final UUID alice = UUID.randomUUID();
    private final UUID workspace = UUID.randomUUID();
    private final List<String> called = new ArrayList<>();

    private GovernanceCheckpointDelegate delegateWith(GovernanceControl... controls) {
        return new GovernanceCheckpointDelegate(activationService, packageService,
                new GovernanceControlRegistry(List.of(controls)));
    }

    private AtelierCheckpointContext writeContext() {
        return AtelierCheckpointContext.afterFileWrite(alice, workspace, "write_file", "a.md", "x");
    }

    /** Un paquet actif citant ces contrôles. */
    private void activate(String... controlIds) {
        GovernancePackage pkg = GovernancePackage.builder().id(UUID.randomUUID()).slug("p")
                .name("P").version(1).published(true)
                .controlIds(String.join(",", controlIds)).build();
        when(packageService.require(pkg.getId())).thenReturn(pkg);
        when(activationService.activeOn(alice, workspace)).thenReturn(List.of(
                GovernanceActivation.builder().userId(alice).workspaceId(workspace)
                        .packageId(pkg.getId()).appliedVersion(1)
                        .status(GovernanceActivationStatus.APPLIED).build()));
    }

    @BeforeEach
    void setUp() {
        called.clear();
    }

    @Test
    @DisplayName("sans paquet actif, on rend « passe » sans lire aucun paquet")
    void noActivationMeansNoLookup() {
        when(activationService.activeOn(alice, workspace)).thenReturn(List.of());
        GovernanceCheckpointDelegate delegate = delegateWith(control("a", true));

        assertThat(delegate.evaluate(AtelierCheckpointKind.AFTER_FILE_WRITE, writeContext()).blocked())
                .isFalse();
        verify(packageService, never()).require(any());
        assertThat(called).isEmpty();
    }

    @Test
    @DisplayName("seuls les contrôles cités par les paquets actifs sont interrogés")
    void onlyCitedControlsAreCalled() {
        activate("cite");
        GovernanceCheckpointDelegate delegate =
                delegateWith(control("cite", false), control("pas-cite", false));

        delegate.evaluate(AtelierCheckpointKind.AFTER_FILE_WRITE, writeContext());

        assertThat(called).containsExactly("cite");
    }

    @Test
    @DisplayName("un contrôle d'un autre point d'accroche n'est pas interrogé")
    void otherKindIsNotCalled() {
        activate("fin-de-tour");
        GovernanceCheckpointDelegate delegate = delegateWith(
                controlOfKind("fin-de-tour", AtelierCheckpointKind.END_OF_TURN, false));

        delegate.evaluate(AtelierCheckpointKind.AFTER_FILE_WRITE, writeContext());

        assertThat(called).isEmpty();
    }

    @Test
    @DisplayName("le premier blocage l'emporte : les suivants ne sont pas appelés")
    void firstBlockWins() {
        activate("bloque", "ensuite");
        GovernanceCheckpointDelegate delegate =
                delegateWith(control("bloque", true), control("ensuite", true));

        AtelierCheckpointVerdict verdict =
                delegate.evaluate(AtelierCheckpointKind.AFTER_FILE_WRITE, writeContext());

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.correction()).isEqualTo("Reprends bloque.");
        assertThat(called).containsExactly("bloque");
    }

    @Test
    @DisplayName("un identifiant que le produit ne fournit plus est ignoré, sans casser les autres")
    void unknownControlIdIsIgnored() {
        activate("disparu", "present");
        GovernanceCheckpointDelegate delegate = delegateWith(control("present", false));

        assertThat(delegate.evaluate(AtelierCheckpointKind.AFTER_FILE_WRITE, writeContext()).blocked())
                .isFalse();
        assertThat(called).containsExactly("present");
    }

    @Test
    @DisplayName("des activations illisibles rendent « passe », jamais un tour raté")
    void unreadableActivationsProceed() {
        when(activationService.activeOn(alice, workspace))
                .thenThrow(new IllegalStateException("base indisponible"));
        GovernanceCheckpointDelegate delegate = delegateWith(control("a", true));

        assertThat(delegate.evaluate(AtelierCheckpointKind.AFTER_FILE_WRITE, writeContext()).blocked())
                .isFalse();
        assertThat(called).isEmpty();
    }

    @Test
    @DisplayName("un contexte sans utilisateur ni projet ne déclenche aucune lecture")
    void contextWithoutIdentityProceeds() {
        GovernanceCheckpointDelegate delegate = delegateWith(control("a", true));

        assertThat(delegate.evaluate(AtelierCheckpointKind.END_OF_TURN, null).blocked()).isFalse();
        assertThat(delegate.evaluate(AtelierCheckpointKind.END_OF_TURN,
                AtelierCheckpointContext.endOfTurn(null, null, "fini", List.of())).blocked()).isFalse();
        verify(activationService, never()).activeOn(any(), any());
    }

    @Test
    @DisplayName("les deux crochets déclarent chacun leur point d'accroche")
    void checkpointsDeclareTheirKind() {
        GovernanceCheckpointDelegate delegate = delegateWith();

        assertThat(new GovernanceWriteCheckpoint(delegate).kind())
                .isEqualTo(AtelierCheckpointKind.AFTER_FILE_WRITE);
        assertThat(new GovernanceEndOfTurnCheckpoint(delegate).kind())
                .isEqualTo(AtelierCheckpointKind.END_OF_TURN);
    }

    private GovernanceControl control(String id, boolean blocks) {
        return controlOfKind(id, AtelierCheckpointKind.AFTER_FILE_WRITE, blocks);
    }

    private GovernanceControl controlOfKind(String id, AtelierCheckpointKind kind, boolean blocks) {
        return new GovernanceControl() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public AtelierCheckpointKind kind() {
                return kind;
            }

            @Override
            public String description() {
                return "Contrôle de test.";
            }

            @Override
            public AtelierCheckpointVerdict evaluate(AtelierCheckpointContext context) {
                called.add(id);
                return blocks
                        ? AtelierCheckpointVerdict.block("Reprends " + id + ".")
                        : AtelierCheckpointVerdict.proceed();
            }
        };
    }
}
