package fr.claudegateway.governance.integrite;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Les <b>chemins du poste</b> qu'un fichier de carte cite (F-95 / SF-95-01).
 *
 * <p>C'est la matière du contrôle des <b>liens morts</b> — les références à des chemins qui
 * n'existent plus. <b>C'est la façon dont une carte pourrit sans qu'on s'en aperçoive</b> : rien ne
 * casse, rien ne prévient, et le jour où l'on suit la référence, elle ne mène nulle part depuis six
 * mois.</p>
 *
 * <h2>La liste d'exceptions</h2>
 *
 * <p>Le prompt d'origine la réclame, et sans elle le contrôle serait inutilisable : une carte cite
 * beaucoup de chemins <b>volontairement indicatifs</b>. Six catégories sont donc écartées, et
 * chacune pour une raison qui lui est propre :</p>
 *
 * <ol>
 *   <li><b>Les citations et les blocs de code.</b> C'est le texte du gabarit : ses exemples sont
 *       indicatifs par construction. Cette seule règle écarte l'essentiel du bruit, parce que tous
 *       les gabarits de F-92 rangent leurs consignes en {@code >}.</li>
 *   <li><b>Les URL.</b> Elles ne désignent pas un chemin du poste — et vérifier qu'un lien web
 *       répond serait un tout autre métier.</li>
 *   <li><b>Les chemins absolus</b> ({@code /etc/…}, {@code ~/…}, {@code C:\…}). Ils décrivent la
 *       machine du <b>client</b>, pas la racine du poste ; la carte en est pleine, et c'est son
 *       travail.</li>
 *   <li><b>Les formes à gabarit</b> ({@code repos/<dépôt>}, {@code …}, {@code *}) : une forme, pas
 *       un chemin.</li>
 *   <li><b>La liste déclarée</b> ci-dessous : les tournures d'exemple que les gabarits emploient.</li>
 *   <li><b>Les fichiers de la carte eux-mêmes</b> : leur absence est déjà dite par
 *       {@code carte/fichier-absent}, et le répéter rendrait deux corrections pour un seul
 *       problème.</li>
 * </ol>
 *
 * <p>Classe <b>pure</b> : aucune entrée-sortie. Savoir si un chemin existe encore est le travail de
 * SF-95-02, qui interroge la machine.</p>
 */
public final class MapReferences {

    private MapReferences() {
    }

    /** Références rendues par fichier. Au-delà, ce n'est plus une vérification, c'est un balayage. */
    public static final int MAX_REFERENCES = 10;

    /** Longueur au-delà de laquelle une chaîne n'est plus un chemin mais une phrase. */
    private static final int MAX_LONGUEUR = 200;

    /**
     * Les tournures d'exemple employées par les gabarits et les messages du produit.
     *
     * <p>Comparées en minuscules, sans accent inutile : ce sont des formes, et on ne veut pas qu'un
     * « Chemin/Vers » passe entre les mailles.</p>
     */
    private static final Set<String> EXCEPTIONS_DECLAREES = Set.of(
            "chemin/vers", "chemin/vers/fichier", "dossier/fichier", "projet/state.md",
            "projet/plan-action.md", "dossier/state.md", "repos/depot", "repos/nom-du-depot",
            "src/foo.java", "and/or", "et/ou");

    /** Ce qui trahit une forme plutôt qu'un chemin réel. */
    private static final String CARACTERES_DE_GABARIT = "<>*${}?|…\"'";

    /** Cible d'un lien Markdown : {@code [texte](cible)}. */
    private static final Pattern LIEN = Pattern.compile("\\[[^\\]]*\\]\\(([^)\\s]+)");

    /** Chemin entre accents graves : {@code `migration-dns/STATE.md`}. */
    private static final Pattern CODE_EN_LIGNE = Pattern.compile("`([^`\\n]+)`");

