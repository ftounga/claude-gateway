package fr.claudegateway.governance.dto;

/**
 * Une entrée de mon catalogue personnel (F-51 / SF-51-02).
 *
 * @param pkg            le paquet retenu, tel qu'il est publié
 * @param defaultApplied embarqué par mes projets à venir, sans que j'aie rien à cocher
 * @param activeProjects nombre de <b>mes</b> projets où ce paquet est actif en ce moment
 */
public record GovernanceSelectionView(GovernancePackageView pkg, boolean defaultApplied,
        int activeProjects) {
}
