package fr.claudegateway.activity.dto;

import java.time.YearMonth;

import fr.claudegateway.activity.ActivityConfigService;

/**
 * Le réglage de suivi d'activité rendu à l'écran (F-124 / SF-124-01) : le mois de départ du cumul,
 * au format {@code YYYY-MM}.
 */
public record ActivitySettingsResponse(String startMonth) {

    public static ActivitySettingsResponse of(YearMonth startMonth) {
        return new ActivitySettingsResponse(ActivityConfigService.formatMonth(startMonth));
    }
}
