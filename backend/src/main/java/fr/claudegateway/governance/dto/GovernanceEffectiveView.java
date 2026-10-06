package fr.claudegateway.governance.dto;

import java.util.List;
import java.util.UUID;

/**
 * <b>Ce qui s'applique vraiment sur ce poste</b> (F-177 / SF-177-04, décision D6) : les règles du client
 * ({@code GOUVERNANCE.md} du poste et des sujets), les skills avec leur origine, et les paquets activés
 * avec leur <b>retard de dépôt</b>.
 *
 * @param hostRef  le poste
 * @param hostRules le {@code GOUVERNANCE.md} de la racine du poste
 * @param subjects les sujets et leur {@code GOUVERNANCE.md} (tel que lu au dernier tour)
 * @param skills   les skills, poste puis sujets
 * @param packages les paquets activés et leur état de dépôt
 */
public record GovernanceEffectiveView(String hostRef, RulesFile hostRules, List<SubjectRules> subjects,
        List<SkillView> skills, List<PackageLag> packages) {

    /**
     * Un {@code GOUVERNANCE.md}.
     *
     * @param state     {@code PRESENT} | {@code ABSENT} | {@code INJOIGNABLE} | {@code INCONNU} | {@code SANS_MACHINE}
     * @param excerpt   son contenu, borné (ou {@code null})
     * @param truncated vrai si l'extrait est coupé
     */
    public record RulesFile(String state, String excerpt, boolean truncated) {
    }

    /** Un sujet et ses règles propres. */
    public record SubjectRules(UUID workspaceId, String name, RulesFile rules) {
    }

    /**
     * Un skill.
     *
     * @param name        nom invocable par {@code /}
     * @param path        son fichier
     * @param origin      {@code POSTE} | {@code SUJET}
     * @param subjectName le sujet, pour un skill de sujet
     * @param source      {@code PAQUET} (déposé par un paquet) | {@code CLIENT} (écrit chez le client)
     * @param packageName le paquet d'origine, le cas échéant
     */
    public record SkillView(String name, String path, String origin, String subjectName, String source,
            String packageName) {
    }

    /**
     * Un paquet activé et son retard de dépôt.
     *
     * @param state  {@code A_JOUR} | {@code EN_RETARD} | {@code JAMAIS_DEPOSE}
     * @param message phrase lisible (« fichiers en v6, paquet en v17 »)
     */
    public record PackageLag(UUID packageId, String name, int packageVersion, Integer filesMinVersion,
            Integer filesMaxVersion, int depositedFiles, String state, String message) {
    }
}
