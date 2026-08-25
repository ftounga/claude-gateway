package fr.claudegateway.atelier.agent;

import java.time.Duration;
import java.util.List;

/**
 * Abstraction fournisseur pour les <b>Managed Agents</b> (F-28 / Phase 2, ADR-013). Parallèle de
 * {@code AIProvider} : le code métier ne dépend que de cette interface, jamais directement
 * d'Anthropic (Provider Independence).
 *
 * <p>Deux familles d'opérations :</p>
 * <ul>
 *   <li><b>Fondation</b> (SF-28-08, sans coût runtime) : {@link #createEnvironment(EnvironmentSpec)},
 *       {@link #createAgent(AgentSpec)} ;</li>
 *   <li><b>Session</b> (SF-28-09) : téléversement de fichiers, création/pilotage d'une session
 *       éphémère (montage des fichiers du workspace, message, attente par polling, récupération des
 *       sorties, terminaison).</li>
 * </ul>
 *
 * <p>Distinct du package {@code fr.claudegateway.agent} (abstraction tool-use de la Phase 1) : ce
 * provider cible l'API Managed Agents d'Anthropic (Environments/Agents/Sessions).</p>
 */
public interface ManagedAgentProvider {

    /**
     * Crée un environnement d'exécution (bac à sable cloud) chez le fournisseur.
     *
     * @param spec caractéristiques de l'environnement à créer
     * @return l'environnement créé (identifiant fournisseur)
     */
    ManagedEnvironment createEnvironment(EnvironmentSpec spec);

    /**
     * Crée une définition d'agent versionnée chez le fournisseur.
     *
     * @param spec caractéristiques de l'agent à créer
     * @return la définition d'agent créée (identifiant + version fournisseur)
     */
    ManagedAgentDefinition createAgent(AgentSpec spec);

    /**
     * Téléverse un fichier chez le fournisseur (Files API, {@code purpose=agent}) pour montage
     * ultérieur dans une session.
     *
     * @param filename nom du fichier (porté dans le multipart)
     * @param content  contenu binaire du fichier
     * @return identifiant fournisseur du fichier ({@code file_id})
     */
    String uploadFile(String filename, byte[] content);

    /**
     * Crée une session éphémère montant les fichiers fournis dans le bac à sable.
     *
     * @param agentId       identifiant de l'agent à exécuter
     * @param environmentId identifiant de l'environnement d'exécution
     * @param resources     fichiers à monter (chacun : {@code file_id} + {@code mount_path})
     * @return la session créée (identifiant fournisseur)
     */
    default ManagedSession createSession(String agentId, String environmentId, List<FileMount> resources) {
        return createSession(agentId, environmentId, resources, null, null);
    }

    /**
     * Crée une session montant des fichiers <b>et/ou</b> un dépôt Git (F-31 / SF-31-02, ADR-015).
     *
     * <p>Un workspace {@code GIT} ne téléverse aucun fichier : le dépôt est cloné par le fournisseur,
     * ce qui supprime le plafond du nombre de fichiers montés. Le jeton porté par {@code repository}
     * n'entre jamais dans le conteneur (proxy git côté fournisseur) et ne doit jamais être journalisé.</p>
     *
     * @param agentId       identifiant de l'agent à exécuter
     * @param environmentId identifiant de l'environnement d'exécution
     * @param resources     fichiers à monter (éventuellement vide)
     * @param repository    dépôt à monter, ou {@code null} pour un workspace d'archive
     * @return la session créée (identifiant fournisseur)
     */
    default ManagedSession createSession(String agentId, String environmentId, List<FileMount> resources,
            RepositoryMount repository) {
        return createSession(agentId, environmentId, resources, repository, null);
    }

