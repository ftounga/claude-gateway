package fr.claudegateway.atelier.actions;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * <b>Aucun secret dans le registre des accès</b> (F-180 / SF-180-04, décision D4). Le registre dit
 * <i>qu'</i>un accès existe, jamais <i>comment</i> l'utiliser : un mot de passe ou un jeton recopié ici
 * deviendrait une fuite (liste jointe au tour, écran, export).
 *
 * <p>Trois filets, du plus sûr au plus large :</p>
 * <ol>
 *   <li>un mot-clé de secret suivi d'une valeur (« mot de passe : x », « token=… », « mdp x ») ;</li>
 *   <li>un jeton reconnaissable (JWT, GitHub, Slack, AWS, OpenAI/Anthropic, GitLab, Google, clé PEM) ;</li>
 *   <li>une chaîne sans espace de 32 caractères ou plus, mêlant majuscules, minuscules et chiffres, sans
 *       {@code / : . @} — la forme d'un secret, pas d'un ARN, d'une URL ni d'une adresse.</li>
 * </ol>
 * <p>Le refus ne recopie <b>jamais</b> la valeur (ni dans le message, ni dans un journal).</p>
 */
public final class AccesSecretGuard {

    static final String MESSAGE = "Le registre ne garde aucun secret : retirez le mot de passe ou le jeton "
            + "(gardez seulement le système, le compte et qui l'a donné).";

    /** Un mot-clé de secret suivi d'un séparateur et d'une valeur : « token: … », « mdp = … ». */
    private static final Pattern KEYWORD_WITH_VALUE = Pattern.compile(
            "(mot de passe|motdepasse|password|passwd|pwd|mdp|passphrase|secret|token|jeton|api[ _-]?key"
                    + "|cle d api|cle api|private[ _-]?key)\\s*[:=]\\s*\\S{3,}");

    /**
     * Un mot-clé fort suivi d'un mot qui a la forme d'un mot de passe (chiffre ou symbole) : « mot de passe
     * Azerty123! ». « mot de passe envoyé par Rémi » reste permis — il ne dit rien du secret.
     */
    private static final Pattern STRONG_KEYWORD_WITH_WORD = Pattern.compile(
            "(mot de passe|motdepasse|password|passwd|pwd|mdp|passphrase|bearer)\\s+\\S*[0-9!#$%^&*_+=?]\\S*");

    private static final List<Pattern> KNOWN_TOKENS = List.of(
            Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
            Pattern.compile("\\b(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}"),
            Pattern.compile("github_pat_[A-Za-z0-9_]{20,}"),
            Pattern.compile("xox[abprs]-[A-Za-z0-9-]{10,}"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}"),
            Pattern.compile("glpat-[A-Za-z0-9_-]{20,}"),
            Pattern.compile("AIza[0-9A-Za-z_-]{35}"),
            Pattern.compile("-----BEGIN [A-Z ]*(PRIVATE KEY|CERTIFICATE)-----"));

    private static final Pattern RUN = Pattern.compile("\\S{32,}");

    private AccesSecretGuard() {
    }

    /** Vrai si la valeur ressemble à un secret. */
    public static boolean looksSecret(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String folded = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replace('’', ' ').replace('\'', ' ');
        if (KEYWORD_WITH_VALUE.matcher(folded).find() || STRONG_KEYWORD_WITH_WORD.matcher(folded).find()) {
            return true;
        }
        for (Pattern token : KNOWN_TOKENS) {
            if (token.matcher(value).find()) {
                return true;
            }
        }
        var runs = RUN.matcher(value);
        while (runs.find()) {
            String run = runs.group();
            if (run.matches(".*[A-Z].*") && run.matches(".*[a-z].*") && run.matches(".*[0-9].*")
                    && !run.matches(".*[/:.@].*")) {
                return true;
            }
        }
        return false;
    }

    /** Refuse la valeur si elle ressemble à un secret — sans jamais la recopier. */
    public static void check(String value) {
        if (looksSecret(value)) {
            throw new InvalidTerminalActionException(MESSAGE);
        }
    }
}
