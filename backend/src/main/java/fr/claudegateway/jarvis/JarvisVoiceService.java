package fr.claudegateway.jarvis;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.quota.ProviderPricingProperties;
import fr.claudegateway.speech.SpeechProperties;
import fr.claudegateway.speech.SpeechProvider;
import fr.claudegateway.speech.SpeechProviderUnavailableException;
import fr.claudegateway.speech.SpeechUsage;

/**
 * <b>La voix de Jarvis</b> (F-231 / SF-231-01, D2) : lit une phrase courte, en flux, avec la voix masculine posée du
 * mode Jarvis, et compte ce que cela coûte.
 *
 * <p><b>Rien n'est réimplémenté</b> : la synthèse est celle de la voix off ({@link SpeechProvider}, F-215,
 * Provider Independence). Ce service ne fait que ce que la passerelle doit faire : borner, plafonner, relayer,
 * compter — et ne rien garder (ni texte ni audio ne sont conservés ou journalisés).</p>
 *
 * <p><b>Le plafond</b> est mensuel et en euros ({@link JarvisVoiceProperties#monthlyBudgetEur()}). Atteint, plus
 * aucun appel ne part : l'écran retombe sur le texte. Le budget de pilotage {@code CostBudget} (F-133) n'est pas
 * touché : il ne refuse rien, par conception.</p>
 */
@Service
public class JarvisVoiceService {

    private static final Logger log = LoggerFactory.getLogger(JarvisVoiceService.class);

    static final BigDecimal DEFAULT_USD_TO_EUR = new BigDecimal("0.92");

    private final SpeechProvider provider;
    private final SpeechProperties speech;
    private final JarvisVoiceProperties properties;
    private final SpeechUsage ledger;
    private final JarvisUsageService usage;
    private final WorkspaceRepository workspaces;
    private BigDecimal usdToEur = DEFAULT_USD_TO_EUR;

    public JarvisVoiceService(SpeechProvider provider, SpeechProperties speech, JarvisVoiceProperties properties,
            SpeechUsage ledger, JarvisUsageService usage, WorkspaceRepository workspaces) {
        this.provider = provider;
        this.speech = speech;
        this.properties = properties;
        this.ledger = ledger;
        this.usage = usage;
        this.workspaces = workspaces;
    }

    @Autowired(required = false)
    public void setPricing(ProviderPricingProperties pricing) {
        if (pricing != null && pricing.usdToEur() != null) {
            this.usdToEur = pricing.usdToEur();
        }
    }

    /** L'état de la voix pour l'écran : allumée, voix, plafond et dépense du mois en euros. */
    public VoiceStatus status(UUID userId) {
        BigDecimal spent = eur(usage.monthSpeechCostUsd(userId));
        BigDecimal budget = properties.monthlyBudgetEur().setScale(2, RoundingMode.HALF_UP);
        return new VoiceStatus(speech.isActive() && budget.signum() > 0, properties.voice(), budget, spent,
                reached(spent, budget));
    }

    /**
     * Prépare une lecture : valide, coupe, vérifie le plafond. Aucun appel fournisseur ici — l'écran reçoit une
     * erreur nette <b>avant</b> que la réponse audio ne commence.
     *
     * @throws IllegalArgumentException            texte vide ou trop long
     * @throws SpeechProviderUnavailableException  voix éteinte ou non configurée
     * @throws JarvisVoiceBudgetException          plafond du mois atteint
     */
    public Reading prepare(UUID userId, UUID workspaceId, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Rien à lire.");
        }
        if (text.length() > properties.maxRequestChars()) {
            throw new IllegalArgumentException("Texte trop long pour être lu à voix haute.");
        }
        if (!speech.isActive()) {
            throw new SpeechProviderUnavailableException("La voix n'est pas configurée sur cette installation.");
        }
        if (!speech.allows(properties.voice())) {
            throw new SpeechProviderUnavailableException("La voix de Jarvis n'est pas dans la liste des voix.");
        }
        VoiceStatus status = status(userId);
        if (status.budgetReached()) {
            throw new JarvisVoiceBudgetException();
        }
        // Rattachement au sujet seulement s'il appartient au compte : un identifiant d'autrui est ignoré, sans
        // erreur qui en révélerait l'existence.
        Workspace workspace = workspaceId == null ? null
                : workspaces.findByIdAndUserId(workspaceId, userId).orElse(null);
        return new Reading(userId, workspace == null ? null : workspace.getId(),
                workspace == null ? null : workspace.getHostId(), cut(text, properties.maxChars()));
    }

    /**
     * Lit à voix haute, en flux, puis compte la lecture (journal d'usage + journée Jarvis).
     *
     * @return le nombre d'octets écrits
     */
    public long speak(Reading reading, OutputStream out) throws IOException {
        long written = provider.synthesizeStream(reading.text(), properties.voice(), properties.instructions(), out);
        long characters = reading.text().length();
        ledger.record(reading.userId(), reading.workspaceId(), reading.hostId(), characters, 0d);
        usage.addSpeech(reading.userId(), characters, costUsd(characters));
        // On journalise la LONGUEUR, jamais le texte.
        log.debug("Jarvis a parlé ({} caractères)", characters);
        return written;
    }

    /** Le coût en dollars de {@code characters} caractères lus, au tarif de la voix off. */
    BigDecimal costUsd(long characters) {
        return speech.costUsdPerThousandChars().multiply(BigDecimal.valueOf(characters))
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal eur(BigDecimal usd) {
        return (usd == null ? BigDecimal.ZERO : usd).multiply(usdToEur).setScale(2, RoundingMode.HALF_UP);
    }

    private static boolean reached(BigDecimal spentEur, BigDecimal budgetEur) {
        return budgetEur.signum() <= 0 || spentEur.compareTo(budgetEur) >= 0;
    }

    /**
     * Coupe {@code text} à au plus {@code max} caractères, à la dernière fin de phrase si possible : Jarvis lit des
     * phrases entières (D3), jamais un mot tranché. Espaces repliés.
     */
    static String cut(String text, int max) {
        String flat = text.strip().replaceAll("\\s+", " ");
        if (flat.length() <= max) {
            return flat;
        }
        String head = flat.substring(0, max);
        int end = -1;
        for (int i = head.length() - 1; i >= 0; i--) {
            char c = head.charAt(i);
            if ((c == '.' || c == '!' || c == '?' || c == '…') && (i == head.length() - 1 || head.charAt(i + 1) == ' ')) {
                end = i;
                break;
            }
        }
        if (end >= max / 3) {
            return head.substring(0, end + 1);
        }
        // La borne tombe juste avant une espace : le dernier mot est entier, on le garde.
        int space = flat.charAt(max) == ' ' ? max : head.lastIndexOf(' ');
        return (space > 0 ? head.substring(0, space) : head).stripTrailing() + "…";
    }

    /** Une lecture prête : le texte coupé, le compte, le sujet et le poste de rattachement (ou {@code null}). */
    public record Reading(UUID userId, UUID workspaceId, UUID hostId, String text) {
    }

    /** L'état de la voix pour l'écran, montants en euros. */
    public record VoiceStatus(boolean enabled, String voice, BigDecimal monthlyBudgetEur, BigDecimal monthSpentEur,
            boolean budgetReached) {
    }
}
