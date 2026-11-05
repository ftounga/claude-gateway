package fr.claudegateway.jarvis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import fr.claudegateway.quota.ProviderPricingProperties;

/**
 * <b>Le compteur visible de Jarvis</b> (F-239 / SF-239-12) : ce que Jarvis a coûté aujourd'hui et depuis le début du
 * mois, <b>en euros</b>, par poste — cerveau rapide, Opus délégué (tours nés de Jarvis), voix, écoute (flux et
 * retranscription). Lu dans {@code jarvis_usage_days} <b>sous {@code user_id}</b> ; la conversion en euros se fait à
 * la lecture, au taux de la passerelle.
 */
@Service
public class JarvisCostService {

    static final BigDecimal DEFAULT_USD_TO_EUR = new BigDecimal("0.92");
    static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    private final JarvisUsageDayRepository repository;
    private final Clock clock;
    private BigDecimal usdToEur = DEFAULT_USD_TO_EUR;

    public JarvisCostService(JarvisUsageDayRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Autowired(required = false)
    public void setPricing(ProviderPricingProperties pricing) {
        if (pricing != null && pricing.usdToEur() != null) {
            this.usdToEur = pricing.usdToEur();
        }
    }

    /** Un poste par poste, en euros (au centime). */
    public record Costs(BigDecimal fastBrain, BigDecimal opus, BigDecimal voice, BigDecimal listening, BigDecimal total,
            int opusTurns, BigDecimal mail, BigDecimal agenda, BigDecimal state) {

        /** Sans le registre d'état (avant F-247 : captures et constats). */
        public Costs(BigDecimal fastBrain, BigDecimal opus, BigDecimal voice, BigDecimal listening, BigDecimal total,
                int opusTurns, BigDecimal mail, BigDecimal agenda) {
            this(fastBrain, opus, voice, listening, total, opusTurns, mail, agenda, BigDecimal.ZERO.setScale(2));
        }

        /** Sans courrier ni agenda (avant F-242 / F-243). */
        public Costs(BigDecimal fastBrain, BigDecimal opus, BigDecimal voice, BigDecimal listening, BigDecimal total,
                int opusTurns) {
            this(fastBrain, opus, voice, listening, total, opusTurns, BigDecimal.ZERO.setScale(2),
                    BigDecimal.ZERO.setScale(2));
        }
    }

    /** Aujourd'hui et le mois en cours. */
    public record Summary(Costs today, Costs month) {
    }

    public Summary costs(UUID userId) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZONE);
        List<JarvisUsageDay> month = repository.findByUserIdAndDayGreaterThanEqualOrderByDayAsc(userId,
                today.withDayOfMonth(1));
        return new Summary(sum(month.stream().filter(d -> today.equals(d.getDay())).toList()), sum(month));
    }

    /** F-247 — un montant en dollars, en euros au taux de la passerelle (plafonds propres des captures et constats). */
    public BigDecimal toEur(BigDecimal usd) {
        return (usd == null ? BigDecimal.ZERO : usd).multiply(usdToEur).setScale(4, RoundingMode.HALF_UP);
    }

    Costs sum(List<JarvisUsageDay> days) {
        return sum(days, usdToEur);
    }

    /** F-244 / SF-244-04 — la ventilation de journées, au taux donné (la mesure admin la reprend). */
    static Costs sum(List<JarvisUsageDay> days, BigDecimal usdToEur) {
        BigDecimal fast = BigDecimal.ZERO;
        BigDecimal opus = BigDecimal.ZERO;
        BigDecimal voice = BigDecimal.ZERO;
        BigDecimal listening = BigDecimal.ZERO;
        BigDecimal mail = BigDecimal.ZERO;
        BigDecimal agenda = BigDecimal.ZERO;
        BigDecimal state = BigDecimal.ZERO;
        int turns = 0;
        for (JarvisUsageDay d : days) {
            mail = mail.add(nz(d.getMailCostUsd()));
            agenda = agenda.add(nz(d.getAgendaCostUsd()));
            // F-247 (D16) : captures lues et constats rédigés, au budget Jarvis.
            state = state.add(nz(d.getCaptureCostUsd())).add(nz(d.getNotesCostUsd()));
            fast = fast.add(nz(d.getFastBrainCostUsd()));
            opus = opus.add(nz(d.getOpusCostUsd()));
            voice = voice.add(nz(d.getSpeechCostUsd()));
            // F-244 / SF-244-01 : l'écoute = flux + retranscription + dictée de repli (désormais en colonnes séparées).
            listening = listening.add(nz(d.getSttStreamCostUsd())).add(nz(d.getSttUtteranceCostUsd()))
                    .add(nz(d.getDictationCostUsd()));
            turns += d.getOpusTurns();
        }
        // F-244 / SF-244-04 : le total est la somme des postes AFFICHÉS (arrondis au centime) — l'écran additionne juste.
        BigDecimal total = eur(fast, usdToEur).add(eur(opus, usdToEur)).add(eur(voice, usdToEur))
                .add(eur(listening, usdToEur)).add(eur(mail, usdToEur)).add(eur(agenda, usdToEur))
                .add(eur(state, usdToEur));
        return new Costs(eur(fast, usdToEur), eur(opus, usdToEur), eur(voice, usdToEur), eur(listening, usdToEur), total,
                turns, eur(mail, usdToEur), eur(agenda, usdToEur), eur(state, usdToEur));
    }

    private static BigDecimal eur(BigDecimal usd, BigDecimal usdToEur) {
        return usd.multiply(usdToEur).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