    /**
     * Les chemins relatifs du poste cités par ce fichier de carte.
     *
     * @param contenu contenu du fichier, éventuellement {@code null}
     * @param fichiersDeLaCarte les noms des fichiers de carte du poste, écartés des références
     * @return les références, dédoublonnées, dans l'ordre du fichier et bornées ; jamais
     *         {@code null}, et aucune exception
     */
    public static List<String> of(String contenu, Set<String> fichiersDeLaCarte) {
        if (contenu == null || contenu.isBlank()) {
            return List.of();
        }
        Set<String> trouvees = new LinkedHashSet<>();
        boolean dansUnBlocDeCode = false;
        for (String brute : contenu.split("\n", -1)) {
            String ligne = brute.strip();
            if (ligne.startsWith("```") || ligne.startsWith("~~~")) {
                dansUnBlocDeCode = !dansUnBlocDeCode;
                continue;
            }
            if (dansUnBlocDeCode || ligne.startsWith(">")) {
                continue; // Consigne ou exemple du gabarit : indicatif par construction.
            }
            collecte(LIEN, ligne, trouvees, fichiersDeLaCarte);
            collecte(CODE_EN_LIGNE, ligne, trouvees, fichiersDeLaCarte);
            if (trouvees.size() >= MAX_REFERENCES) {
                break;
            }
        }
        return trouvees.stream().limit(MAX_REFERENCES).toList();
    }

    // ------------------------------------------------------------- internes

    private static void collecte(Pattern motif, String ligne, Set<String> trouvees,
            Set<String> fichiersDeLaCarte) {
        Matcher matcher = motif.matcher(ligne);
        while (matcher.find() && trouvees.size() < MAX_REFERENCES) {
            String candidat = matcher.group(1).strip();
            if (estUnChemin(candidat) && !estUneException(candidat, fichiersDeLaCarte)) {
                trouvees.add(nettoie(candidat));
            }
        }
    }

    /** Vrai si cette chaîne <b>ressemble</b> à un chemin relatif du poste. */
    private static boolean estUnChemin(String candidat) {
        if (candidat.isEmpty() || candidat.length() > MAX_LONGUEUR || candidat.contains(" ")) {
            return false;
        }
        String minuscule = candidat.toLowerCase(Locale.ROOT);
        return minuscule.contains("/") || minuscule.endsWith(".md");
    }

    /** Vrai si cette référence est volontairement indicative — les six catégories du javadoc. */
    private static boolean estUneException(String candidat, Set<String> fichiersDeLaCarte) {
        String minuscule = nettoie(candidat).toLowerCase(Locale.ROOT);
        if (minuscule.isEmpty()) {
            return true;
        }
        for (char interdit : CARACTERES_DE_GABARIT.toCharArray()) {
            if (minuscule.indexOf(interdit) >= 0) {
                return true;
            }
        }
        if (minuscule.contains("://") || minuscule.startsWith("mailto:")
                || minuscule.startsWith("www.") || minuscule.startsWith("#")) {
            return true;
        }
        if (minuscule.startsWith("/") || minuscule.startsWith("~") || minuscule.startsWith("\\")
                || minuscule.matches("^[a-z]:[/\\\\].*")) {
            return true; // Chemin absolu : c'est la machine du client, pas la racine du poste.
        }
        if (minuscule.startsWith("..")) {
            return true; // Au-dessus de la racine : hors de ce que le poste sait lire.
        }
        if (EXCEPTIONS_DECLAREES.contains(minuscule)) {
            return true;
        }
        return fichiersDeLaCarte != null && fichiersDeLaCarte.stream()
                .anyMatch(fichier -> fichier.toLowerCase(Locale.ROOT).equals(minuscule));
    }

    /** Retire la ponctuation de fin de phrase et le {@code ./} de tête : « acces.md, » = « acces.md ». */
    private static String nettoie(String candidat) {
        String valeur = candidat.strip();
        while (!valeur.isEmpty() && ",;.:!)".indexOf(valeur.charAt(valeur.length() - 1)) >= 0
                && !valeur.toLowerCase(Locale.ROOT).endsWith(".md")) {
            valeur = valeur.substring(0, valeur.length() - 1);
        }
        if (valeur.startsWith("./")) {
            valeur = valeur.substring(2);
        }
        while (valeur.endsWith("/")) {
            valeur = valeur.substring(0, valeur.length() - 1);
        }
        return valeur;
    }
}
