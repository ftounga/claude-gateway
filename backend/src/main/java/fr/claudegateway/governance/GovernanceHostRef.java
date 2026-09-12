package fr.claudegateway.governance;

import java.util.UUID;

import fr.claudegateway.runner.host.RunnerHostNotFoundException;

/**
 * Le <b>poste</b> sur lequel une gouvernance s'active (F-75 / SF-75-01).
 *
 * <p>F-51 activait un paquet <b>par projet</b>. C'était une erreur de grain : le modèle dont la
 * feature est issue pose la gouvernance <b>une fois au niveau de la machine</b>, et ce sont les
 * <i>artefacts</i> qui sont par sujet — le {@code STATE.md} de chaque dossier, la dette de promotion.
 * Depuis F-75, on active une fois sur un poste et <b>tout dossier ajouté demain sous sa racine en
 * hérite</b> : c'est tout l'intérêt d'un bootstrap idempotent, et c'est précisément ce que
 * l'activation par projet interdisait.</p>
 *
 * <p><b>Aucune dérogation par dossier</b> (tranché par le PO le 2026-09-12). Une gouvernance qui se
 * contourne au cas par cas cesse d'en être une : elle devient un réglage, et la première exception
 * rend la seconde évidente. Il n'existe donc qu'un seul grain, et ce type est le seul moyen de le
 * désigner.</p>
 *
 * <p><b>Le poste « Hébergé ».</b> Les projets sans machine — archive importée, dépôt monté — sont
 * rangés par F-71 sous un poste <b>virtuel</b> dont l'identifiant est rendu <b>nul</b> au client, et
 * délibérément : « un identifiant constant ressemblerait à une entité et finirait envoyé à un
 * endpoint qui répondrait 404 ». On respecte cela : le poste « Hébergé » s'adresse par le mot réservé
 * {@value #HOSTED_REF}, jamais par un identifiant. L'identifiant nul de {@link #HOSTED_ID} ne sort
 * <b>jamais</b> d'ici : il ne sert qu'à donner à ces activations une clé d'unicité en base — une
 * colonne nulle ne dédoublonnerait rien sous PostgreSQL.</p>
 *
 * @param hostId identifiant porté en base ; {@link #HOSTED_ID} pour le poste « Hébergé »
 * @param hosted vrai s'il s'agit du poste virtuel « Hébergé »
 */
public record GovernanceHostRef(UUID hostId, boolean hosted) {

    /** Le mot par lequel le poste « Hébergé » s'adresse dans une URL. */
    public static final String HOSTED_REF = "hosted";

    /**
     * Clé technique du poste « Hébergé » en base. <b>Interne</b> : aucune réponse d'API ne la rend,
     * aucune requête ne l'accepte — {@link #parse(String)} refuse explicitement de la reconnaître.
     */
    static final UUID HOSTED_ID = new UUID(0L, 0L);

    /** Le poste virtuel « Hébergé » : les projets sans machine, gouvernés ensemble. */
    public static final GovernanceHostRef HOSTED = new GovernanceHostRef(HOSTED_ID, true);

    /** Un poste réel, désigné par son identifiant. */
    public static GovernanceHostRef of(UUID hostId) {
        if (hostId == null || HOSTED_ID.equals(hostId)) {
            // Personne ne construit « Hébergé » par un identifiant : il n'en a pas.
            throw new RunnerHostNotFoundException("Poste introuvable.");
        }
        return new GovernanceHostRef(hostId, false);
    }

    /**
     * Lit une référence de poste venue d'une URL.
     *
     * <p>Deux formes, et deux seulement : le mot {@value #HOSTED_REF}, ou l'identifiant d'un poste.
     * Tout le reste est « introuvable » — un identifiant mal formé ne mérite pas un message
     * différent d'un poste inexistant.</p>
     */
    public static GovernanceHostRef parse(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new RunnerHostNotFoundException("Poste introuvable.");
        }
        if (HOSTED_REF.equalsIgnoreCase(ref.strip())) {
            return HOSTED;
        }
        UUID id;
        try {
            id = UUID.fromString(ref.strip());
        } catch (IllegalArgumentException ex) {
            throw new RunnerHostNotFoundException("Poste introuvable : " + ref);
        }
        return of(id);
    }

    /** La référence telle qu'elle s'écrit dans une URL — et telle que l'écran la reçoit. */
    public String ref() {
        return hosted ? HOSTED_REF : hostId.toString();
    }

    /** Identifiant du poste <b>réel</b>, ou {@code null} pour « Hébergé » (F-71 : jamais d'identifiant). */
    public UUID publicId() {
        return hosted ? null : hostId;
    }
}
