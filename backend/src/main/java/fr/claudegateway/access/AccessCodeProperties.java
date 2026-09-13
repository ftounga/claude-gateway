package fr.claudegateway.access;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglages des codes d'accès à durée limitée (F-62). Trois durées, toutes réversibles, aucune n'est
 * un secret : elles décrivent une politique commerciale, pas une clé.
 *
 * @param durationHours durée du <b>droit</b> ouvert par un code, en heures (défaut 24). Figée dans la
 *                      ligne à l'émission : la changer n'altère jamais un code déjà remis.
 * @param validityDays  durée de vie d'un code <b>non consommé</b>, en jours (défaut 30). C'est le
 *                      « daté » de la spec : un code oublié dans un e-mail finit par ne plus rien valoir.
 * @param graceMinutes  <b>grâce de tour</b>, en minutes (défaut 15). Le droit de Forge est relu à
 *                      chaque requête d'un tour (relances du runner, flux SSE) ; fermer la porte à la
 *                      seconde du terme couperait un tour engagé en plein milieu. La grâce laisse
 *                      finir ce qui était commencé — elle n'ajoute aucun jeton, puisque le quota n'a
 *                      jamais été touché, et elle n'offre pas une seconde journée.
 */
@ConfigurationProperties(prefix = "app.access-code")
public record AccessCodeProperties(
        Integer durationHours,
        Integer validityDays,
        Integer graceMinutes,
        Integer vigieTrialDays) {

    private static final int DEFAULT_DURATION_HOURS = 24;
    private static final int DEFAULT_VALIDITY_DAYS = 30;
    private static final int DEFAULT_GRACE_MINUTES = 15;
    /** L'essai Vigie : deux semaines (F-107 §9, décidé par le PO le 2026-09-13). */
    private static final int DEFAULT_VIGIE_TRIAL_DAYS = 14;

    /** Configuration d'avant F-107 : essai Vigie à sa durée par défaut. */
    public AccessCodeProperties(Integer durationHours, Integer validityDays, Integer graceMinutes) {
        this(durationHours, validityDays, graceMinutes, null);
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public AccessCodeProperties {
        if (durationHours == null || durationHours <= 0) {
            durationHours = DEFAULT_DURATION_HOURS;
        }
        if (validityDays == null || validityDays <= 0) {
            validityDays = DEFAULT_VALIDITY_DAYS;
        }
        // Une grâce nulle est légitime (fermeture nette) ; une grâce négative ne l'est pas.
        if (graceMinutes == null || graceMinutes < 0) {
            graceMinutes = DEFAULT_GRACE_MINUTES;
        }
        if (vigieTrialDays == null || vigieTrialDays <= 0) {
            vigieTrialDays = DEFAULT_VIGIE_TRIAL_DAYS;
        }
    }
}