    /**
     * Crée une session en <b>surchargeant le prompt système</b> pour cette session seulement
     * (F-34 / SF-34-01).
     *
     * <p>Sert à porter les instructions du projet à l'ouverture de la session. La surcharge est
     * <b>session-locale</b> : elle ne modifie pas l'agent provisionné pour la plateforme, et n'est
     * donc jamais visible d'un autre utilisateur. Le prompt passé est <b>complet</b> (il contient
     * déjà le prompt plateforme) : chez le fournisseur, une surcharge remplace, elle n'ajoute pas.</p>
     *
     * @param agentId        identifiant de l'agent à exécuter
     * @param environmentId  identifiant de l'environnement d'exécution
     * @param resources      fichiers à monter (éventuellement vide)
     * @param repository     dépôt à monter, ou {@code null} pour un workspace d'archive
     * @param systemOverride prompt système complet à utiliser pour cette session, ou {@code null}
     *                       pour conserver celui de l'agent (comportement d'avant F-34)
     * @return la session créée (identifiant fournisseur)
     */
    default ManagedSession createSession(String agentId, String environmentId, List<FileMount> resources,
            RepositoryMount repository, String systemOverride) {
        return createSession(agentId, environmentId, resources, repository, systemOverride,
                SessionPermissions.ALLOW_ALL);
    }

    /**
     * Crée une session en fixant en outre la <b>politique d'autorisation des outils</b>
     * (F-33 / SF-33-01).
     *
     * <p>La politique est fixée <b>à l'ouverture</b> et vaut pour toute la vie de la session. Avec
     * {@link SessionPermissions#ALLOW_ALL}, la session est créée exactement comme avant F-33 (aucune
     * surcharge d'outils) : aucune régression pour les projets qui n'activent rien.</p>
     *
     * @param agentId        identifiant de l'agent à exécuter
     * @param environmentId  identifiant de l'environnement d'exécution
     * @param resources      fichiers à monter (éventuellement vide)
     * @param repository     dépôt à monter, ou {@code null} pour un workspace d'archive
     * @param systemOverride prompt système complet pour cette session, ou {@code null}
     * @param permissions    politique d'autorisation des outils (jamais {@code null})
     * @return la session créée (identifiant fournisseur)
     */
    default ManagedSession createSession(String agentId, String environmentId, List<FileMount> resources,
            RepositoryMount repository, String systemOverride, SessionPermissions permissions) {
        return createSession(agentId, environmentId, resources, repository, systemOverride, permissions,
                null);
    }

    /**
     * Crée une session en lui donnant en outre accès à un <b>serveur MCP</b> authentifié par un vault
     * de credentials (F-31 / SF-31-05, ADR-015).
     *
     * <p>Le vault s'attache <b>à la création</b> : le fournisseur refuse de l'ajouter à une session
     * déjà ouverte. Avec {@code mcpAccess} à {@code null}, le corps envoyé est strictement celui
     * d'avant SF-31-05 — aucune régression pour les projets qui n'en ont pas besoin.</p>
     *
     * <p>Le secret n'est pas ici : il vit dans le vault, chez le fournisseur, et n'entre jamais dans
     * le conteneur.</p>
     *
     * @param agentId        identifiant de l'agent à exécuter
     * @param environmentId  identifiant de l'environnement d'exécution
     * @param resources      fichiers à monter (éventuellement vide)
     * @param repository     dépôt à monter, ou {@code null} pour un workspace d'archive
     * @param systemOverride prompt système complet pour cette session, ou {@code null}
     * @param permissions    politique d'autorisation des outils (jamais {@code null})
     * @param mcpAccess      serveur MCP et vault à attacher, ou {@code null} pour aucun
     * @return la session créée (identifiant fournisseur)
     */
    default ManagedSession createSession(String agentId, String environmentId, List<FileMount> resources,
            RepositoryMount repository, String systemOverride, SessionPermissions permissions,
            McpAccess mcpAccess) {
        return createSession(agentId, environmentId, resources, repository, systemOverride, permissions,
                mcpAccess, DelegationPolicy.DISABLED);
    }

