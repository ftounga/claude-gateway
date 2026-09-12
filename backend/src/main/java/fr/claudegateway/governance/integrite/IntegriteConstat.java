package fr.claudegateway.governance.integrite;

import java.util.List;

import fr.claudegateway.governance.GovernanceHostRule;

/**
 * Un constat d'intégrité, et <b>le geste qui le corrige</b> (F-95 / SF-95-01).
 *
 * <p><b>La règle qui prime, et son motif est écrit dans le prompt d'origine</b> : un message
 * d'erreur porte son action corrective, pas seulement le constat, parce qu'« il est lu par un
 * <b>modèle</b> qui doit corriger, pas par un humain qui doit comprendre ». « Le dossier ne respecte
 * pas la convention » ne se corrige pas ; « déplace-le sous {@code repos/} » se corrige.</p>
 *
 * <p><b>Comment on la rend mécanique.</b> Il n'existe <b>aucun</b> constructeur public prenant un
 * message libre : un constat se fabrique par l'une des méthodes ci-dessous, une par règle, et
 * chacune écrit le geste. Un appelant ne peut donc pas produire un constat muet, même par
 * distraction — la garantie est dans la forme du type, pas dans la discipline de celui qui
 * l'emploie.</p>
 *
 * <p><b>Les deux règles de clonage ne réécrivent rien</b> : leur énoncé et leur geste viennent de
 * {@link GovernanceHostRule}, où F-93 les a posés en annonçant que « c'est là que F-95 se branchera
 * pour les vérifier mécaniquement, sans réécrire ni les énoncés ni les gestes ». Un contrôle et un
 * document qui décrivent la même règle avec deux textes divergent au premier correctif.</p>
 *
 * @param regle   la règle constatée
 * @param cible   ce sur quoi porte le constat — un fichier de carte, un projet, un dépôt
 * @param message le constat <b>et</b> le geste, dans cet ordre
 */
public record IntegriteConstat(IntegriteRegle regle, String cible, String message) {

    /**
     * Longueur d'un constat. Au-delà, ce n'est plus une action corrective mais un paragraphe — et
     * le rapport en cite plusieurs dans les 2 000 caractères d'un verdict de F-50.
     */
    public static final int MAX_MESSAGE_CHARS = 400;

    /** Seuil de faits au-delà duquel l'index de la carte n'est plus un index (voir §7 du prompt). */
    public static final int SEUIL_INDEX_FAITS = 60;

    /** Borne et nettoie, sans jamais rendre un message vide : un constat muet resterait un constat. */
    public IntegriteConstat {
        cible = cible == null ? "" : cible.strip();
        message = borne(message);
    }

    /** Le niveau du constat — celui de sa règle, jamais choisi au cas par cas. */
    public IntegriteNiveau niveau() {
        return regle.niveau();
    }

    // ------------------------------------------------------------- la carte

    /** Un fichier de carte manque à la racine : il n'y a plus de destination pour la promotion. */
    public static IntegriteConstat carteAbsente(String fichier) {
        return new IntegriteConstat(IntegriteRegle.CARTE_ABSENTE, fichier,
                "le fichier de carte « " + fichier + " » manque à la racine de ce poste : reprends "
                        + "« Appliquer » sur ce poste depuis l'écran Gouvernance pour le reposer, ou "
                        + "crée-le à la racine avec un titre « # … » et ses sections « ## … ». Sans "
                        + "lui, il n'y a nulle part où promouvoir ce que ce projet fait apparaître.");
    }

    /** Un fichier de carte présent mais sans aucune section : on ne sait plus où ranger un fait. */
    public static IntegriteConstat carteSansStructure(String fichier) {
        return new IntegriteConstat(IntegriteRegle.CARTE_SANS_STRUCTURE, fichier,
                "le fichier de carte « " + fichier + " » ne porte aucune section : rends-lui ses "
                        + "titres « ## … » (un par thème) avant d'y ajouter un fait, sinon les faits "
                        + "s'y entassent sans qu'on sache plus où chercher.");
    }

    /**
     * L'index de la carte porte trop de faits.
     *
     * <p><b>Ce qu'on retient du prompt, et ce qu'on en écarte.</b> Il exige des index de 60 lignes
     * au plus. Compté en <b>lignes</b>, le seuil se déclencherait sur l'index que le produit
     * <i>livre lui-même</i> — 91 lignes de consignes —, et le produit reprocherait à l'utilisateur
     * un fichier qu'il a écrit à sa place. On retient donc l'<b>esprit</b> : un index qui accumule
     * le détail cesse d'être un index. Le compte porte sur les <b>faits</b> (F-92 /
     * {@code GovernanceMapDigest}, qui écarte consignes, en-têtes et cases vides) : un index tout
     * juste déposé en compte <b>zéro</b>, et le seuil ne se déclenche que le jour où quelqu'un a
     * versé soixante faits dans l'index au lieu des fichiers de domaine.</p>
     */
    public static IntegriteConstat indexSurcharge(String fichier, int faits) {
        return new IntegriteConstat(IntegriteRegle.CARTE_INDEX_SURCHARGE, fichier,
                "l'index de la carte « " + fichier + " » porte " + faits + " faits (seuil : "
                        + SEUIL_INDEX_FAITS + ") : déplace le détail dans le fichier de domaine "
                        + "concerné — accès, réseau, plateformes, données, exploitation — et ne "
                        + "garde ici que ce qui dit où chercher quoi.");
    }

