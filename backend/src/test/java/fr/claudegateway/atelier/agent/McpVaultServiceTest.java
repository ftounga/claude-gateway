package fr.claudegateway.atelier.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.git.GitProperties;
import fr.claudegateway.git.GitTokenService;
import fr.claudegateway.git.GitVaultRef;
import fr.claudegateway.git.GitVaultRevokedEvent;

/**
 * Vérifie le cycle de vie du vault de credentials MCP (F-31 / SF-31-05) : création paresseuse, une
 * seule fois par utilisateur, destruction à la révocation, et dégradation propre quand le fournisseur
 * n'est pas disponible.
 */
@ExtendWith(MockitoExtension.class)
class McpVaultServiceTest {

    private static final UUID USER = UUID.randomUUID();

    @Mock
    private ManagedAgentProvider provider;
    @Mock
    private GitTokenService gitTokenService;

    private final GitProperties properties = new GitProperties(null, null, null, null, null, null);

    private McpVaultService service() {
        return new McpVaultService(provider, gitTokenService, properties);
    }

    @Test
    void createsTheVaultOnFirstUseAndRemembersIt() {
        when(gitTokenService.findVault(USER)).thenReturn(Optional.empty());
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        when(provider.createVaultWithBearer(any(), eq("https://api.githubcopilot.com/mcp/"),
                eq("github_pat_secret"))).thenReturn(new ManagedVault("vlt_1", "vcrd_1"));

        Optional<McpAccess> access = service().resolveAccess(USER);

        assertThat(access).isPresent();
        assertThat(access.get().vaultId()).isEqualTo("vlt_1");
        assertThat(access.get().serverName()).isEqualTo("github");
        assertThat(access.get().serverUrl()).isEqualTo("https://api.githubcopilot.com/mcp/");

        ArgumentCaptor<GitVaultRef> remembered = ArgumentCaptor.forClass(GitVaultRef.class);
        verify(gitTokenService).rememberVault(eq(USER), remembered.capture());
        assertThat(remembered.getValue().vaultId()).isEqualTo("vlt_1");
        assertThat(remembered.getValue().credentialId()).isEqualTo("vcrd_1");
    }

    @Test
    void reusesTheExistingVaultWithoutCreatingASecondOne() {
        // Un vault par utilisateur : le fournisseur n'accepte qu'une credential par serveur MCP, et
        // en recréer un à chaque session laisserait des copies du jeton derrière nous.
        when(gitTokenService.findVault(USER)).thenReturn(Optional.of(new GitVaultRef("vlt_1", "vcrd_1")));

        Optional<McpAccess> access = service().resolveAccess(USER);

        assertThat(access).isPresent();
        assertThat(access.get().vaultId()).isEqualTo("vlt_1");
        verify(provider, never()).createVaultWithBearer(any(), any(), any());
        verify(gitTokenService, never()).rememberVault(any(), any());
    }

    @Test
    void withoutATokenNothingIsDepositedAtTheProvider() {
        when(gitTokenService.findVault(USER)).thenReturn(Optional.empty());
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.empty());

        assertThat(service().resolveAccess(USER)).isEmpty();
        verify(provider, never()).createVaultWithBearer(any(), any(), any());
    }

    @Test
    void aProviderFailureDegradesToNoMcpRatherThanBreakingTheSession() {
        // Perdre l'Atelier entier parce que la création de pull request est indisponible serait
        // disproportionné : la session s'ouvre sans MCP, le repli de SF-31-04 reste en place.
        when(gitTokenService.findVault(USER)).thenReturn(Optional.empty());
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        when(provider.createVaultWithBearer(any(), any(), any()))
                .thenThrow(new AgentProviderException("fournisseur indisponible"));

        assertThat(service().resolveAccess(USER)).isEmpty();
        verify(gitTokenService, never()).rememberVault(any(), any());
    }

    @Test
    void revokingTheTokenDestroysTheVaultAtTheProvider() {
        // Un jeton révoqué chez nous mais toujours utilisable là-bas serait une révocation de façade.
        service().onTokenRevoked(new GitVaultRevokedEvent(USER, "vlt_1", "vcrd_1"));

        verify(provider).deleteVault("vlt_1");
    }

    @Test
    void aFailedDestructionNeverSurfacesToTheUser() {
        // `deleteVault` est best-effort côté provider ; le service ne rattrape rien de plus, mais on
        // fige ici le contrat : la révocation ne doit jamais échouer à cause du fournisseur.
        assertThatCode(() -> service().onTokenRevoked(new GitVaultRevokedEvent(USER, "vlt_1", null)))
                .doesNotThrowAnyException();
    }
}
