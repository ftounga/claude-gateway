package fr.claudegateway.mail;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
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
                    "(?i)\\bauthorization\\s*:\\s*(?:bearer|basic)\\s+[A-Za-z0-9._~+/=-]{16,}")));

    /**
     * Libellé de la règle générique : un mot-clé de secret affecté d'une <b>valeur</b> (SF-110-07).
     */
    static final String PASSWORD_LABEL = "un mot de passe";

    /** {@code mot-clé [:=] candidat} ; le candidat n'est un secret que s'il passe {@link #isSecretValue}. */
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)\\b(?:password|passwd|pwd|mot de passe|mdp|secret|api[_ -]?key|token)\\s*[:=]\\s*(\\S{6,})");

    /** Encadrement retiré du candidat avant examen : guillemets, accents graves, parenthèses, ponctuation. */
    private static final Pattern FRAME = Pattern.compile("^[\"'`«»()\\[\\]{},;.!?]+|[\"'`«»()\\[\\]{},;.!?]+$");

    /** Un <b>nom</b> (paramètre, mot) : des lettres seules, éventuellement séparées par {@code _ . -}. */
    private static final Pattern NAME = Pattern.compile("\\p{L}+(?:[_.-]\\p{L}+)*");

    /** Un <b>masque</b> ({@code ********}, {@code xxxxxx}) : rien qui puisse être une valeur. */
    private static final Pattern MASK = Pattern.compile("[*•xX._-]+");

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
        // Chaque occurrence est examinée : un nom de paramètre plus haut ne doit pas masquer une vraie valeur
        // plus bas.
        Matcher assignment = ASSIGNMENT.matcher(text);
        while (assignment.find()) {
            if (isSecretValue(assignment.group(1))) {
                return Optional.of(PASSWORD_LABEL);
            }
        }
        return Optional.empty();
    }

    /**
     * Ce qui suit {@code password:} est-il une <b>valeur</b> de secret, ou seulement un nom, un masque, un gabarit
     * (F-110 / SF-110-07) ?
     *
     * <p>Pourquoi : la règle ne regardait que la longueur, et {@code Token:KeyCrt} — un <b>nom</b> de paramètre
     * dans une demande de rotation — a fait refuser un courriel légitime en prod. Un document qui parle de secrets
     * en nomme forcément. Limite assumée : un mot de passe fait de lettres seules n'est plus reconnu ici, car rien
     * dans sa forme ne le distingue d'un nom.</p>
     */
    static boolean isSecretValue(String candidate) {
        String value = FRAME.matcher(candidate).replaceAll("");
        if (value.length() < 6) {
            return false;
        }
        if (value.startsWith("${") || value.startsWith("{{") || value.startsWith("<") || value.startsWith("%")) {
            return false;
        }
        return !NAME.matcher(value).matches() && !MASK.matcher(value).matches();
    }
}
