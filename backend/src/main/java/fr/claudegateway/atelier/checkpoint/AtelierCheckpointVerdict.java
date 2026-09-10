package fr.claudegateway.atelier.checkpoint;

/**
 * Ce qu'un contrôle rend (F-50 / SF-50-01) : la boucle continue, ou elle est <b>bloquée</b> avec
 * l'action corrective à rendre au modèle.
 *
 * <p><b>Le message porte le geste, pas le constat.</b> C'est la règle d'écriture du cadrage, et elle
 * n'est pas cosmétique : ce texte est lu par un modèle dont on attend une correction. « Le fichier
 * ne respecte pas la convention » ne se corrige pas ; « ajoute l'en-tête en tête de {@code Foo.java},
 * puis reprends » se corrige. Un contrôle qui bloque sans dire quoi faire laisse le modèle tourner
 * en rond jusqu'au plafond d'étapes.</p>
 */
public record AtelierCheckpointVerdict(boolean blocked, String correction) {

    /** Longueur d'une action corrective : au-delà, ce n'est plus une action, c'est un cahier des charges. */
    public static final int MAX_CORRECTION_CHARS = 2_000;

    private static final AtelierCheckpointVerdict PROCEED = new AtelierCheckpointVerdict(false, null);

    /** Compacte le verdict : {@code trim}, troncature, et une correction vide vaut {@code null}. */
    public AtelierCheckpointVerdict {
        correction = normalize(correction);
    }

    /** Rien à signaler : la boucle continue exactement comme si aucun contrôle n'existait. */
    public static AtelierCheckpointVerdict proceed() {
        return PROCEED;
    }

    /**
     * Bloque, et dit quoi faire.
     *
     * @param correction l'action corrective attendue du modèle ; {@code null} ou vide est accepté
     *                   mais donnera un message de repli — un blocage muet reste un blocage
     */
    public static AtelierCheckpointVerdict block(String correction) {
        return new AtelierCheckpointVerdict(true, correction);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= MAX_CORRECTION_CHARS
                ? trimmed
                : trimmed.substring(0, MAX_CORRECTION_CHARS);
    }
}
