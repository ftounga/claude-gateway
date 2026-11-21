package fr.claudegateway.atelier.skills;

import java.util.List;

/**
 * <b>Ce qu'un skill déclare pour être lancé d'un clic</b> (F-275 / SF-275-01, D1-D2) : ses déclencheurs (les phrases
 * qui le font partir), le sujet du poste où le lancer, et s'il part directement.
 *
 * @param triggers les phrases de démarrage déclarées (au plus {@link SkillTriggers#MAX_TRIGGERS}), dans l'ordre
 * @param subject  le nom du sujet du poste où lancer le skill (en-tête {@code sujet:}), ou {@code null} : terminal courant
 * @param send     vrai si l'en-tête dit {@code clic: envoyer} ; sinon la phrase est déposée dans la saisie
 */
public record SkillLaunch(List<String> triggers, String subject, boolean send) {

    /** Un skill qui ne déclare rien. */
    public static final SkillLaunch NONE = new SkillLaunch(List.of(), null, false);

    public SkillLaunch {
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
    }

    /** Vrai si le skill déclare au moins un déclencheur. */
    public boolean declared() {
        return !triggers.isEmpty();
    }
}
