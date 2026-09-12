package fr.claudegateway.governance;

import java.util.ArrayList;
import java.util.List;

import fr.claudegateway.governance.dto.GovernanceMapSectionView;

/**
 * Le <b>résumé</b> d'un fichier de carte (F-92 / SF-92-02) : son titre, ses sections, et le nombre de
 * <b>faits</b> que chacune porte.
 *
 * <p><b>Pourquoi compter.</b> Le but de la feature, dans les mots du PO, est que « à chaque projet
 * qu'on rajoute, la connaissance de l'infra augmente ». Un écran qui se contenterait de dire « six
 * fichiers présents » ne montrerait jamais cette augmentation : il dirait la même chose le premier
 * jour et le centième. Le seul chiffre qui bouge, c'est le nombre de faits — et c'est donc celui
 * qu'on rend.</p>
 *
 * <p><b>Un gabarit livré compte zéro.</b> C'est la propriété qui fait tenir toute la jauge : si les
 * consignes, les en-têtes de tableau et les cases vides comptaient, la carte afficherait déjà
 * plusieurs dizaines de « faits » à l'activation, et le chiffre ne voudrait plus rien dire.</p>
 *
 * <p><b>C'est une jauge, jamais une autorité.</b> Aucune décision produit ne s'attache à ce
 * compte : il ne bloque rien, il ne refuse rien. Juger la <i>qualité</i> d'un fait — daté ?
 * sourcé ? — est le sujet de F-95, et il demande autre chose qu'un compteur de lignes.</p>
 *
 * <p>Classe <b>pure</b> : aucune entrée-sortie, aucun état. C'est ce qui la rend testable ligne à
 * ligne, et c'est là que vit toute la subtilité du compte.</p>
 */
public final class GovernanceMapDigest {

    private GovernanceMapDigest() {
    }

    /** Ce qu'un fichier de carte porte : son titre, ses sections, et son total de faits. */
    public record Digest(String title, List<GovernanceMapSectionView> sections, int facts) {
    }

    /**
     * Résume un contenu Markdown de carte.
     *
     * @param content contenu du fichier, éventuellement {@code null} ou vide
     * @param fallbackTitle titre de repli quand le fichier ne porte pas de titre de niveau 1
     */
    public static Digest of(String content, String fallbackTitle) {
        if (content == null || content.isBlank()) {
            return new Digest(fallbackTitle, List.of(), 0);
        }
        String[] lines = content.split("\n", -1);
        boolean[] fact = new boolean[lines.length];
        String title = null;
        List<String> sectionTitles = new ArrayList<>();
        List<Integer> sectionFacts = new ArrayList<>();
        List<Integer> sectionOf = new ArrayList<>();
        int current = -1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.startsWith("# ") && title == null) {
                title = line.substring(2).strip();
            }
            if (line.startsWith("## ")) {
                sectionTitles.add(line.substring(3).strip());
                sectionFacts.add(0);
                current = sectionTitles.size() - 1;
            }
            sectionOf.add(current);
            fact[i] = isFact(line);
            if (fact[i] && isTableSeparator(line)) {
                fact[i] = false;
            }
            if (isTableSeparator(line) && i > 0) {
                // La ligne juste au-dessus d'une séparation est l'EN-TÊTE du tableau, pas un fait.
                // On ne peut le savoir qu'après coup : c'est la séparation qui le révèle.
                fact[i - 1] = false;
            }
        }

        int total = 0;
        for (int i = 0; i < lines.length; i++) {
            if (!fact[i]) {
                continue;
            }
            total++;
            int section = sectionOf.get(i);
            if (section >= 0) {
                sectionFacts.set(section, sectionFacts.get(section) + 1);
            }
        }

        List<GovernanceMapSectionView> sections = new ArrayList<>(sectionTitles.size());
        for (int i = 0; i < sectionTitles.size(); i++) {
            sections.add(new GovernanceMapSectionView(sectionTitles.get(i), sectionFacts.get(i)));
        }
        return new Digest(title == null || title.isBlank() ? fallbackTitle : title,
                List.copyOf(sections), total);
    }

    // -------------------------------------------------------------- internes

    /**
     * Vrai si cette ligne porte un fait.
     *
     * <p>Tout ce qu'un gabarit livré contient est écarté ici, et rien d'autre : titres, consignes
     * (citation ou italique seul), structure de tableau, cellules vides, cases à cocher vides.</p>
     */
    private static boolean isFact(String line) {
        if (line.isEmpty() || line.startsWith("#")) {
            return false;
        }
        if (line.startsWith(">")) {
            return false; // Une citation est une consigne du gabarit, jamais un fait.
        }
        if (line.startsWith("|")) {
            return hasFilledCell(line);
        }
        String body = bulletBody(line);
        if (body != null) {
            return !body.isEmpty() && !isEmphasisOnly(body);
        }
        return !isEmphasisOnly(line);
    }

    /** Vrai si la ligne est la séparation d'un tableau ({@code |---|---|}). */
    private static boolean isTableSeparator(String line) {
        if (!line.startsWith("|")) {
            return false;
        }
        String stripped = line.replace("|", "").replace(":", "").replace("-", "").replace(" ", "");
        return stripped.isEmpty() && line.contains("-");
    }

    /** Vrai si au moins une cellule de cette ligne de tableau porte quelque chose. */
    private static boolean hasFilledCell(String line) {
        for (String cell : line.split("\\|")) {
            String value = cell.strip();
            if (!value.isEmpty() && !isEmphasisOnly(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Le texte d'une puce ou d'une case à cocher, ou {@code null} si la ligne n'en est pas une.
     *
     * <p>Une case vide ({@code - [ ]}) est la marque du gabarit « ce qui reste à cartographier » :
     * elle dit ce qu'on ne sait pas encore, et ce n'est pas un fait.</p>
     */
    private static String bulletBody(String line) {
        if (!line.startsWith("- ") && !line.equals("-") && !line.startsWith("* ")
                && !line.equals("*")) {
            return null;
        }
        String body = line.length() <= 1 ? "" : line.substring(2).strip();
        if (body.startsWith("[ ]") || body.startsWith("[x]") || body.startsWith("[X]")) {
            body = body.substring(3).strip();
        }
        return body;
    }

    /**
     * Vrai si le texte n'est qu'une <b>consigne en italique</b> ({@code _…_} ou {@code *…*}).
     *
     * <p>C'est la forme qu'emploient tous les gabarits pour expliquer ce qu'une section attend. Du
     * gras ({@code **…**}) n'est pas visé : un fait mis en valeur reste un fait.</p>
     */
    private static boolean isEmphasisOnly(String text) {
        if (text.length() < 2) {
            return false;
        }
        boolean underscores = text.startsWith("_") && text.endsWith("_");
        boolean stars = text.startsWith("*") && text.endsWith("*") && !text.startsWith("**");
        return underscores || stars;
    }
}
