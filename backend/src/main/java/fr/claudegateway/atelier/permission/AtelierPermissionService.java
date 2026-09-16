package fr.claudegateway.atelier.permission;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Politique de permission des outils de la boucle maison (F-121 / SF-121-02) : elle tranche, par
 * <b>règle persistée</b>, si un outil s'exécute sans demander ({@code ALLOW}), demande une
 * autorisation ({@code ASK}) ou est refusé d'emblée ({@code DENY}).
 *
 * <p>C'est ce qui remplace la porte binaire (autoriser/refuser, remise à zéro chaque tour) : ici une
 * règle « toujours autoriser cette commande » survit au tour <b>et</b> au redémarrage, un
 * {@code DENY} bloque sans même demander, et les règles valent par outil — {@code bash} pouvant en
 * plus être réglé par <b>préfixe de commande</b>.</p>
 *
 * <p><b>Isolation</b> : chaque lecture et chaque écriture filtre sur {@code (user_id, workspace_id)}
 * (le repository l'impose). Une règle d'un projet ne s'applique jamais à un autre, ni à un autre
 * compte.</p>
 *
 * <p>Cette classe ne connaît <b>pas</b> les défauts (faut-il demander pour {@code bash} ou pour une
 * édition quand aucune règle n'existe ?) : elle rend l'effet de la règle la plus spécifique, ou
 * {@link Optional#empty()} s'il n'y en a aucune, et laisse l'appelant appliquer sa politique par
 * défaut. C'est ce qui garde la règle « configurable » sans dupliquer les défauts ici.</p>
 */
@Service
public class AtelierPermissionService {

    private final AtelierPermissionRuleRepository repository;

    public AtelierPermissionService(AtelierPermissionRuleRepository repository) {
        this.repository = repository;
    }

    /**
     * L'effet de la règle la <b>plus spécifique</b> qui couvre cet appel, ou vide s'il n'y en a
     * aucune. Pour {@code bash}, une règle de préfixe de commande l'emporte sur une règle d'outil, et
     * le préfixe le plus long l'emporte sur un plus court.
     *
     * @param command la ligne de commande (pour {@code bash}) ; ignorée pour les autres outils
     */
    @Transactional(readOnly = true)
    public Optional<PermissionEffect> ruleFor(UUID userId, UUID workspaceId, String tool, String command) {
        if (tool == null) {
            return Optional.empty();
        }
        List<AtelierPermissionRule> rules = repository.findByUserIdAndWorkspaceId(userId, workspaceId);
        if ("bash".equals(tool) && command != null && !command.isBlank()) {
            AtelierPermissionRule best = null;
            for (AtelierPermissionRule rule : rules) {
                if (!"bash".equals(rule.getTool()) || rule.getCommandPrefix() == null) {
                    continue;
                }
                if (commandMatches(command, rule.getCommandPrefix())
                        && (best == null
                                || rule.getCommandPrefix().length() > best.getCommandPrefix().length())) {
                    best = rule;
                }
            }
            if (best != null) {
                return Optional.of(best.effect());
            }
        }
        // Règle d'outil (sans préfixe) : vaut pour bash sans règle de commande, et pour tout autre outil.
        return rules.stream()
                .filter(rule -> tool.equals(rule.getTool()) && rule.getCommandPrefix() == null)
                .map(AtelierPermissionRule::effect)
                .findFirst();
    }

    /**
     * Écrit une règle « <b>toujours autoriser cette commande</b> » (F-121 / SF-121-02) : pour
     * {@code bash}, sur le <b>premier mot</b> de la commande (« toujours autoriser {@code git} ») ;
     * pour tout autre outil, sur l'outil entier. Idempotent.
     */
    @Transactional
    public void alwaysAllowCommand(UUID userId, UUID workspaceId, String tool, String command) {
        if ("bash".equals(tool) && command != null && !command.isBlank()) {
            setRule(userId, workspaceId, "bash", firstToken(command), PermissionEffect.ALLOW);
        } else if (tool != null && !tool.isBlank()) {
            setRule(userId, workspaceId, tool, null, PermissionEffect.ALLOW);
        }
    }

    /**
     * Pose (ou remplace) une règle allow/ask/deny. {@code commandPrefix} {@code null} = règle
     * d'outil ; non nul = règle de préfixe de commande (n'a de sens que pour {@code bash}).
     */
    @Transactional
    public void setRule(UUID userId, UUID workspaceId, String tool, String commandPrefix,
            PermissionEffect effect) {
        String normalizedPrefix = commandPrefix == null || commandPrefix.isBlank()
                ? null : commandPrefix.trim();
        Optional<AtelierPermissionRule> existing = normalizedPrefix == null
                ? repository.findByUserIdAndWorkspaceIdAndToolAndCommandPrefixIsNull(userId, workspaceId, tool)
                : repository.findByUserIdAndWorkspaceIdAndToolAndCommandPrefix(userId, workspaceId, tool,
                        normalizedPrefix);
        if (existing.isPresent()) {
            AtelierPermissionRule rule = existing.get();
            rule.setEffect(effect.name());
            repository.save(rule);
            return;
        }
        repository.save(AtelierPermissionRule.builder()
                .userId(userId).workspaceId(workspaceId).tool(tool)
                .commandPrefix(normalizedPrefix).effect(effect.name()).build());
    }

    /** Une commande est-elle couverte par un préfixe : même premier mot, ou début de commande. */
    private static boolean commandMatches(String command, String prefix) {
        String normalized = command.strip();
        return firstToken(normalized).equals(prefix) || normalized.startsWith(prefix);
    }

    /** Le premier mot d'une commande (jusqu'à la première espace), en minuscules — la clef d'une règle bash. */
    static String firstToken(String command) {
        String trimmed = command.strip().toLowerCase(Locale.ROOT);
        int space = trimmed.indexOf(' ');
        int tab = trimmed.indexOf('\t');
        int cut = space < 0 ? tab : (tab < 0 ? space : Math.min(space, tab));
        return cut < 0 ? trimmed : trimmed.substring(0, cut);
    }
}
