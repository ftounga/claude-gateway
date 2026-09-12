package fr.claudegateway.governance.juge;

import java.util.List;

/**
 * Ce que le juge indépendant rend (F-94 / SF-94-02).
 *
 * <p><b>Best-effort, jamais une autorité.</b> Un avis n'est pas une décision : c'est une <b>liste à
 * vérifier</b>. Il se présente comme telle à qui le lit, et F-50 rend la main après un nombre fixe
 * de refus — un juge ne prend jamais le message d'un utilisateur en otage.</p>
 *
 * @param issue    ce qui s'est passé
 * @param elements ce qui est cité dans les notes et absent de la carte ; vide sauf pour
 *                 {@link Issue#ELEMENTS}
 */
public record JugeAvis(Issue issue, List<JugeVerdict.Element> elements) {

    /** Les cinq issues possibles, et ce qu'elles autorisent l'appelant à faire. */
    public enum Issue {

        /** Le juge a répondu, et n'a rien trouvé. <b>La seule qui autorise à se taire.</b> */
        RIEN,

        /** Le juge a listé. L'appelant signale — comme une liste à vérifier, pas comme un verdict. */
        ELEMENTS,

        /**
         * <b>Le repli qui alerte.</b> Le juge a répondu, mais son bloc de verdict manque ou ne veut
         * rien dire : on n'a donc <b>pas</b> analysé, et le prompt d'origine est formel — on signale
         * plutôt que de laisser passer. Un filet qui se tait quand il ne comprend pas ne protège de
         * rien.
         */
        VERDICT_ILLISIBLE,

        /** Rien à comparer : pas de carte, pas de note, ou poste sans machine. On se tait. */
        PAS_DE_MATIERE,

        /**
         * Le juge n'a pas pu être consulté : coupe-circuit fermé, fournisseur absent, appel en
         * échec, délai dépassé. <b>La session se termine normalement</b> — c'est la règle : le juge
         * ne casse jamais rien.
         */
        INDISPONIBLE
    }

    public JugeAvis {
        elements = elements == null ? List.of() : List.copyOf(elements);
    }

    public static JugeAvis rien() {
        return new JugeAvis(Issue.RIEN, List.of());
    }

    public static JugeAvis elements(List<JugeVerdict.Element> elements) {
        return new JugeAvis(Issue.ELEMENTS, elements);
    }

    public static JugeAvis verdictIllisible() {
        return new JugeAvis(Issue.VERDICT_ILLISIBLE, List.of());
    }

    public static JugeAvis pasDeMatiere() {
        return new JugeAvis(Issue.PAS_DE_MATIERE, List.of());
    }

    public static JugeAvis indisponible() {
        return new JugeAvis(Issue.INDISPONIBLE, List.of());
    }

    /** Vrai si cet avis demande à être dit — une liste, ou un juge qu'on n'a pas compris. */
    public boolean aSignaler() {
        return issue == Issue.ELEMENTS || issue == Issue.VERDICT_ILLISIBLE;
    }

    /** Les éléments, tels qu'un message les cite. */
    public String cited() {
        return new JugeVerdict(true, elements).cited();
    }
}
