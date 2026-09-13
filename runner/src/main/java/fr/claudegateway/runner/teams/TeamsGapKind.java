package fr.claudegateway.runner.teams;

/**
 * Les façons dont une lecture peut être <b>incomplète</b> (F-87 / SF-87-01).
 *
 * <p>Liste close, et volontairement explicite : « un trou se voit, un trou silencieux ne se voit
 * jamais ». Chaque valeur doit pouvoir être <b>écrite en français</b> à l'utilisateur — c'est à quoi
 * sert {@link #label()}.</p>
 */
public enum TeamsGapKind {

    /** Une réponse de Teams dont la forme n'est plus reconnue du tout. */
    UNRECOGNIZED_PAYLOAD("réponse de Teams non reconnue"),

    /** Un genre de message que l'adaptateur ne sait pas cartographier. */
    UNKNOWN_MESSAGE_KIND("genre de message inconnu"),

    /** Un champ obligatoire absent : l'objet n'est pas rendu. */
    MISSING_FIELD("champ obligatoire absent"),

    /** La pagination s'est arrêtée avant d'avoir tout lu. */
    PAGINATION_STOPPED("pagination interrompue"),

    /** Le défilement n'a plus rien fait venir alors que la fenêtre n'était pas couverte. */
    SCROLL_EXHAUSTED("défilement sans effet"),

    /** Le corps de la réponse n'a pas pu être récupéré auprès du navigateur. */
    BODY_UNAVAILABLE("corps de réponse indisponible"),

    /** Le plafond annoncé a été atteint : ce qui précède n'a pas été lu (D4). */
    CAP_REACHED("plafond de remontée atteint"),
    /**
     * Le fil demandé n'a pas pu être amené sous les yeux : il n'avait jamais été observé, et le
     * geste d'ouverture n'a pas abouti (F-88 / SF-88-01). Il rend <b>zéro message et ce manque</b> —
     * jamais une liste vide silencieuse, qui se lirait comme « il n'y a rien ».
     */
    CONVERSATION_NOT_REACHED("fil non atteint dans la fenêtre Teams"),
    /**
     * Teams n'a rien servi sur ce sujet depuis le rattachement (F-88 / SF-88-01). Distinct de
     * « il n'y a rien » : on ne sait pas, et on le dit.
     */
    NOTHING_OBSERVED("rien d'observé depuis le rattachement"),

    /**
     * La vidéo n'a pas changé de plan au seuil demandé (F-90 / SF-90-01). Distinct de « il n'y avait
     * rien à montrer » : peut-être un plan fixe, peut-être un seuil trop haut — et on le dit plutôt
     * que de rendre une liste vide, qui se lirait « cette réunion n'a rien montré ».
     */
    NO_SCENE_CHANGE("aucun changement de plan détecté"),

    /** Une image sortie par {@code ffmpeg} mais qu'on n'a pas pu relire (F-90 / SF-90-01). */
    FRAME_UNREADABLE("image extraite illisible"),

    /**
     * Une image sortie sans horodatage exploitable (F-90 / SF-90-01) : elle est <b>écartée</b>, car
     * une image mal datée dans un compte rendu est pire qu'une image absente.
     */
    FRAME_UNDATED("image extraite non datée"),

    /**
     * Une parole prononcée <b>avant la première image retenue</b> (F-90 / SF-90-02) : aucune image
     * n'était en vigueur. La rattacher à la première serait la même faute que prendre l'image
     * suivante, dans l'autre sens — elle est donc comptée, et rapprochée de rien.
     */
    SPOKEN_BEFORE_FIRST_FRAME("parole sans image en vigueur"),

    /**
     * Une image retenue devant laquelle <b>personne n'a parlé</b> (F-90 / SF-90-02). Elle ne devient
     * pas un moment — un moment sans citation n'est pas un moment — et elle est comptée.
     */
    FRAME_WITHOUT_SPEECH("image sans parole pendant son affichage"),

    /**
     * Une image retenue que la gateway n'a pas acceptée (F-90 / SF-90-03). Son moment est rendu
     * <b>sans capture</b>, et le compte est dit : perdre une image sur soixante et le dire vaut
     * mieux que perdre le compte rendu — mais le taire serait le rendre faux.
     */
    UPLOAD_REFUSED("image retenue non remontée"),

    /**
     * Une réponse de SharePoint / OneDrive qui ne correspond pas à la forme documentée sur laquelle
     * l'adaptateur a été écrit (F-108 / SF-108-03). Rien n'est rendu : une liste de fichiers à
     * moitié lue se lirait « voilà tout le dossier ».
     */
    SHAPE_MISMATCH("réponse Microsoft 365 non conforme au modèle documenté"),

    /** L'emplacement de fichiers demandé n'a pas pu être reconnu ou retrouvé (F-108 / SF-108-03). */
    LOCATION_UNKNOWN("emplacement de fichiers non reconnu"),

    /**
     * L'onglet a atterri sur une page d'identification (F-108 / SF-108-03) : le runner ne se
     * connecte jamais, il le dit.
     */
    SIGNED_OUT("session Microsoft à rouvrir"),

    /** Microsoft 365 a refusé l'accès à cet emplacement (401 / 403) (F-108 / SF-108-03). */
    ACCESS_DENIED("accès refusé par Microsoft 365"),

    /** L'élément demandé n'existe pas, ou plus, à cet emplacement (F-108 / SF-108-03). */
    NOT_FOUND("élément introuvable"),

    /**
     * Le téléchargement n'a pas abouti : bloqué par l'organisateur ou la politique du tenant, refusé,
     * ou remplacé par une page d'erreur (F-108 / SF-108-03 et SF-108-05).
     */
    DOWNLOAD_BLOCKED("téléchargement bloqué ou refusé"),

    /** Un élément de ce nom existe déjà : rien n'est écrasé (F-108 / SF-108-04). */
    ALREADY_EXISTS("un élément de ce nom existe déjà"),

    /** Microsoft 365 a refusé l'écriture — verrou, extraction, règle du site (F-108 / SF-108-04). */
    WRITE_FAILED("écriture refusée par Microsoft 365"),

    /** Un nom que SharePoint refuserait : l'écriture n'est pas tentée (F-108 / SF-108-04). */
    INVALID_NAME("nom refusé");

    private final String label;

    TeamsGapKind(String label) {
        this.label = label;
    }

    /** Libellé français, écrit tel quel dans ce que rend un outil. */
    public String label() {
        return label;
    }
}
