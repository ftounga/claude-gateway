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

    /** Longueur maximale de la phrase de rôle substituée à l'amorce (F-148 / SF-148-02). */
    static final int PROFILE_ROLE_MAX_CHARS = 400;

    @Override
    public String activeProfileRole(UUID userId, UUID workspaceId) {
        if (userId == null || workspaceId == null) {
            return null; // On n'invente pas un propriétaire pour aller chercher un profil.
        }
        List<GovernanceActivation> active;
        try {
            active = activationService.activeOnWorkspace(userId, workspaceId);
        } catch (RuntimeException ex) {
            log.debug("Profil actif illisible ({})", ex.getClass().getSimpleName());
            return null; // Repli passant : l'amorce générique, jamais un tour raté.
        }
        for (GovernanceActivation activation : active) {
            GovernancePackage pkg;
            try {
                pkg = packageService.require(activation.getPackageId());
            } catch (RuntimeException ex) {
                continue; // Un paquet disparu n'empêche pas de trouver un profil parmi les autres.
            }
            if (pkg.getSlug() == null
                    || !pkg.getSlug().startsWith(GovernanceProfileSeeder.PROFILE_SLUG_PREFIX)) {
                continue; // Pas un profil : ne peut pas fournir de phrase de rôle.
            }
            String role = roleSentenceOf(pkg.getRules());
            if (role != null) {
                // Le PREMIER profil actif (ordre d'activation) donne le cadre : un seul rôle en tête.
                return role;
            }
        }
        return null;
    }

    /**
     * La <b>1re phrase</b> du texte d'un profil, prête à servir d'amorce de rôle
     * (F-148 / SF-148-02).
     *
     * <p>Chaque profil livré ouvre par une phrase de rôle (« Tu interviens comme architecte… »). On la
     * rend en prose : marqueurs de gras Markdown retirés, espaces normalisés, bornée. La coupe à la 1re
     * phrase évite d'injecter tout le profil en tête — il vit déjà en entier dans le bloc de règles.</p>
     *
     * @return la phrase, ou {@code null} s'il n'y a rien d'exploitable
     */
    static String roleSentenceOf(String rules) {
        if (rules == null || rules.isBlank()) {
            return null;
        }
        String text = rules.strip().replace("**", "").replaceAll("\\s+", " ").strip();
        if (text.isEmpty()) {
            return null;
        }
        int end = text.indexOf(". ");
        String sentence = end >= 0 ? text.substring(0, end + 1) : text;
        if (sentence.length() > PROFILE_ROLE_MAX_CHARS) {
            sentence = sentence.substring(0, PROFILE_ROLE_MAX_CHARS).stripTrailing();
        }
        return sentence.isBlank() ? null : sentence;
    }
}
