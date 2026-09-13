package fr.claudegateway.mail;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * <b>Pas de secret dans un courriel</b> (F-110 / SF-110-02, cadrage §4) : détecte ce qui est
 * <b>manifestement</b> un mot de passe, un jeton ou une clé.
 *
 * <p>Volontairement étroit : on cherche des formes reconnaissables (préfixes de fournisseurs, blocs de clé
 * privée, affectation explicite d'un mot de passe), pas une entropie. Un faux positif bloquerait un compte
 * rendu légitime ; les formes retenues n'apparaissent pas dans une prose ordinaire. La phrase de refus nomme la
 * <b>nature</b> du secret, jamais sa valeur.</p>
 */
public final class ClientMailSecrets {

    private record Rule(String label, Pattern pattern) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("une clé privée", Pattern.compile("-----BEGIN (?:[A-Z]+ )*PRIVATE KEY-----")),
            new Rule("une clé d'accès AWS", Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b")),
            new Rule("un jeton GitHub", Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{40,})")),
            new Rule("un jeton Slack", Pattern.compile("\\bxox[abposr]-[A-Za-z0-9-]{10,}")),
            new Rule("une clé Stripe", Pattern.compile("\\b(?:sk|rk)_live_[A-Za-z0-9]{16,}")),
            new Rule("une clé d'API Anthropic", Pattern.compile("\\bsk-ant-[A-Za-z0-9_-]{20,}")),
            new Rule("une clé d'API", Pattern.compile("\\bsk-(?:proj-)?[A-Za-z0-9_-]{32,}")),
            new Rule("une clé d'API Google", Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b")),
            new Rule("un jeton JWT", Pattern.compile(
                    "\\beyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}")),
            new Rule("un jeton d'authentification", Pattern.compile(
                    "(?i)\\bauthorization\\s*:\\s*(?:bearer|basic)\\s+[A-Za-z0-9._~+/=-]{16,}")),
            new Rule("un mot de passe", Pattern.compile(
                    "(?i)\\b(?:password|passwd|pwd|mot de passe|mdp|secret|api[_ -]?key|token)\\s*[:=]\\s*\\S{6,}")));

    private ClientMailSecrets() {
    }

    /**
     * La nature du premier secret manifeste trouvé.
     *
     * @param text objet ou corps du courriel ({@code null} toléré)
     * @return par exemple « une clé privée », ou vide si rien n'est manifeste
     */
    public static Optional<String> find(String text) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(text).find()) {
                return Optional.of(rule.label());
            }
        }
        return Optional.empty();
    }
}
