package fr.claudegateway.runner.host;

/**
 * Chemin d'un projet <b>sous la racine du poste</b> (F-48 / SF-48-01) : la valeur qui voyage dans
 * chaque appel d'outil et sur laquelle le runner referme son confinement (SF-48-02, régime local).
 *
 * <p>Forme canonique : séparateur {@code /}, ni chemin absolu, ni lettre de lecteur, ni {@code ..},
 * ni segment vide. La chaîne <b>vide</b> est légitime et signifie « la racine du poste elle-même » —
 * un poste peut n'héberger qu'un projet, et c'est alors la racine.</p>
 *
 * <p>La gateway normalise ici <b>avant de stocker</b>. Ce n'est pas la garde de sécurité : celle qui
 * fait foi reste celle du runner, qui canonicalise et refuse de sortir (décision n° 2 du cadrage,
 * non réversible). C'est simplement le refus d'écrire en base une valeur qu'aucun runner
 * n'accepterait.</p>
 */
public final class RunnerProjectPath {

    /** Longueur maximale acceptée pour un chemin relatif de projet. */
    public static final int MAX_LENGTH = 512;

    private RunnerProjectPath() {
    }

    /**
     * Forme canonique du chemin relatif, ou chaîne vide pour la racine du poste.
     *
     * @throws InvalidProjectPathException si le chemin est absolu, remonte, porte un octet nul ou
     *                                     dépasse {@link #MAX_LENGTH}
     */
    public static String normalize(String rawPath) {
        if (rawPath == null) {
            return "";
        }
        String path = rawPath.trim().replace('\\', '/');
        if (path.isEmpty()) {
            return "";
        }
        if (path.length() > MAX_LENGTH) {
            throw new InvalidProjectPathException("Chemin du projet trop long (512 caractères au plus).");
        }
        if (path.indexOf('\0') >= 0) {
            throw new InvalidProjectPathException("Chemin du projet invalide.");
        }
        if (path.startsWith("/")) {
            throw new InvalidProjectPathException(
                    "Le chemin du projet est relatif à la racine du poste : il ne commence pas par « / ».");
        }
        if (path.length() >= 2 && path.charAt(1) == ':' && Character.isLetter(path.charAt(0))) {
            throw new InvalidProjectPathException(
                    "Le chemin du projet est relatif à la racine du poste : pas de lettre de lecteur.");
        }
        StringBuilder normalized = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new InvalidProjectPathException(
                        "Le chemin du projet ne peut pas remonter au-dessus de la racine du poste.");
            }
            if (!normalized.isEmpty()) {
                normalized.append('/');
            }
            normalized.append(segment);
        }
        return normalized.toString();
    }
}