    /**
     * Crée une session pouvant en outre <b>déléguer</b> des sous-tâches à des copies d'elle-même
     * (F-35 / SF-35-01).
     *
     * <p>La délégation est fixée <b>à l'ouverture</b> et vaut pour toute la vie de la session, comme
     * la politique d'outils et le prompt système. Avec {@link DelegationPolicy#DISABLED}, le corps
     * envoyé est strictement celui d'avant F-35 — aucune régression pour les sessions séquentielles,
     * qui restent le comportement par défaut.</p>
     *
     * <p><b>Coût</b> : chaque sous-agent consomme sa propre session de bac à sable facturée. Le
     * plafond porté par la politique est donc le garde-fou, pas une préférence.</p>
     *
     * @param agentId        identifiant de l'agent à exécuter
     * @param environmentId  identifiant de l'environnement d'exécution
     * @param resources      fichiers à monter (éventuellement vide)
     * @param repository     dépôt à monter, ou {@code null} pour un workspace d'archive
     * @param systemOverride prompt système complet pour cette session, ou {@code null}
     * @param permissions    politique d'autorisation des outils (jamais {@code null})
     * @param mcpAccess      serveur MCP et vault à attacher, ou {@code null} pour aucun
     * @param delegation     politique de délégation (jamais {@code null})
     * @return la session créée (identifiant fournisseur)
     */
    ManagedSession createSession(String agentId, String environmentId, List<FileMount> resources,
            RepositoryMount repository, String systemOverride, SessionPermissions permissions,
            McpAccess mcpAccess, DelegationPolicy delegation);

    /**
     * Crée un <b>vault</b> de credentials chez le fournisseur et y dépose un jeton bearer statique
     * destiné à un serveur MCP (F-31 / SF-31-05).
     *
     * <p>Un vault par utilisateur : le fournisseur n'accepte qu'une credential par
     * {@code mcp_server_url} et par vault, et mélanger les jetons de plusieurs utilisateurs dans un
     * même vault violerait l'isolation.</p>
     *
     * <p>Le jeton passé ici est un <b>secret en clair</b>, le temps de l'appel. Il ne doit jamais
     * être journalisé, et le fournisseur ne le renvoie jamais.</p>
     *
     * @param displayName nom lisible du vault (jamais un secret : sert au diagnostic côté fournisseur)
     * @param serverUrl   URL du serveur MCP, qui est la clé de la credential
     * @param token       jeton bearer à déposer
     * @return le vault créé et l'identifiant de la credential déposée
     * @throws AgentProviderException si le fournisseur refuse la création
     */
    ManagedVault createVaultWithBearer(String displayName, String serverUrl, String token);

    /**
     * Détruit un vault chez le fournisseur (F-31 / SF-31-05), après révocation du jeton qu'il porte.
     *
     * <p>Nettoyage <b>best-effort</b> : ne lève jamais. Un échec est journalisé — le jeton, lui, a
     * déjà été révoqué côté GitHub ou retiré de chez nous, et faire échouer le geste de l'utilisateur
     * sur une panne du fournisseur n'améliorerait rien.</p>
     *
     * @param vaultId identifiant du vault à détruire
     */
    void deleteVault(String vaultId);

    /**
     * Envoie un message utilisateur à la session (event {@code user.message}).
     *
     * @param sessionId identifiant de la session
     * @param text      contenu du message
     */
    void sendUserMessage(String sessionId, String text);

    /**
     * Attend la complétion de la session par polling des events jusqu'à {@code session.status_idle},
     * en agrégeant le texte des events {@code agent.message}. Garde-fous : au plus {@code maxPolls}
     * tours et pas au-delà de {@code timeout}.
     *
     * @param sessionId identifiant de la session
     * @param timeout   délai maximal d'attente (garde-fou de coût runtime)
     * @param maxPolls  nombre maximal de tours de polling
     * @return la réponse agrégée + la raison d'arrêt
     * @throws AgentSessionTimeoutException si l'état idle n'est pas atteint dans les bornes
     */
    SessionRun awaitCompletion(String sessionId, Duration timeout, int maxPolls);

    /**
     * Variante de {@link #awaitCompletion(String, Duration, int)} qui <b>relaie en direct</b> chaque
     * event de page au {@code listener} (texte d'agent, usage d'outil, transition d'état) en plus
     * d'agréger la réponse finale. La variante à trois arguments délègue ici avec
     * {@link ManagedEventListener#NOOP} (aucune régression).
     *
     * @param sessionId identifiant de la session
     * @param timeout   délai maximal d'attente (garde-fou de coût runtime)
     * @param maxPolls  nombre maximal de tours de polling
     * @param listener  écouteur notifié pour chaque event relayé (jamais {@code null} ; passer
     *                  {@link ManagedEventListener#NOOP} pour ne rien relayer)
     * @return la réponse agrégée + la raison d'arrêt
     * @throws AgentSessionTimeoutException si l'état idle n'est pas atteint dans les bornes
     */
    SessionRun awaitCompletion(String sessionId, Duration timeout, int maxPolls, ManagedEventListener listener);