    /** La carte cite un chemin qui n'existe plus : le savoir pointe dans le vide. */
    public static IntegriteConstat lienMort(String fichier, String reference) {
        return new IntegriteConstat(IntegriteRegle.CARTE_LIEN_MORT, fichier,
                "« " + fichier + " » cite « " + reference + " », qui n'existe plus à la racine de ce "
                        + "poste : vérifie où c'est parti, corrige la référence dans « " + fichier
                        + " » — ou retire la ligne si le fait est devenu caduc, en disant depuis "
                        + "quand.");
    }

    // ----------------------------------------------------------- les projets

    /** Un projet sans {@code STATE.md} : sa dette de promotion n'est écrite nulle part. */
    public static IntegriteConstat stateAbsent(String projet) {
        return new IntegriteConstat(IntegriteRegle.PROJET_SANS_STATE, projet,
                "le projet « " + projet + " » n'a pas de « STATE.md » : crée-le à sa racine avec les "
                        + "sections « ## Statut », « ## Où j'en suis » et « ## Promotions », ou "
                        + "reprends « Appliquer » sur ce poste depuis l'écran Gouvernance pour "
                        + "reposer le gabarit. Sans lui, la dette de promotion ne se compte pas.");
    }

    /** Sujet déclaré clos alors qu'une case reste ouverte : le savoir part avec le dossier. */
    public static IntegriteConstat detteALaCloture(String projet, int cases, String carte) {
        return new IntegriteConstat(IntegriteRegle.DETTE_A_LA_CLOTURE, projet,
                "le projet « " + projet + " » est déclaré clos avec " + cases
                        + (cases > 1 ? " cases « - [ ] » non cochées" : " case « - [ ] » non cochée")
                        + " : remonte-les d'abord dans la carte du poste (" + carte + "), coche "
                        + "chacune en disant où — « - [x] <élément> -> promu dans <fichier> » —, "
                        + "puis clos. Ce qui n'est pas promu meurt avec le dossier.");
    }

    /** Sujet en cours portant une dette : état normal, on le dit sans rien bloquer. */
    public static IntegriteConstat detteEnCours(String projet, int cases, String carte) {
        return new IntegriteConstat(IntegriteRegle.DETTE_EN_COURS, projet,
                "le projet « " + projet + " » garde " + cases
                        + (cases > 1 ? " cases « - [ ] » non cochées" : " case « - [ ] » non cochée")
                        + " : promeus-les dans la carte du poste (" + carte + ") au fil de l'eau, "
                        + "avant la clôture — après, elles bloqueront.");
    }

    /** Un projet qui porte un {@code .git/} : le geste est celui de F-93, mot pour mot. */
    public static IntegriteConstat projetDepotGit(String projet) {
        return new IntegriteConstat(IntegriteRegle.PROJET_DEPOT_GIT, projet,
                "le projet « " + projet + " » porte un « .git/ ». "
                        + GovernanceHostRule.PROJET_SANS_GIT.correction());
    }

    /** Des notes non versionnées à la racine d'un dépôt client : le geste est celui de F-93. */
    public static IntegriteConstat noteHorsDepot(String depot, List<String> fichiers) {
        String cites = fichiers == null || fichiers.isEmpty() ? "un « .md »"
                : "« " + String.join(" », « ", fichiers) + " »";
        return new IntegriteConstat(IntegriteRegle.NOTE_HORS_DEPOT, depot,
                "le dépôt « " + depot + " » porte à sa racine " + cites + ", non versionné. "
                        + GovernanceHostRule.NOTE_HORS_DEPOT.correction());
    }

    // ------------------------------------------------------------- internes

    private static String borne(String message) {
        String valeur = message == null ? "" : message.strip();
        if (valeur.isEmpty()) {
            // Ne peut pas arriver par les fabriques ; le dire plutôt que rendre un constat muet.
            return "constat sans message : relis l'élément cité et corrige-le.";
        }
        return valeur.length() <= MAX_MESSAGE_CHARS ? valeur
                : valeur.substring(0, MAX_MESSAGE_CHARS - 1) + "…";
    }
}
