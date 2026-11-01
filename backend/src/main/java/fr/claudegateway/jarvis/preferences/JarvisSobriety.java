package fr.claudegateway.jarvis.preferences;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.veille.JarvisWatchAlertRepository;

/**
 * <b>Le ton sobre forcé</b> (F-235 / SF-235-09, D18) — déterministe, sans appel modèle. Quand le tour porte une
 * alerte, un incident, une erreur ou un échec, le message reçoit la note fixe {@link #NOTE} : <b>aucun humour</b>,
 * pour le cerveau rapide comme pour Opus.
 *
 * <p>Signaux (liste fermée) : une alerte de veille du compte depuis moins de {@link #ALERT_WINDOW} ; la parole parle
 * d'incident, d'erreur, de panne, d'échec, d'alerte, d'urgence ; le contexte porte une alerte ou un échec.</p>
 */
@Component
public class JarvisSobriety {

    /** La note fixe, jointe au message (jamais à la consigne système). */
    public static final String NOTE = "[Jarvis — ton sobre, sans humour]";

    static final Duration ALERT_WINDOW = Duration.ofHours(2);

    /** Mots (normalisés) qui signalent un incident dans la parole ou le contexte. */
    static final List<String> SIGNALS = List.of("incident", "erreur", "panne", "echec", "echoue", "plante",
            "plantage", "crash", "alerte", "urgent", "urgence", "en rade", "down", "bloquant", "critique",
            "sensible", "licenciement", "deces", "malade", "litige", "plainte");

    private final Clock clock;
    private JarvisWatchAlertRepository alerts;

    public JarvisSobriety(Clock clock) {
        this.clock = clock;
    }

    @Autowired(required = false)
    public void setAlerts(JarvisWatchAlertRepository alerts) {
        this.alerts = alerts;
    }

    /** Vrai si le ton doit être sobre pour ce compte et cette parole. Best-effort : en panne ⇒ faux. */
    public boolean sober(UUID userId, String said) {
        if (mentionsTrouble(said)) {
            return true;
        }
        if (alerts == null || userId == null) {
            return false;
        }
        try {
            return !alerts.findByUserIdAndCreatedAtGreaterThanEqual(userId,
                    OffsetDateTime.now(clock).minus(ALERT_WINDOW)).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Vrai si le texte parle d'un incident, d'une erreur, d'un échec, d'une alerte, d'un sujet sensible. */
    public static boolean mentionsTrouble(String text) {
        String n = " " + fr.claudegateway.jarvis.fastbrain.JarvisFastGuards.normalize(text) + " ";
        for (String signal : SIGNALS) {
            if (n.contains(" " + signal + " ") || n.contains(" " + signal + "s ")) {
                return true;
            }
        }
        return false;
    }
}
