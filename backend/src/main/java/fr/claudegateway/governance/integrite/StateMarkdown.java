package fr.claudegateway.governance.integrite;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Ce qu'un fichier de projet dit de son <b>statut</b> et de sa <b>dette</b> (F-95 / SF-95-01).
 *
 * <p>C'est la lecture qui distingue les deux niveaux du contrôle « dette et clôture » : un sujet
 * <b>clos</b> qui garde une case ouverte a perdu du savoir — c'est une <b>erreur</b> ; un sujet
 * <b>en cours</b> qui en garde une est dans un état parfaitement normal — c'est un
 * <b>avertissement</b>. Les confondre reviendrait soit à bloquer tous les tours de tous les projets
 * vivants, soit à ne jamais rien empêcher.</p>
 *
 * <p><b>Le défaut est « en cours », et c'est structurant.</b> Les {@code STATE.md} déjà déposés chez
 * les clients n'ont pas de section {@code Statut} — F-51 ne réécrit jamais un fichier posé, et la
 * mise à jour d'un gabarit existant est le sujet de F-96. Si l'absence valait « clos », la première
 * inspection bloquerait tous les tours de tous les postes déjà gouvernés.</p>
 *
 * <p><b>Les blocs de code et les citations ne comptent pas.</b> Le gabarit livré porte
 * {@code - [ ] cluster « atlas »} <b>dans un bloc d'exemple</b> : les compter ferait déclarer une
 * dette à un projet qui n'a encore rien écrit, et le contrôle perdrait toute crédibilité au premier
 * usage. Même raison pour les citations — tout ce qui est en {@code >} est une consigne du
 * gabarit.</p>
 *
 * <p>Classe <b>pure</b> : aucune entrée-sortie, aucun état, aucune exception. Elle se vérifie ligne
 * à ligne, et c'est là que vit toute la subtilité du compte.</p>
 */
public final class StateMarkdown {

    private StateMarkdown() {
    }

    /** Le statut d'un sujet, tel que son {@code STATE.md} le déclare. */
    public enum Statut {

        /** Le sujet vit. Une dette y est normale : on la dit, on ne la refuse pas. */
        EN_COURS,

        /** Le sujet est déclaré terminé. Plus rien ne doit y rester à promouvoir. */
        CLOS
    }

    /**
     * Ce qu'on a lu d'un fichier de projet.
     *
     * @param statut       le statut déclaré, {@link Statut#EN_COURS} à défaut
     * @param casesOuvertes nombre de {@code - [ ]} hors bloc de code et hors citation
     */
    public record Lecture(Statut statut, int casesOuvertes) {

        /** Vrai s'il reste quelque chose à promouvoir. */
        public boolean aDeLaDette() {
            return casesOuvertes > 0;
        }
    }

    /** Lecture neutre — un fichier absent ou vide ne déclare rien et ne doit rien. */
    public static final Lecture NEUTRE = new Lecture(Statut.EN_COURS, 0);

    /** Le titre de section qui porte le statut, dans le gabarit livré. */
    private static final String SECTION_STATUT = "statut";

    /** Les mots qui valent « clos ». Sans accent ni casse : on corrige le fond, pas la typographie. */
    private static final String[] MOTS_CLOS = {"clos", "cloture", "termine", "fini", "acheve"};

    /**
     * Lit un contenu de {@code STATE.md} (ou de {@code PLAN-ACTION.md}, dont seules les cases
     * comptent).
     *
     * @param contenu contenu du fichier, éventuellement {@code null}
     * @return la lecture ; <b>jamais</b> {@code null}, et aucune exception
     */
    public static Lecture of(String contenu) {
        if (contenu == null || contenu.isBlank()) {
            return NEUTRE;
        }
        String[] lignes = contenu.split("\n", -1);
        boolean dansUnBlocDeCode = false;
        boolean sectionStatut = false;
        Statut statut = null;
        int cases = 0;

        for (String brute : lignes) {
            String ligne = brute.strip();
            if (ligne.startsWith("```") || ligne.startsWith("~~~")) {
                dansUnBlocDeCode = !dansUnBlocDeCode;
                continue;
            }
            if (dansUnBlocDeCode || ligne.startsWith(">")) {
                // Exemple du gabarit ou consigne : ni un statut, ni une dette.
                continue;
            }
            if (ligne.startsWith("#")) {
                sectionStatut = estSectionStatut(ligne);
                continue;
            }
            if (statut == null) {
                if (sectionStatut && !ligne.isEmpty()) {
                    // La première ligne de la section « Statut » EST le statut, quelle que soit sa
                    // décoration (gras, puce, accents graves).
                    statut = statutDe(ligne);
                    sectionStatut = false;
                } else {
                    statut = statutInline(ligne);
                }
            }
            if (estCaseOuverte(ligne)) {
                cases++;
            }
        }
        return new Lecture(statut == null ? Statut.EN_COURS : statut, cases);
    }

    // ------------------------------------------------------------- internes

    /** Vrai si ce titre est celui de la section qui porte le statut. */
    private static boolean estSectionStatut(String ligne) {
        String titre = sansAccent(ligne.replace("#", "").replace("*", "").strip());
        return titre.equals(SECTION_STATUT) || titre.startsWith(SECTION_STATUT + " ");
    }

    /** Ce qui précède le premier {@code :} d'une ligne, ou {@code null} s'il n'y en a pas. */
    private static String cleDe(String ligne) {
        int deuxPoints = ligne.indexOf(':');
        return deuxPoints <= 0 ? null : ligne.substring(0, deuxPoints);
    }

    /**
     * Le statut d'une ligne de la forme {@code statut : clos}, où qu'elle soit dans le fichier.
     *
     * <p>Rend {@code null} si la ligne ne <b>parle</b> pas de statut : on ne devine pas une clôture
     * à partir d'une phrase qui contient « terminé ». Seule une ligne dont la clé <i>est</i>
     * « statut » est lue.</p>
     */
    private static Statut statutInline(String ligne) {
        String cle = cleDe(ligne);
        if (cle == null) {
            return null;
        }
        String nettoyee = sansAccent(cle.replace("*", "").replace("-", "").replace("_", "")
                .replace("`", "").strip());
        if (!nettoyee.equals(SECTION_STATUT)) {
            return null;
        }
        return statutDe(ligne.substring(ligne.indexOf(':') + 1));
    }

    /** Traduit une valeur en statut. Tout ce qui n'est pas « clos » laisse le sujet en cours. */
    private static Statut statutDe(String valeur) {
        String nettoyee = sansAccent(valeur);
        for (String mot : MOTS_CLOS) {
            if (nettoyee.contains(mot)) {
                return Statut.CLOS;
            }
        }
        return Statut.EN_COURS;
    }

    /** Vrai si la ligne est une case à cocher <b>non cochée</b>. */
    private static boolean estCaseOuverte(String ligne) {
        String corps = ligne;
        if (corps.startsWith("- ") || corps.startsWith("* ") || corps.startsWith("+ ")) {
            corps = corps.substring(2).strip();
        } else {
            return false;
        }
        return corps.startsWith("[ ]") || corps.startsWith("[]");
    }

    /** Minuscules, sans accent : on juge ce qui est dit, jamais la typographie. */
    private static String sansAccent(String texte) {
        String decompose = Normalizer.normalize(texte.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return decompose.replaceAll("\\p{M}", "");
    }
}
