package fr.claudegateway.atelier.agent;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import fr.claudegateway.git.GitProperties;
import fr.claudegateway.git.GitTokenService;
import fr.claudegateway.git.GitVaultRef;
import fr.claudegateway.git.GitVaultRevokedEvent;

/**
 * Cycle de vie du <b>vault de credentials</b> qui authentifie le serveur MCP GitHub d'un utilisateur
 * (F-31 / SF-31-05, ADR-015).
 *
 * <p><b>Un vault par utilisateur</b>, pour deux raisons qui se rejoignent : le fournisseur n'accepte
 * qu'une credential par {@code mcp_server_url} et par vault — un vault partagé ne pourrait donc
 * porter qu'un seul jeton — et mélanger les jetons de plusieurs utilisateurs violerait l'isolation
 * {@code user_id}.</p>
 *
 * <p><b>Créé paresseusement</b> : rien n'est déposé chez le fournisseur tant que l'utilisateur
 * n'ouvre pas de session sur un dépôt Git. Enregistrer un jeton dans les réglages ne le recopie
 * nulle part ailleurs que dans notre base, chiffré.</p>
 *
 * <p><b>Détruit à la révocation</b> : remplacer ou retirer le jeton fait disparaître le vault. Un
 * jeton révoqué chez nous mais toujours utilisable chez le fournisseur serait une révocation de
 * façade.</p>
 *
 * <p>Le jeton en clair ne traverse ce service qu'en mémoire, le temps de l'appel de création, et
 * n'est jamais journalisé.</p>
 */
@Service
public class McpVaultService {

    private static final Logger log = LoggerFactory.getLogger(McpVaultService.class);

    private final ManagedAgentProvider provider;
    private final GitTokenService gitTokenService;
    private final GitProperties properties;

    public McpVaultService(ManagedAgentProvider provider, GitTokenService gitTokenService,
            GitProperties properties) {
        this.provider = provider;
        this.gitTokenService = gitTokenService;
        this.properties = properties;
    }

    /**
     * Accès MCP de l'utilisateur, en créant le vault au premier besoin.
     *
     * <p>Renvoie vide — sans lever — quand l'utilisateur n'a pas de jeton, ou quand le fournisseur
     * refuse de créer le vault : la session doit pouvoir s'ouvrir <b>sans</b> MCP. Perdre l'Atelier
     * entier parce que la création de pull request est indisponible serait disproportionné, et le
     * repli de SF-31-04 (lien de comparaison) reste en place.</p>
     *
     * @param userId propriétaire du jeton (isolation multi-tenant)
     * @return l'accès MCP à attacher à la session, ou vide s'il n'y en a pas
     */
    public Optional<McpAccess> resolveAccess(UUID userId) {
        Optional<GitVaultRef> existing = gitTokenService.findVault(userId);
        if (existing.isPresent()) {
            return existing.map(ref -> access(ref.vaultId()));
        }
        Optional<String> token = gitTokenService.resolveToken(userId);
        if (token.isEmpty()) {
            return Optional.empty();
        }
        try {
            ManagedVault vault = provider.createVaultWithBearer(
                    "claude-gateway user " + userId, properties.mcpServerUrl(), token.get());
            gitTokenService.rememberVault(userId, new GitVaultRef(vault.vaultId(), vault.credentialId()));
            log.info("Vault de credentials MCP créé pour l'utilisateur {}", userId);
            return Optional.of(access(vault.vaultId()));
        } catch (AgentProviderException ex) {
            // Dégradation volontaire : la session s'ouvre sans MCP, et la création de pull request
            // dira franchement qu'elle n'a pas abouti. Le message du fournisseur est journalisé, le
            // jeton ne l'est jamais.
            log.warn("Vault de credentials MCP indisponible pour l'utilisateur {} : {}", userId,
                    ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Détruit le vault d'un jeton révoqué, <b>après commit</b> de la transaction qui l'a révoqué :
     * détruire un vault pour une transaction annulée laisserait l'utilisateur avec un jeton en base
     * et plus rien chez le fournisseur.
     *
     * <p>Best-effort — la suppression côté fournisseur ne lève jamais (voir
     * {@link ManagedAgentProvider#deleteVault(String)}) : le geste de l'utilisateur a déjà abouti.</p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTokenRevoked(GitVaultRevokedEvent event) {
        provider.deleteVault(event.vaultId());
        log.info("Vault de credentials MCP détruit pour l'utilisateur {}", event.userId());
    }

    private McpAccess access(String vaultId) {
        return new McpAccess(vaultId, properties.mcpServerName(), properties.mcpServerUrl());
    }
}
