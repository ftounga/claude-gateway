package fr.claudegateway.governance.integrite;

import java.util.List;

/**
 * Ce que l'inspection d'un poste a trouvé (F-95 / SF-95-01) — et <b>comment ça se dit à un
 * modèle</b>.
 *
 * <p>Le rapport garde les constats <b>dans l'ordre de découverte</b> et sait les rendre
 * <b>séparés par niveau</b>. C'est la seconde exigence littérale de la feature : les erreurs
 * bloquent, les avertissements informent, et les deux ne se mélangent jamais — ni dans le type, ni
 * dans le texte remis au modèle, où ils vivent sous deux intitulés distincts.</p>
 *
 * <p><b>« Rien à signaler » et « rien n'a été inspecté » ne sont pas la même chose</b>, et
 * {@link #inspecte()} les distingue. Une machine éteinte, un poste sans gouvernance, un projet
 * effacé rendent un rapport <b>silencieux</b> — jamais un rapport « sain ». Confondre les deux ferait
 * afficher « tout va bien » à un utilisateur dont on n'a rien lu.</p>
 *
 * <p><b>Borné, parce qu'il traverse un verdict de F-50.</b> Trois erreurs et deux avertissements
 * sont cités au plus ; le reste est <b>annoncé</b> plutôt que tu. Une liste de quinze corrections
 * n'en ferait traiter qu'une, et perdrait les quatorze autres sans le dire.</p>
 */
public record IntegriteRapport(List<IntegriteConstat> constats, boolean inspecte) {

    /** Erreurs citées dans la correction. Au-delà, le modèle n'en traiterait de toute façon pas plus. */
    public static final int MAX_ERREURS_CITEES = 3;

    /** Avertissements cités. Ils informent : deux suffisent à dire dans quel sens ça penche. */
    public static final int MAX_AVERTISSEMENTS_CITES = 2;

    /** Borne du texte rendu, alignée sur {@code AtelierCheckpointVerdict.MAX_CORRECTION_CHARS}. */
    public static final int MAX_CORRECTION_CHARS = 2_000;

    /** Ce qui introduit le bloc informatif, pour qu'aucun lecteur ne le prenne pour un refus. */
    public static final String ENTETE_AVERTISSEMENTS =
            "AVERTISSEMENTS (informatifs — ils ne bloquent pas ce tour) :";

    /** Ce qui introduit le bloc bloquant. */
    public static final String ENTETE_ERREURS =
            "Intégrité du poste — ERREURS (à corriger avant de conclure) :";

    /** Rend la liste immuable : un rapport se lit, il ne se complète pas après coup. */
    public IntegriteRapport {
        constats = constats == null ? List.of() : List.copyOf(constats);
    }

    /** Un poste inspecté sur lequel on n'a rien à dire. */
    public static IntegriteRapport sain() {
        return new IntegriteRapport(List.of(), true);
    }

    /** <b>Rien n'a été inspecté</b> — machine muette, poste non gouverné, projet effacé. */
    public static IntegriteRapport silencieux() {
        return new IntegriteRapport(List.of(), false);
    }

    /** Un rapport porteur de constats. */
    public static IntegriteRapport de(List<IntegriteConstat> constats) {
        return new IntegriteRapport(constats, true);
    }

    /** Les constats de ce niveau, dans l'ordre de découverte ; jamais {@code null}. */
    public List<IntegriteConstat> par(IntegriteNiveau niveau) {
        return constats.stream().filter(constat -> constat.niveau() == niveau).toList();
    }

    /** Les erreurs — celles qui bloquent. */
    public List<IntegriteConstat> erreurs() {
        return par(IntegriteNiveau.ERREUR);
    }

    /** Les avertissements — ceux qui informent, et rien de plus. */
    public List<IntegriteConstat> avertissements() {
        return par(IntegriteNiveau.AVERTISSEMENT);
    }

    /** Vrai s'il existe au moins une erreur : c'est la seule condition d'un blocage. */
    public boolean bloque() {
        return !erreurs().isEmpty();
    }

    /** Vrai si l'inspection a eu lieu et n'a rien trouvé. */
    public boolean rienASignaler() {
        return inspecte && constats.isEmpty();
    }

    /**
     * Le texte remis au modèle : les erreurs d'abord, puis — sous un intitulé distinct — les
     * avertissements.
     *
     * <p>Rend une chaîne <b>vide</b> quand il n'y a rien à dire : l'appelant décide ce qu'il en
     * fait, et un texte de politesse n'aiderait personne à corriger.</p>
     */
    public String correction() {
        List<IntegriteConstat> erreurs = erreurs();
        List<IntegriteConstat> avertissements = avertissements();
        if (erreurs.isEmpty() && avertissements.isEmpty()) {
            return "";
        }
        StringBuilder texte = new StringBuilder();
        if (!erreurs.isEmpty()) {
            texte.append(ENTETE_ERREURS);
            ajoute(texte, erreurs, MAX_ERREURS_CITEES);
        }
        if (!avertissements.isEmpty()) {
            if (texte.length() > 0) {
                texte.append('\n');
            }
            texte.append(ENTETE_AVERTISSEMENTS);
            ajoute(texte, avertissements, MAX_AVERTISSEMENTS_CITES);
        }
        String rendu = texte.toString();
        return rendu.length() <= MAX_CORRECTION_CHARS ? rendu
                : rendu.substring(0, MAX_CORRECTION_CHARS - 1) + "…";
    }

    // ------------------------------------------------------------- internes

    /** Cite au plus {@code max} constats, et <b>annonce</b> ceux qu'il n'a pas cités. */
    private static void ajoute(StringBuilder texte, List<IntegriteConstat> constats, int max) {
        int cites = Math.min(max, constats.size());
        for (int i = 0; i < cites; i++) {
            texte.append("\n").append(i + 1).append(". [").append(constats.get(i).regle().id())
                    .append("] ").append(constats.get(i).message());
        }
        int reste = constats.size() - cites;
        if (reste > 0) {
            // Un constat non cité doit être SU : tu, il disparaîtrait sans que personne l'apprenne.
            texte.append("\n… et ").append(reste)
                    .append(reste > 1 ? " autres constats, rejoués" : " autre constat, rejoué")
                    .append(" au prochain passage.");
        }
    }
}
