package fr.claudegateway.governance.juge;

import java.util.List;

/**
 * Ce qu'on met sous les yeux du juge indépendant (F-94 / SF-94-01) : <b>la carte</b> d'un côté,
 * <b>les notes des projets</b> de l'autre.
 *
 * <p>Le sens de lecture est unique, et c'est ce qui rend la question posable en une phrase :
 * <i>qu'est-ce qui est cité là et absent d'ici ?</i> Tout ce qui n'entre pas dans ces deux colonnes
 * n'entre pas dans l'appel.</p>
 *
 * <p><b>Les notes sont les fichiers de la racine d'un projet</b>, jamais des sous-dossiers. Des
 * sources brutes — un dépôt cloné, un export, un dossier {@code sources/} — sont du bruit : elles
 * feraient citer au juge tout et n'importe quoi, et un filet qui crie à chaque tour cesse d'être
 * lu.</p>
 *
 * @param carte     les fichiers de carte lus à la racine du poste
 * @param notes     les notes lues à la racine des projets du poste
 * @param tronquee  vrai si la matière a été coupée par une borne — une coupe se <b>dit</b>, sinon le
 *                  juge conclurait « absent » sur ce qu'il n'a pas vu
 */
public record JugeMatiere(List<Piece> carte, List<Piece> notes, boolean tronquee) {

    /** Notes retenues : au-delà, on n'envoie plus une matière, on envoie un disque. */
    public static final int MAX_NOTES = 20;

    /** Caractères retenus d'un fichier. */
    public static final int MAX_CHARS_PAR_FICHIER = 20_000;

    /** Caractères retenus pour toute la matière — carte et notes confondues. */
    public static final int MAX_CHARS_TOTAL = 120_000;

    /** Une matière vide : rien à comparer, donc aucune question à poser. */
    public static final JugeMatiere VIDE = new JugeMatiere(List.of(), List.of(), false);

    /**
     * Un fichier remis au juge.
     *
     * @param chemin  le chemin tel qu'il sera cité dans le verdict — c'est ce qui rend une alerte
     *                vérifiable
     * @param contenu son contenu, éventuellement coupé
     */
    public record Piece(String chemin, String contenu) {

        public Piece {
            chemin = chemin == null ? "" : chemin.strip();
            contenu = contenu == null ? "" : contenu;
        }
    }

    /** Rend les listes immuables : elles partent dans un appel, personne n'y touche après coup. */
    public JugeMatiere {
        carte = carte == null ? List.of() : List.copyOf(carte);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /**
     * Vrai si la question vaut la peine d'être posée.
     *
     * <p>Il faut <b>les deux</b> côtés : sans note, il n'y a rien à comparer ; sans carte lue, on
     * comparerait à une carte qu'on n'a pas, et <i>tout</i> serait signalé comme absent.</p>
     */
    public boolean utilisable() {
        return !notes.isEmpty() && !carte.isEmpty();
    }
}