    /**
     * Demande l'<b>interruption</b> du travail en cours dans la session (event {@code user.interrupt},
     * F-32 / SF-32-01).
     *
     * <p>L'arrêt est <b>asynchrone</b> : la session poursuit jusqu'à une frontière sûre puis passe
     * {@code idle} — ce n'est pas un {@code kill}, aucun état n'est corrompu. Le run en cours sort
     * donc de son attente par le chemin nominal, sur le {@code session.status_idle} qui suit.</p>
     *
     * @param sessionId identifiant de la session à interrompre
     * @throws AgentProviderException si le fournisseur refuse l'interruption (session morte, panne) —
     *                                contrairement à {@link #terminateSession(String)}, l'échec est
     *                                remonté : l'utilisateur doit savoir que sa demande n'est pas passée
     */
    void interruptSession(String sessionId);

    /**
     * Répond à une <b>demande d'autorisation</b> d'outil (event {@code user.tool_confirmation},
     * F-33 / SF-33-02) : la session, en pause depuis la demande, reprend — en exécutant la commande,
     * ou en apprenant qu'elle est refusée et pourquoi.
     *
     * <p>L'identifiant attendu est celui relayé par
     * {@link ManagedEventListener#onConfirmationRequest(String, String, String)} — l'identifiant de
     * l'<b>event</b> de demande, jamais un identifiant fabriqué ailleurs.</p>
     *
     * @param sessionId      identifiant de la session en attente
     * @param confirmationId identifiant de la demande à trancher
     * @param allow          vrai pour autoriser, faux pour refuser
     * @param message        motif du refus relayé à l'agent (ignoré sur une autorisation) ; peut être
     *                       {@code null}
     * @throws AgentProviderException si la demande est inconnue, déjà tranchée, ou si le fournisseur
     *                                est indisponible — l'échec est remonté : une autorisation qui
     *                                n'est pas passée doit être dite
     */
    void confirmToolUse(String sessionId, String confirmationId, boolean allow, String message);

    /**
     * Liste les fichiers de sortie de la session (Files API, filtrés sur le {@code scope_id}).
     *
     * @param sessionId identifiant de la session
     * @return les fichiers de sortie (identifiant + nom)
     */
    List<OutputFile> listOutputs(String sessionId);

    /**
     * Télécharge le contenu binaire d'un fichier chez le fournisseur.
     *
     * @param fileId identifiant fournisseur du fichier
     * @return le contenu binaire
     */
    byte[] downloadFile(String fileId);

    /**
     * Termine la session (nettoyage <b>best-effort</b> : ne lève jamais). Borne le coût runtime.
     *
     * @param sessionId identifiant de la session à terminer
     */
    void terminateSession(String sessionId);

    /**
     * Récupère la consommation agrégée d'une session (F-28 / SF-28-12) pour la décompter du quota et
     * du plafond de bac à sable. Les tokens d'entrée agrègent l'ensemble des postes rapportés par le
     * fournisseur (entrée + lecture/écriture de cache) ; le temps de bac à sable est le temps facturé
     * ({@code active_seconds} arrondi à la seconde).
     *
     * @param sessionId identifiant de la session
     * @return la consommation agrégée de la session
     * @throws AgentProviderException en cas d'échec de récupération (l'appelant la traite en best-effort)
     */
    SessionUsage getSessionUsage(String sessionId);

    /**
     * Consommation agrégée d'une session Managed Agents (F-28 / SF-28-12).
     *
     * @param inputTokens   tokens d'entrée agrégés (entrée + lecture/création de cache)
     * @param outputTokens  tokens de sortie
     * @param activeSeconds temps de bac à sable facturé (secondes, arrondi)
     */
    record SessionUsage(long inputTokens, long outputTokens, long activeSeconds) {
    }
}
