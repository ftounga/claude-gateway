package fr.claudegateway.governance;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.ProjectRulesSource;

/**
 * Les règles des paquets actifs, prêtes à rejoindre la consigne système du projet
 * (F-51 / SF-51-04).
 *
 * <p>C'est le premier des trois niveaux de gouvernance : la <b>convention</b>. Elle se demande au
 * modèle, elle ne s'impose pas — c'est précisément pourquoi les <b>contrôles</b> existent
 * ({@link GovernanceWriteCheckpoint}, {@link GovernanceEndOfTurnCheckpoint}), et pourquoi ce texte
 * n'a aucun pouvoir au-delà de ce que le modèle veut bien en faire.</p>
 *
 * <p><b>Chaque règle est nommée.</b> Un bloc anonyme ne dirait pas d'où vient une consigne, et le
 * jour où deux paquets se contredisent, l'utilisateur n'aurait aucun moyen de savoir lequel
 * décrocher.</p>
 *
 * <p><b>Le bloc est borné.</b> La consigne système entière a déjà sa borne côté boucle ; sans borne
 * propre, un paquet bavard pourrait la consommer à lui seul et faire disparaître les conventions du
 * projet <b>en silence</b> — exactement le mode d'échec qu'on ne verrait pas.</p>
 */
@Component
public class GovernanceRulesProvider implements ProjectRulesSource {

    private static final Logger log = LoggerFactory.getLogger(GovernanceRulesProvider.class);

    /** Longueur maximale du bloc de règles, toutes activations confondues (arbitrage C4). */
    public static final int MAX_RULES_BLOCK_CHARS = 12_000;

    /** Ce qui remplace la fin coupée : une troncature muette serait pire que la coupe. */
    static final String TRUNCATION_NOTICE =
            "\n… (règles de gouvernance tronquées : le catalogue actif est trop long)";

    private final GovernanceActivationService activationService;
    private final GovernancePackageService packageService;

    public GovernanceRulesProvider(GovernanceActivationService activationService,
            GovernancePackageService packageService) {
        this.activationService = activationService;
        this.packageService = packageService;
    }

    @Override
    public String rulesFor(UUID userId, UUID workspaceId) {
        if (userId == null || workspaceId == null) {
            return null; // On n'invente pas un propriétaire pour aller chercher des règles.
        }
        List<GovernanceActivation> active;
        try {
            active = activationService.activeOnWorkspace(userId, workspaceId);
        } catch (RuntimeException ex) {
            log.debug("Règles de gouvernance illisibles ({})", ex.getClass().getSimpleName());
            return null;
        }
        if (active.isEmpty()) {
            // Le cas de très loin le plus fréquent : aucune lecture de paquet n'est faite.
            return null;
        }

        StringBuilder block = new StringBuilder();
        for (GovernanceActivation activation : active) {
            GovernancePackage pkg;
            try {
                pkg = packageService.require(activation.getPackageId());
            } catch (RuntimeException ex) {
                continue; // Un paquet disparu n'empêche pas les autres de s'appliquer.
            }
            String rules = pkg.getRules();
            if (rules == null || rules.isBlank()) {
                continue; // Pas de titre vide : un paquet sans règle ne dit rien.
            }
            block.append("## ").append(pkg.getName()).append('\n')
                    .append(rules.strip()).append("\n\n");
        }
        String result = block.toString().stripTrailing();
        if (result.isEmpty()) {
            return null;
        }
        return result.length() <= MAX_RULES_BLOCK_CHARS
                ? result
                : result.substring(0, MAX_RULES_BLOCK_CHARS) + TRUNCATION_NOTICE;
    }
}
