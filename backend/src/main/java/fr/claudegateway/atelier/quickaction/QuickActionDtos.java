package fr.claudegateway.atelier.quickaction;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Les formes échangées par l'API des actions rapides (F-275 / SF-275-01). */
public final class QuickActionDtos {

    private QuickActionDtos() {
    }

    /** Source d'une action de la liste. */
    public static final String PERSO = "PERSO";
    public static final String SKILL = "SKILL";

    /**
     * Une action de la liste d'un terminal.
     *
     * @param key               clé stable (lancements) : {@code c:<uuid>} ou {@code s:<ancre>:<skill>:<déclencheur>}
     * @param source            {@code PERSO} ou {@code SKILL}
     * @param id                l'action personnalisée, ou {@code null} (skill)
     * @param label             le libellé du menu
     * @param phrase            la phrase de démarrage
     * @param skill             le skill qui déclare ce déclencheur, ou {@code null}
     * @param scope             portée (SUJET / POSTE / COMPTE) ; pour un skill, son origine (SUJET / POSTE)
     * @param targetWorkspaceId le sujet où lancer, ou {@code null} : le terminal où l'on clique
     * @param targetName        son nom, ou {@code null}
     * @param targetMissing     vrai si un sujet cible est désigné mais introuvable
     * @param clickMode         {@code DEPOSER} ou {@code ENVOYER}
     * @param suggestDays       jours ISO de la suggestion (1 = lundi), vide si aucune
     * @param suggestMoment     {@code MATIN}, {@code APRES_MIDI}, {@code JOURNEE} ou {@code null}
     * @param lastRunAt         dernier lancement depuis le bouton, ou {@code null}
     */
    public record QuickActionView(String key, String source, UUID id, String label, String phrase, String skill,
            String scope, UUID targetWorkspaceId, String targetName, boolean targetMissing, String clickMode,
            List<Integer> suggestDays, String suggestMoment, OffsetDateTime lastRunAt) {
    }

    /**
     * Création ou modification (champs {@code null} = inchangés à la modification ; {@code clearTarget} retire le sujet
     * cible, {@code clearSuggestion} la suggestion).
     */
    public record QuickActionRequest(String label, String phrase, String scope, UUID targetWorkspaceId,
            Boolean clearTarget, String clickMode, List<Integer> suggestDays, String suggestMoment,
            Boolean clearSuggestion) {
    }

    /** Un lancement à enregistrer. */
    public record LaunchRequest(String key) {
    }

    /** Le lancement enregistré. */
    public record LaunchView(String key, OffsetDateTime lastRunAt) {
    }
}
