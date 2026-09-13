package fr.claudegateway.billing;

/**
 * Les <b>espaces</b> que l'offre vend (F-107 / SF-107-02, cadrage §3) : chacun est une ligne d'offre
 * complète, avec son droit propre.
 *
 * <p>La Forge sert à <b>faire</b> (runner, projets, terminaux, carte, gouvernance — l'ancien Atelier),
 * la Vigie à <b>piloter</b> (Teams, Radar, réunions). La passerelle (conversations, fichiers,
 * historique) n'est pas un espace : tout plan la comprend.</p>
 *
 * <p>Enum propre au paquet {@code billing} : le droit ne dépend pas du modèle des postes
 * ({@code runner.host.ClientSpace}), comme le supplément par poste ne dépend que de {@code SeatSource}.</p>
 */
public enum EntitlementSpace {

    /** L'espace où l'on fabrique : l'ancien Atelier. */
    FORGE,

    /** L'espace d'où l'on voit venir : Teams, Radar, réunions. */
    VIGIE
}
