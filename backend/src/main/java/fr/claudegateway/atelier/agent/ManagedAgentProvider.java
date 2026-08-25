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
    ManagedSession createSession(String agentId, String environmentId, List<FileMount> resources,
            RepositoryMount repository, String systemOverride);

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
