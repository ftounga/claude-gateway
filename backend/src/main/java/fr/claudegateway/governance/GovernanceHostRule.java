package fr.claudegateway.governance;

import java.util.List;

/**
 * Les <b>invariants de la racine d'un poste</b> — les règles de clonage transposées (F-93 /
 * SF-93-01).
 *
 * <p>Le prompt d'origine impose trois choses sur {@code ~/dev}. Transposées à la racine d'un poste,
 * elles protègent trois erreurs qui coûtent cher et qu'aucune convention orale n'évite :</p>
 *
 * <ol>
 *   <li>un dépôt cloné <b>parmi les sujets</b> sera pris pour un projet et gouverné comme tel ;</li>
 *   <li>un sujet qui porte un {@code .git/} <b>est</b> un dépôt qui s'est trompé d'endroit ;</li>
 *   <li>une note personnelle non versionnée à la racine d'un dépôt client finit <b>livrée chez le
 *       client</b>. C'est celle qui protège le plus.</li>
 * </ol>
 *
 * <p><b>Pourquoi une énumération, et pas seulement un paragraphe du document de règles.</b> Le
 * document parle au modèle ; cette énumération parle au <b>produit</b>. Elle porte l'identifiant
 * stable de chaque règle, son énoncé et son <b>action corrective</b> — et c'est là que F-95 se
 * branchera pour les vérifier mécaniquement sur la machine, sans réécrire ni les énoncés ni les
 * gestes. Un contrôle et un document qui décrivent la même règle avec deux textes différents
 * divergent au premier correctif ; ici, le texte n'existe qu'une fois.</p>
 *
 * <p><b>Le document doit les citer.</b> {@link GovernancePackageSeeder} refuse de semer un paquet
 * dont le texte de règles ne mentionne pas les trois identifiants : un paquet qui annoncerait une
 * règle absente de son propre texte serait pire qu'un paquet incomplet — il aurait l'air complet.</p>
 *
 * <p><b>Ce que cette énumération ne fait pas</b> : vérifier quoi que ce soit. Elle ne lit aucune
 * machine, n'ouvre aucun dossier, ne rend aucun verdict. C'est F-95 qui lira la racine par le
 * runner ; ici on ne fait que <b>dire la règle une fois pour toutes</b>.</p>
 */
public enum GovernanceHostRule {

    /**
     * Un dépôt client se clone dans {@code repos/}, jamais parmi les sujets.
     *
     * <p>C'est la transposition directe de {@code ~/dev/repos}. Un dépôt qui atterrit à côté des
     * projets sera compté comme un projet : il recevra les gabarits, entrera dans l'annuaire de la
     * carte, et sa dette de promotion sera réclamée à un dossier qui n'est pas un sujet.</p>
     */
    DEPOT_DANS_REPOS("clonage/depot-dans-repos",
            "Un dépôt client se clone dans « repos/ » sous la racine du poste, jamais à côté des "
                    + "dossiers de projets.",
            "Déplace-le sous « repos/ » : « mkdir -p repos && mv <dossier> repos/<dossier> ». "
                    + "Laissé parmi les sujets, il serait pris pour un projet et gouverné comme tel."),

    /**
     * Un projet n'est jamais un dépôt git.
     *
     * <p>Un sujet est un dossier de travail : des notes, un {@code STATE.md}, un
     * {@code PLAN-ACTION.md}. S'il porte un {@code .git/}, ce n'est pas un sujet — c'est un dépôt
     * rangé au mauvais endroit, et tout ce qu'on y écrira partira un jour dans une branche.</p>
     */
    PROJET_SANS_GIT("clonage/projet-sans-git",
            "Un projet n'est jamais un dépôt git : un sujet est un dossier de travail.",
            "Ce dossier porte un « .git/ » : c'est un dépôt, pas un sujet. Déplace-le sous "
                    + "« repos/ » (« mv <dossier> repos/<dossier> ») et ouvre un dossier de travail "
                    + "distinct pour le sujet."),

    /**
     * Aucune note personnelle non versionnée à la racine d'un dépôt client.
     *
     * <p><b>La règle qui protège le plus.</b> Un {@code .md} non suivi dans
     * {@code repos/<dépôt>/} est un fichier qu'on ne possède pas, dans un dossier qu'on ne possède
     * pas : il finit committé par distraction, ou livré dans une archive. Sa place est la carte du
     * poste, qui, elle, appartient à la machine.</p>
     */
    NOTE_HORS_DEPOT("clonage/note-hors-depot",
            "Aucune note personnelle non versionnée à la racine d'un dépôt client.",
            "Déplace ce « .md » dans la carte du poste — un fichier « .md » de la racine — puis "
                    + "supprime-le du dépôt. Il ne doit pas partir dans un dépôt qu'on ne possède "
                    + "pas.");

    private final String id;
    private final String statement;
    private final String correction;

    GovernanceHostRule(String id, String statement, String correction) {
        this.id = id;
        this.statement = statement;
        this.correction = correction;
    }

    /**
     * L'identifiant stable de la règle, tel que le document de règles le cite et tel que F-95 le
     * rendra dans ses constats. <b>Immuable</b> : il est publié.
     */
    public String id() {
        return id;
    }

    /** Ce que la règle exige, en une phrase. */
    public String statement() {
        return statement;
    }

    /**
     * <b>Le geste qui corrige</b>, pas le constat.
     *
     * <p>C'est une exigence littérale de la feature, et son motif y est écrit : le message est lu
     * par un modèle qui doit corriger, pas par un humain qui doit comprendre. « Le dossier ne
     * respecte pas la convention » ne se corrige pas ; « déplace-le sous repos/ » se corrige.</p>
     */
    public String correction() {
        return correction;
    }

    /** Les identifiants des trois règles, dans l'ordre où le document les présente. */
    public static List<String> ids() {
        return List.of(DEPOT_DANS_REPOS.id, PROJET_SANS_GIT.id, NOTE_HORS_DEPOT.id);
    }
}
