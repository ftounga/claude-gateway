package fr.claudegateway.jarvis.activity;

import java.util.List;
import java.util.UUID;

/**
 * <b>La carte d'activité de Jarvis</b> (F-236 / SF-236-26, NF-16) : le nombre de tours par sujet et par jour sur les
 * derniers jours. <b>Aucun contenu</b> : des identifiants de sujets du compte, des dates et des nombres.
 *
 * @param days     les jours couverts, du plus ancien à aujourd'hui (« 2026-10-27 »), au plus
 *                 {@value JarvisActivityService#MAX_DAYS}
 * @param subjects les sujets du compte qui ont au moins un tour sur la période, au plus
 *                 {@value JarvisActivityService#MAX_SUBJECTS}
 */
public record JarvisActivity(List<String> days, List<Subject> subjects) {

    /**
     * @param subjectId le sujet (un workspace du compte)
     * @param turns     un nombre de tours par jour, dans l'ordre de {@code days}
     */
    public record Subject(UUID subjectId, List<Integer> turns) {
    }
}
