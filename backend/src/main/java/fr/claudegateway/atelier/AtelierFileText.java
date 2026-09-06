package fr.claudegateway.atelier;

/**
 * Lecture numérotée/paginée et remplacement exact d'un texte de fichier (F-39 / SF-39-06).
 *
 * <p>Logique <b>pure</b>, volontairement séparée de la boucle : elle s'applique à l'identique aux
 * deux cibles d'exécution, que le contenu vienne du stockage objet ou de la machine de
 * l'utilisateur. Elle n'exige donc <b>aucune évolution du protocole runner</b> — un runner déjà
 * installé en bénéficie sans rien mettre à jour (décision D1).</p>
 */
final class AtelierFileText {

    /** Lignes rendues par défaut, et au maximum : au-delà, le fichier noie le tour. */
    static final int MAX_LINES = 2_000;
    /** Longueur d'une ligne rendue : un fichier minifié ne doit pas remplir le contexte à lui seul. */
    static final int MAX_LINE_CHARS = 2_000;
    static final String EMPTY = "(fichier vide)";

    private AtelierFileText() {
    }

    /**
     * Rend une page de lignes numérotées.
     *
     * <p>Les numéros ne sont pas décoratifs : sans eux, l'agent ne peut ni dire où il a vu quelque
     * chose, ni demander la suite d'un fichier — il relit tout, à chaque fois.</p>
     *
     * @param content contenu complet du fichier
     * @param offset  première ligne rendue (1 par défaut ; valeur invalide ⇒ 1)
     * @param limit   nombre de lignes (défaut et maximum {@link #MAX_LINES})
     * @throws InvalidFilePathException si {@code offset} dépasse la fin du fichier — le message
     *                                  nomme le nombre réel de lignes pour que l'agent se corrige
     */
    static String numbered(String content, Integer offset, Integer limit) {
        if (content == null || content.isEmpty()) {
            return EMPTY;
        }
        String[] lines = content.split("\n", -1);
        int total = lines.length;
        // Un fichier terminé par un saut de ligne produit une dernière entrée vide : elle n'est pas
        // une ligne du fichier, et l'afficher ferait croire à une ligne de plus.
        if (total > 1 && lines[total - 1].isEmpty()) {
            total--;
        }
        int from = offset == null || offset < 1 ? 1 : offset;
        int count = limit == null || limit < 1 || limit > MAX_LINES ? MAX_LINES : limit;
        if (from > total) {
            throw new InvalidFilePathException(
                    "Le fichier ne compte que " + total + " ligne(s) : offset " + from + " est au-delà de la fin.");
        }
        int to = Math.min(total, from + count - 1);
        StringBuilder page = new StringBuilder();
        for (int index = from; index <= to; index++) {
            page.append(String.format("%6d", index)).append('→')
                    .append(boundLine(lines[index - 1])).append('\n');
        }
        if (to < total) {
            page.append("… (lignes ").append(from).append(" à ").append(to).append(" sur ").append(total)
                    .append(" ; relance read_file avec offset=").append(to + 1).append(")");
        }
        return page.toString();
    }

    private static String boundLine(String line) {
        return line.length() <= MAX_LINE_CHARS ? line : line.substring(0, MAX_LINE_CHARS) + "…";
    }

    /**
     * Remplace un passage <b>littéral</b>. Aucune expression régulière : trop de façons de se
     * tromper pour le gain. Aucun numéro de ligne non plus — ils bougent dès la première édition,
     * le texte exact, non.
     *
     * @return le contenu modifié et le nombre de remplacements
     * @throws InvalidFilePathException si le texte est absent, présent plusieurs fois sans
     *                                  {@code replaceAll}, ou si l'édition ne change rien
     */
    static Edit replace(String content, String oldString, String newString, boolean replaceAll) {
        String source = content == null ? "" : content;
        String replacement = newString == null ? "" : newString;
        if (oldString == null || oldString.isEmpty()) {
            throw new InvalidFilePathException("Paramètre requis manquant : old_string");
        }
        if (oldString.equals(replacement)) {
            throw new InvalidFilePathException("Aucune modification demandée : old_string et new_string sont identiques.");
        }
        int occurrences = count(source, oldString);
        if (occurrences == 0) {
            throw new InvalidFilePathException(
                    "Texte introuvable : l'édition n'a rien changé. Relis le fichier avant de réessayer.");
        }
        if (occurrences > 1 && !replaceAll) {
            throw new InvalidFilePathException("Texte trouvé " + occurrences
                    + " fois : donne un extrait plus large, ou passe replace_all à true.");
        }
        String edited = replaceAll
                ? source.replace(oldString, replacement)
                : source.replaceFirst(java.util.regex.Pattern.quote(oldString),
                        java.util.regex.Matcher.quoteReplacement(replacement));
        return new Edit(edited, replaceAll ? occurrences : 1);
    }

    private static int count(String haystack, String needle) {
        int found = 0;
        int from = 0;
        int at;
        while ((at = haystack.indexOf(needle, from)) >= 0) {
            found++;
            from = at + needle.length();
        }
        return found;
    }

    /** Contenu après édition, et nombre de passages remplacés. */
    record Edit(String content, int replacements) {
    }
}
