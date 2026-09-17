package fr.claudegateway.governance.integrite;

import fr.claudegateway.governance.GovernanceHostRule;

/**
 * Ce que l'intégrité d'un poste vérifie (F-95 / SF-95-01) — l'équivalent d'{@code infra-doctor},
 * <b>réduit à ce qui a du sens dans le produit</b>.
 *
 * <p>Chaque règle porte un <b>identifiant stable</b> — il apparaît dans les constats, il est donc
 * publié — et son <b>niveau</b> : bloquant ou informatif. Le <i>geste</i>, lui, dépend de la cible
 * et vit dans {@link IntegriteConstat} ; le <i>constat</i> se construit toujours par une fabrique,
 * si bien qu'aucun chemin de code ne peut produire un message sans action corrective.</p>
 *
 * <p><b>Ce qu'on ne transpose pas, et pourquoi.</b> Le prompt d'origine vérifie aussi l'ossature
 * <b>personnelle</b> de son auteur : {@code ~/dev} limité à {@code repos/} et {@code infra/},
 * {@code ~/poste/}, {@code ~/methodo/}, le site de méthodologie jamais sous {@code ~/dev}. Ces
 * contrôles décrivent son poste à lui, pas celui d'un client — et le produit n'impose <b>aucune
 * convention de chemin</b> : la racine est celle que le runner déclare, « dev » chez un poste,
 * « infra » chez un autre.</p>
 *
 * <p><b>Et {@code clonage/depot-dans-repos} n'a pas d'entrée ici.</b> Du point de vue du produit, un
 * dépôt cloné parmi les sujets <b>est</b> un projet qui porte un {@code .git/} : une seule
 * détection, un seul constat — {@link #PROJET_DEPOT_GIT} —, dont le geste dit déjà « déplace-le sous
 * {@code repos/} ». Deux constats pour une même situation rendraient deux corrections dont une
 * seule serait traitée.</p>
 */
public enum IntegriteRegle {

    /** Un fichier de carte attendu manque à la racine : il n'y a plus où promouvoir. */
    CARTE_ABSENTE("carte/fichier-absent", IntegriteNiveau.ERREUR),

    /** Un fichier de carte présent ne porte aucune section : il ne se remplit plus, il s'entasse. */
    CARTE_SANS_STRUCTURE("carte/sans-structure", IntegriteNiveau.AVERTISSEMENT),

    /** L'index de la carte porte trop de faits : ce n'est plus un index, c'est un fourre-tout. */
    CARTE_INDEX_SURCHARGE("carte/index-surcharge", IntegriteNiveau.AVERTISSEMENT),

    /**
     * Un fichier de carte est présent à la racine mais <b>non déclaré</b> (F-125 / SF-125-03).
     *
     * <p>C'est un <b>avertissement</b>, et c'est tout l'objet de la feature : un fichier créé mais
     * pas encore inscrit dans l'index ne met plus l'agent « hors gouvernance » ni en boucle. Le
     * corriger — le déclarer dans {@code README.md}, ou le laisser — demande un <b>jugement</b> ; le
     * bloquer dessus reviendrait à décider à la place de l'utilisateur, dans la carte d'un client.</p>
     */
    CARTE_NON_DECLAREE("carte/non-declaree", IntegriteNiveau.AVERTISSEMENT),

    /**
     * La carte cite un chemin qui n'existe plus.
     *
     * <p><b>C'est la façon dont une carte pourrit sans qu'on s'en aperçoive</b>, et c'est pourtant
     * un <b>avertissement</b> : corriger demande de savoir si le chemin a bougé ou si le fait est
     * devenu caduc. Un modèle qui bloquerait là-dessus trancherait à la place de l'utilisateur, dans
     * la carte d'un client.</p>
     */
    CARTE_LIEN_MORT("carte/lien-mort", IntegriteNiveau.AVERTISSEMENT),

    /** Un projet du poste n'a pas de {@code STATE.md} : sa dette n'est écrite nulle part. */
    PROJET_SANS_STATE("projet/state-absent", IntegriteNiveau.ERREUR),

    /** Sujet déclaré clos alors qu'une case reste ouverte : le savoir meurt avec le dossier. */
    DETTE_A_LA_CLOTURE("dette/a-la-cloture", IntegriteNiveau.ERREUR),

    /** Sujet en cours portant une dette : c'est un état normal, on le <b>dit</b> sans bloquer. */
    DETTE_EN_COURS("dette/en-cours", IntegriteNiveau.AVERTISSEMENT),

    /** Un projet porte un {@code .git/} : c'est un dépôt rangé au mauvais endroit (F-93). */
    PROJET_DEPOT_GIT(GovernanceHostRule.PROJET_SANS_GIT.id(), IntegriteNiveau.ERREUR),

    /** Un {@code .md} non versionné à la racine d'un dépôt client : la règle qui protège le plus. */
    NOTE_HORS_DEPOT(GovernanceHostRule.NOTE_HORS_DEPOT.id(), IntegriteNiveau.ERREUR);

    private final String id;
    private final IntegriteNiveau niveau;

    IntegriteRegle(String id, IntegriteNiveau niveau) {
        this.id = id;
        this.niveau = niveau;
    }

    /** L'identifiant stable de la règle, cité dans chaque constat. <b>Immuable</b> : il est publié. */
    public String id() {
        return id;
    }

    /** Bloquant ou informatif — et les deux ne se mélangent jamais. */
    public IntegriteNiveau niveau() {
        return niveau;
    }
}
