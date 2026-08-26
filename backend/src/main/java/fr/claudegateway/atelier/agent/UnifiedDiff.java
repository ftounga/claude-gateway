package fr.claudegateway.atelier.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Calcul du <b>diff unifié</b> entre deux versions d'un fichier texte (F-37 / SF-37-01).
 *
 * <p>Écrit à la main, sur une <b>plus longue sous-séquence commune</b> (LCS) appliquée aux lignes :
 * aucune bibliothèque de comparaison n'est présente dans le projet, et en ajouter une pour comparer
 * des lignes de texte serait disproportionné.</p>
 *
 * <p>Le format produit est celui que tout développeur lit sans apprentissage : des sections
 * {@code @@ -a,b +c,d @@}, {@value #CONTEXT_LINES} lignes de contexte autour de chaque changement,
 * les lignes retirées préfixées {@code -} et les ajoutées {@code +}.</p>
 *
 * <p><b>Tout est borné avant de comparer</b>, jamais seulement à l'affichage :</p>
 * <ul>
 *   <li>le préfixe et le suffixe communs sont élagués d'abord — c'est ce qui rend le cas courant
 *       (quelques lignes changées dans un gros fichier) linéaire ;</li>
 *   <li>si la région restante dépasse {@value #MAX_LCS_CELLS} cellules de table, la comparaison fine
 *       est <b>abandonnée</b> au profit d'un repli en blocs (tout retiré, puis tout ajouté) : mieux
 *       vaut un diff grossier qu'un pic mémoire quadratique ;</li>
 *   <li>le diff rendu est tronqué sur une frontière de ligne au-delà de la borne demandée, et le
 *       nombre de lignes omises est rapporté.</li>
 * </ul>
 *
 * <p>Classe utilitaire pure : aucun état, aucune dépendance Spring, aucune dépendance fournisseur.</p>
 */
public final class UnifiedDiff {

    /** Lignes de contexte de part et d'autre d'un changement — le standard du format unifié. */
    public static final int CONTEXT_LINES = 3;

    /**
     * Plafond de la table de comparaison, en cellules. À {@code 2 000 000} d'entiers, le pic mémoire
     * transitoire reste de l'ordre de 8 Mo — au-delà, le repli en blocs prend le relais.
     */
    private static final long MAX_LCS_CELLS = 2_000_000L;

    private UnifiedDiff() {
    }

    /**
     * Diff unifié entre l'ancien et le nouveau contenu d'un fichier.
     *
     * @param path        chemin relatif au workspace (repris tel quel dans le résultat)
     * @param oldContent  ancien contenu, ou {@code null} si le fichier est <b>nouveau</b>
     * @param newContent  nouveau contenu (jamais {@code null} : une sortie vide est une chaîne vide)
     * @param maxDiffLines borne du nombre de lignes de diff rendues ; les suivantes sont comptées
     *                     comme omises. Une valeur {@code <= 0} retombe sur une ligne
     * @return la modification décrite ; {@code unreadable} quand un contenu n'est pas textuel
     */
    public static FileDiff between(String path, String oldContent, String newContent, int maxDiffLines) {
        boolean added = oldContent == null;
        String previous = added ? "" : oldContent;
        String current = newContent == null ? "" : newContent;
        if (isBinary(previous) || isBinary(current)) {
            // Un contenu non décodable doit se dire, pas exploser : le workspace est textuel, mais
            // rien n'empêche une sortie d'agent de ne pas l'être.
            return FileDiff.unreadable(path, added);
        }
        List<Op> ops = diffOps(splitLines(previous), splitLines(current));
        return render(path, added, ops, Math.max(1, maxDiffLines));
    }

    /**
     * Découpe un contenu en lignes. Un saut de ligne <b>final</b> est traité comme un terminateur, pas
     * comme une ligne vide supplémentaire : sans cela, tout fichier bien formé afficherait une
     * dernière ligne fantôme. La distinction « fichier sans saut de ligne final » est donc perdue —
     * elle n'apporte rien à la lecture d'un diff à l'écran.
     */
    static String[] splitLines(String content) {
        if (content.isEmpty()) {
            return new String[0];
        }
        String[] lines = content.split("\n", -1);
        if (lines.length > 1 && lines[lines.length - 1].isEmpty()) {
            String[] trimmed = new String[lines.length - 1];
            System.arraycopy(lines, 0, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return lines;
    }

    /** Un contenu portant un octet nul n'est pas du texte : aucune comparaison n'a de sens. */
    private static boolean isBinary(String content) {
        return content.indexOf('\0') >= 0;
    }

    /** Une opération d'édition : ligne conservée ({@code ' '}), retirée ({@code '-'}), ajoutée ({@code '+'}). */
    record Op(char type, String text) {

        boolean change() {
            return type != ' ';
        }

        boolean fromOld() {
            return type != '+';
        }

        boolean fromNew() {
            return type != '-';
        }
    }

    /**
     * Script d'édition menant de {@code oldLines} à {@code newLines}. Le préfixe et le suffixe communs
     * sont élagués d'abord : c'est ce qui garde le cas courant — quelques lignes changées dans un gros
     * fichier — linéaire, et ce qui borne la table de comparaison à la seule région remuée.
     */
    private static List<Op> diffOps(String[] oldLines, String[] newLines) {
        int prefix = 0;
        while (prefix < oldLines.length && prefix < newLines.length
                && oldLines[prefix].equals(newLines[prefix])) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < oldLines.length - prefix && suffix < newLines.length - prefix
                && oldLines[oldLines.length - 1 - suffix].equals(newLines[newLines.length - 1 - suffix])) {
            suffix++;
        }
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < prefix; i++) {
            ops.add(new Op(' ', oldLines[i]));
        }
        int oldMiddle = oldLines.length - suffix - prefix;
        int newMiddle = newLines.length - suffix - prefix;
        ops.addAll(middleOps(oldLines, newLines, prefix, oldMiddle, newMiddle));
        for (int i = newLines.length - suffix; i < newLines.length; i++) {
            ops.add(new Op(' ', newLines[i]));
        }
        return ops;
    }

    /** Comparaison fine de la région remuée, ou repli en blocs si elle sort de la borne mémoire. */
    private static List<Op> middleOps(String[] oldLines, String[] newLines, int from, int oldCount,
            int newCount) {
        List<Op> ops = new ArrayList<>();
        if (oldCount == 0 || newCount == 0
                || (long) (oldCount + 1) * (newCount + 1) > MAX_LCS_CELLS) {
            // Rien à apparier d'un côté, ou table hors borne : bloc retiré puis bloc ajouté. Le diff
            // est plus grossier, mais il reste exact — et il ne coûte rien en mémoire.
            for (int i = 0; i < oldCount; i++) {
                ops.add(new Op('-', oldLines[from + i]));
            }
            for (int j = 0; j < newCount; j++) {
                ops.add(new Op('+', newLines[from + j]));
            }
            return ops;
        }
        int[][] lcs = new int[oldCount + 1][newCount + 1];
        for (int i = oldCount - 1; i >= 0; i--) {
            for (int j = newCount - 1; j >= 0; j--) {
                lcs[i][j] = oldLines[from + i].equals(newLines[from + j])
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < oldCount && j < newCount) {
            if (oldLines[from + i].equals(newLines[from + j])) {
                ops.add(new Op(' ', oldLines[from + i]));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                ops.add(new Op('-', oldLines[from + i]));
                i++;
            } else {
                ops.add(new Op('+', newLines[from + j]));
                j++;
            }
        }
        while (i < oldCount) {
            ops.add(new Op('-', oldLines[from + i++]));
        }
        while (j < newCount) {
            ops.add(new Op('+', newLines[from + j++]));
        }
        return ops;
    }

    /**
     * Rend le script d'édition en sections unifiées bornées. Les changements distants de plus de
     * {@code 2 × CONTEXT_LINES} produisent des sections séparées ; les changements proches sont
     * fusionnés dans une seule, comme le fait tout outil de comparaison.
     */
    private static FileDiff render(String path, boolean added, List<Op> ops, int maxDiffLines) {
        List<int[]> sections = sections(ops);
        if (sections.isEmpty()) {
            return new FileDiff(path, added, "", 0, 0, 0, false);
        }
        int[] oldNumbers = new int[ops.size()];
        int[] newNumbers = new int[ops.size()];
        int oldLine = 0;
        int newLine = 0;
        for (int k = 0; k < ops.size(); k++) {
            Op op = ops.get(k);
            oldNumbers[k] = op.fromOld() ? ++oldLine : oldLine;
            newNumbers[k] = op.fromNew() ? ++newLine : newLine;
        }

        int total = 0;
        for (int[] section : sections) {
            total += 1 + (section[1] - section[0] + 1);
        }

        StringBuilder out = new StringBuilder();
        int emitted = 0;
        int addedLines = 0;
        int removedLines = 0;
        for (int[] section : sections) {
            // Place pour l'en-tête ET au moins une ligne : un `@@` seul ne dirait rien.
            if (emitted + 1 >= maxDiffLines) {
                break;
            }
            out.append(header(ops, oldNumbers, newNumbers, section)).append('\n');
            emitted++;
            for (int k = section[0]; k <= section[1] && emitted < maxDiffLines; k++) {
                Op op = ops.get(k);
                out.append(op.type()).append(op.text()).append('\n');
                emitted++;
                if (op.type() == '+') {
                    addedLines++;
                } else if (op.type() == '-') {
                    removedLines++;
                }
            }
        }
        if (out.length() > 0) {
            out.setLength(out.length() - 1); // le dernier saut de ligne n'est pas une ligne
        }
        return new FileDiff(path, added, out.toString(), addedLines, removedLines,
                Math.max(0, total - emitted), false);
    }

    /** Bornes {@code [début, fin]} de chaque section : les changements, étendus au contexte, fusionnés. */
    private static List<int[]> sections(List<Op> ops) {
        List<int[]> sections = new ArrayList<>();
        int start = -1;
        int end = -1;
        for (int k = 0; k < ops.size(); k++) {
            if (!ops.get(k).change()) {
                continue;
            }
            int from = Math.max(0, k - CONTEXT_LINES);
            int to = Math.min(ops.size() - 1, k + CONTEXT_LINES);
            if (start < 0) {
                start = from;
                end = to;
            } else if (from <= end + 1) {
                end = to;
            } else {
                sections.add(new int[] {start, end});
                start = from;
                end = to;
            }
        }
        if (start >= 0) {
            sections.add(new int[] {start, end});
        }
        return sections;
    }

    /**
     * En-tête {@code @@ -a,b +c,d @@} d'une section. Convention du format unifié : un décompte nul se
     * rapporte à la ligne <b>précédente</b>, d'où le début à zéro quand la section n'emprunte rien à
     * l'un des deux côtés (création de fichier, par exemple).
     */
    private static String header(List<Op> ops, int[] oldNumbers, int[] newNumbers, int[] section) {
        int oldCount = 0;
        int newCount = 0;
        for (int k = section[0]; k <= section[1]; k++) {
            if (ops.get(k).fromOld()) {
                oldCount++;
            }
            if (ops.get(k).fromNew()) {
                newCount++;
            }
        }
        int oldBefore = section[0] == 0 ? 0 : oldNumbers[section[0] - 1];
        int newBefore = section[0] == 0 ? 0 : newNumbers[section[0] - 1];
        int oldStart = oldCount == 0 ? oldBefore : oldBefore + 1;
        int newStart = newCount == 0 ? newBefore : newBefore + 1;
        return "@@ -" + oldStart + "," + oldCount + " +" + newStart + "," + newCount + " @@";
    }
}
