package fr.claudegateway.videos;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <b>Les coefficients du devis d'une vidéo</b> (F-216 / SF-216-01), surchargeables sous {@code app.videos.cost.*}.
 * Une valeur absente, nulle ou négative retombe sur le défaut : une faute de configuration ne doit ni rendre un devis
 * absurde, ni le faire échouer.
 *
 * <p>Les tarifs de la <b>voix</b> ne sont pas ici : ce sont ceux de {@code voix_off} ({@code app.speech.*}), jamais
 * dupliqués.</p>
 *
 * <p>Un seul constructeur (le canonique) : un second casserait la liaison Spring.</p>
 *
 * @param charsPerMinute               caractères de narration par minute (français posé : 900)
 * @param turnsPerMinuteLow            tours de l'agent par minute de vidéo, borne basse (0,8 : 2 tours pour 2 min 30)
 * @param turnsPerMinuteHigh           idem, borne haute (1,6 : 4 tours pour 2 min 30)
 * @param turnsPerRetoucheLow          tours par retouche et par épisode, borne basse
 * @param turnsPerRetoucheHigh         idem, borne haute
 * @param seriesPlanningTurnsLow       tours de plan et de charte d'une série, borne basse
 * @param seriesPlanningTurnsHigh      idem, borne haute
 * @param retoucheVoiceFraction        part de la narration refaite par retouche (borne haute de la voix)
 * @param defaultTurnCostUsd           coût d'un tour quand les mesures manquent
 * @param minTurnSamples               tours mesurés exigés pour se fier à la moyenne
 * @param turnCostWindow               fenêtre glissante de la moyenne
 * @param renderMinutesPerVideoMinute  minutes de rendu sur le poste par minute de vidéo
 * @param retoucheConfirmThresholdUsd  au-delà (borne haute), une retouche se fait confirmer par une question
 * @param idleCloseHours               un devis ouvert sans activité depuis ce délai est clos (SF-216-02)
 */
@ConfigurationProperties(prefix = "app.videos.cost")
public record VideoCostProperties(
        Integer charsPerMinute,
        BigDecimal turnsPerMinuteLow,
        BigDecimal turnsPerMinuteHigh,
        BigDecimal turnsPerRetoucheLow,
        BigDecimal turnsPerRetoucheHigh,
        BigDecimal seriesPlanningTurnsLow,
        BigDecimal seriesPlanningTurnsHigh,
        BigDecimal retoucheVoiceFraction,
        BigDecimal defaultTurnCostUsd,
        Integer minTurnSamples,
        Duration turnCostWindow,
        BigDecimal renderMinutesPerVideoMinute,
        BigDecimal retoucheConfirmThresholdUsd,
        Integer idleCloseHours) {

    public VideoCostProperties {
        charsPerMinute = charsPerMinute == null || charsPerMinute <= 0 ? 900 : charsPerMinute;
        turnsPerMinuteLow = orDefault(turnsPerMinuteLow, "0.8");
        turnsPerMinuteHigh = orDefault(turnsPerMinuteHigh, "1.6");
        if (turnsPerMinuteHigh.compareTo(turnsPerMinuteLow) < 0) {
            turnsPerMinuteHigh = turnsPerMinuteLow;
        }
        turnsPerRetoucheLow = orDefault(turnsPerRetoucheLow, "0.5");
        turnsPerRetoucheHigh = orDefault(turnsPerRetoucheHigh, "1");
        if (turnsPerRetoucheHigh.compareTo(turnsPerRetoucheLow) < 0) {
            turnsPerRetoucheHigh = turnsPerRetoucheLow;
        }
        seriesPlanningTurnsLow = orDefault(seriesPlanningTurnsLow, "1");
        seriesPlanningTurnsHigh = orDefault(seriesPlanningTurnsHigh, "2");
        if (seriesPlanningTurnsHigh.compareTo(seriesPlanningTurnsLow) < 0) {
            seriesPlanningTurnsHigh = seriesPlanningTurnsLow;
        }
        retoucheVoiceFraction = orDefault(retoucheVoiceFraction, "0.5");
        defaultTurnCostUsd = defaultTurnCostUsd == null || defaultTurnCostUsd.signum() <= 0
                ? new BigDecimal("1.00") : defaultTurnCostUsd;
        minTurnSamples = minTurnSamples == null || minTurnSamples <= 0 ? 5 : minTurnSamples;
        turnCostWindow = turnCostWindow == null || turnCostWindow.isZero() || turnCostWindow.isNegative()
                ? Duration.ofDays(7) : turnCostWindow;
        renderMinutesPerVideoMinute = orDefault(renderMinutesPerVideoMinute, "2.8");
        retoucheConfirmThresholdUsd = orDefault(retoucheConfirmThresholdUsd, "3.00");
        idleCloseHours = idleCloseHours == null || idleCloseHours <= 0 ? 48 : idleCloseHours;
    }

    /** Les coefficients du cadrage, sans configuration. */
    public static VideoCostProperties defaults() {
        return new VideoCostProperties(null, null, null, null, null, null, null, null, null, null, null, null, null,
                null);
    }

    private static BigDecimal orDefault(BigDecimal value, String fallback) {
        return value == null || value.signum() < 0 ? new BigDecimal(fallback) : value;
    }
}
