package fr.claudegateway.atelier.actions;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * <b>Quand relancer</b> (F-175 / SF-175-06, décision D8) : une attente « Demandé » depuis plus de
 * {@code APP_ATELIER_FOLLOWUP_DAYS} jours <b>ouvrés</b> (défaut 3) appelle une relance. En prod, des
 * attentes s'enlisaient neuf jours sans que rien ne le dise.
 *
 * <p>Jours ouvrés = du lundi au vendredi, sans calendrier de fériés (arbitrage réversible : un férié
 * avance la relance d'un jour, ce qui ne coûte qu'un rappel un peu tôt).</p>
 */
@Component
public class TerminalActionFollowUp {

    private final int days;

    public TerminalActionFollowUp(@Value("${app.atelier.followup-days:3}") int days) {
        this.days = Math.max(1, days);
    }

    /** Le seuil, en jours ouvrés. */
    public int days() {
        return days;
    }

    /** Vrai si l'attente est « Demandé » depuis au moins le seuil de jours ouvrés. */
    public boolean isDue(TerminalAction action, OffsetDateTime now) {
        if (action == null || action.getStatus() != TerminalActionStatus.DEMANDE || now == null) {
            return false;
        }
        OffsetDateTime since = action.getRequestedAt() != null ? action.getRequestedAt() : action.getCreatedAt();
        return since != null && businessDaysBetween(since.toLocalDate(), now.toLocalDate()) >= days;
    }

    /** Les jours ouvrés écoulés après {@code from} jusqu'à {@code to} inclus. */
    static int businessDaysBetween(LocalDate from, LocalDate to) {
        int count = 0;
        for (LocalDate day = from.plusDays(1); !day.isAfter(to); day = day.plusDays(1)) {
            DayOfWeek dow = day.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                count++;
            }
        }
        return count;
    }
}
