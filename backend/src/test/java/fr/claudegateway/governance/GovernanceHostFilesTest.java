package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.governance.GovernanceHostFiles.HostFileRead;
import fr.claudegateway.governance.GovernanceHostFiles.Presence;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * F-92 / SF-92-01 — la racine du poste, lue et écrite.
 *
 * <p>Ce que ces tests protègent : <b>« absent » ne se déduit que d'un « absent » explicite</b>. Tout
 * le reste — machine muette, droits refusés, chemin occupé par un dossier — est un <b>doute</b>, et
 * un doute ne doit jamais autoriser une écriture : à la racine d'un poste, écrire par erreur
 * signifie <b>écraser la carte d'un client</b>.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GovernanceHostFilesTest {

    @Mock
    private RunnerToolGateway gateway;
    @Mock
    private RunnerAuditService auditService;

    private GovernanceHostFiles files;

    private final UUID alice = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final GovernanceHostRef host = GovernanceHostRef.of(hostId);

    @BeforeEach
    void setUp() {
        files = new GovernanceHostFiles(gateway, auditService);
    }

    private static RunnerCallResult ok(String content) {
        return new RunnerCallResult(true, content, false, null, 1L, null, null, null, "", false);
    }

    @Test
    @DisplayName("une lecture réussie rend PRÉSENT, avec son contenu")
    void aSuccessfulReadIsPresent() {
        when(gateway.readFile(any(), anyString(), eq("README.md")))
                .thenReturn(ok("# La carte du poste"));

        HostFileRead read = files.read(alice, host, "README.md");

        assertThat(read.presence()).isEqualTo(Presence.PRESENT);
        assertThat(read.contentOrEmpty()).isEqualTo("# La carte du poste");
    }

    @Test
    @DisplayName("« not_found » — et lui SEUL — vaut ABSENT")
    void onlyNotFoundMeansAbsent() {
        when(gateway.readFile(any(), anyString(), anyString()))
                .thenReturn(RunnerCallResult.backendError("not_found", "Fichier introuvable"));

        assertThat(files.presence(alice, host, "acces.md")).isEqualTo(Presence.ABSENT);
    }

    @Test
    @DisplayName("des droits refusés, un dossier, un fichier trop gros : on NE SAIT PAS")
    void everyOtherRefusalIsUnknown() {
        for (String code : new String[] {"io_error", "is_directory", "too_large", "not_a_file"}) {
            when(gateway.readFile(any(), anyString(), anyString()))
                    .thenReturn(RunnerCallResult.backendError(code, "refus"));

            assertThat(files.presence(alice, host, "acces.md"))
                    .as("code %s", code).isEqualTo(Presence.UNKNOWN);
        }
    }

    @Test
    @DisplayName("une machine muette est INJOIGNABLE, pas « illisible » — la suite n'a rien à tenter")
    void asilentMachineIsUnreachable() {
        // La distinction n'est pas cosmétique : un fichier illisible n'empêche pas de lire le
        // suivant, une machine injoignable si. C'est elle qui borne le coût de la lecture de carte
        // à UN délai plutôt qu'à six (F-92 / SF-92-02).
        for (String code : new String[] {RunnerErrorCodes.RUNNER_UNAVAILABLE,
                RunnerErrorCodes.RUNNER_TIMEOUT, RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE}) {
            when(gateway.readFile(any(), anyString(), anyString()))
                    .thenReturn(RunnerCallResult.backendError(code, "muet"));

            assertThat(files.presence(alice, host, "acces.md"))
                    .as("code %s", code).isEqualTo(Presence.UNREACHABLE);
        }
    }

    @Test
    @DisplayName("un chemin inexploitable ne traverse pas le réseau et ne passe JAMAIS pour absent")
    void anUnusablePathIsNeverAbsent() {
        assertThat(files.presence(alice, host, "../ailleurs.md")).isEqualTo(Presence.UNKNOWN);
        assertThat(files.presence(alice, host, "/etc/passwd")).isEqualTo(Presence.UNKNOWN);

        verify(gateway, never()).readFile(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("le poste « Hébergé » n'a pas de racine : aucun appel ne part")
    void theHostedHostIsUnsupported() {
        assertThat(files.supports(GovernanceHostRef.HOSTED)).isFalse();
        assertThat(files.presence(alice, GovernanceHostRef.HOSTED, "README.md"))
                .isEqualTo(Presence.UNSUPPORTED);
        assertThat(files.write(alice, GovernanceHostRef.HOSTED, "README.md", "x")).isFalse();

        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("la cible est un appel de POSTE : la racine, et aucun projet")
    void theTargetIsTheHostRoot() {
        when(gateway.readFile(any(), anyString(), anyString())).thenReturn(ok(""));

        files.read(alice, host, "reseau.md");

        ArgumentCaptor<RunnerTarget> captor = ArgumentCaptor.forClass(RunnerTarget.class);
        verify(gateway).readFile(captor.capture(), anyString(), eq("reseau.md"));
        assertThat(captor.getValue().hostId()).isEqualTo(hostId);
        assertThat(captor.getValue().workspaceId()).isNull();
        assertThat(captor.getValue().safeProjectPath()).isEmpty();
    }

    @Test
    @DisplayName("chaque lecture et chaque écriture laisse une ligne de journal qui lui est propre")
    void everyAccessIsAudited() {
        when(gateway.readFile(any(), anyString(), anyString())).thenReturn(ok(""));
        when(gateway.writeFile(any(), anyString(), anyString(), any())).thenReturn(ok("écrit"));

        files.read(alice, host, "donnees.md");
        files.write(alice, host, "donnees.md", "# Données");

        verify(auditService).recordCall(eq(alice), any(), anyString(),
                eq(GovernanceHostFiles.TOOL_MAP_READ), eq("donnees.md"), any());
        verify(auditService).recordCall(eq(alice), any(), anyString(),
                eq(GovernanceHostFiles.TOOL_MAP_WRITE), eq("donnees.md"), any());
    }

    @Test
    @DisplayName("une écriture refusée rend faux, sans exception")
    void arefusedWriteReturnsFalse() {
        when(gateway.writeFile(any(), anyString(), anyString(), any()))
                .thenReturn(RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_UNAVAILABLE));

        assertThat(files.write(alice, host, "donnees.md", "# Données")).isFalse();
    }

    // ----------------------------------------------- F-125 / SF-125-03 : listage de la racine

    @Test
    @DisplayName("listRoot ne rend que les entrées de premier niveau, ./ et \\ normalisés")
    void listRootReturnsRootLevelEntriesOnly() {
        when(gateway.listFiles(any(), anyString()))
                .thenReturn(ok("README.md\nenjeux.md\n./acces.md\nrepos/portail/STATE.md\n"
                        + "migration-dns\\notes.md\nmigration-dns\n"));

        assertThat(files.listRoot(alice, host))
                .containsExactly("README.md", "enjeux.md", "acces.md", "migration-dns");
        verify(auditService).recordCall(eq(alice), any(), anyString(),
                eq(GovernanceHostFiles.TOOL_MAP_LIST), eq(""), any());
    }

    @Test
    @DisplayName("listRoot rend vide quand la machine ne répond pas")
    void listRootIsEmptyWhenUnreachable() {
        when(gateway.listFiles(any(), anyString()))
                .thenReturn(RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_UNAVAILABLE));

        assertThat(files.listRoot(alice, host)).isEmpty();
    }

    @Test
    @DisplayName("listRoot rend vide sur un listage tronqué : on ne conclut aucune absence")
    void listRootIsEmptyWhenTruncated() {
        when(gateway.listFiles(any(), anyString()))
                .thenReturn(new RunnerCallResult(true, "enjeux.md\n", true, null, 1L, null, null,
                        null, "", false));

        assertThat(files.listRoot(alice, host)).isEmpty();
    }

    @Test
    @DisplayName("listRoot d'un poste hébergé ne joint aucune machine")
    void listRootOfAHostedHostProbesNothing() {
        assertThat(files.listRoot(alice, GovernanceHostRef.HOSTED)).isEmpty();
        verifyNoInteractions(gateway);
    }
}
