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
import fr.claudegateway.jarvis.audition.JarvisVoiceChoiceRepository;
import fr.claudegateway.quota.ProviderPricingProperties;
import fr.claudegateway.speech.SpeechProperties;
import fr.claudegateway.speech.elevenlabs.ElevenLabsProperties;
import fr.claudegateway.speech.elevenlabs.ElevenLabsSpeechProvider;
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
    /** F-233 / SF-233-06 — la voix ElevenLabs, candidate (absente si non déclarée). */
    private ElevenLabsSpeechProvider elevenLabs;
    private ElevenLabsProperties elevenLabsProperties;
    private JarvisVoiceChoiceRepository choices;

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

    /** F-233 / SF-233-06 — la voix ElevenLabs, candidate ; choisie par {@code app.jarvis.voice.provider}. */
    @Autowired(required = false)
    public void setElevenLabs(ElevenLabsSpeechProvider elevenLabs, ElevenLabsProperties elevenLabsProperties) {
        this.elevenLabs = elevenLabs;
        this.elevenLabsProperties = elevenLabsProperties;
    }

    /** F-233 / SF-233-07 — les choix de voix faits à l'audition (absent hors contexte Spring). */
    @Autowired(required = false)
    public void setChoices(JarvisVoiceChoiceRepository choices) {
        this.choices = choices;
    }

    /** F-233 / SF-233-06 — la voix de Jarvis par défaut (configuration) : fournisseur et voix. */
    public VoiceRef current() {
        return new VoiceRef(properties.provider(), properties.voice());
    }

    /**
     * F-233 / SF-233-07 — la voix de Jarvis <b>pour ce compte</b> : celle choisie à l'audition (la plus récente),
     * sinon la configuration.
     */
    public VoiceRef current(UUID userId) {
        if (choices != null && userId != null) {
            return choices.findFirstByUserIdOrderByChosenAtDesc(userId)
                    .map(c -> new VoiceRef(c.getProvider(), c.getVoice()))
                    .orElseGet(this::current);
        }
        return current();
    }

    /** L'état de la voix pour l'écran : allumée, voix, plafond et dépense du mois en euros. */
    public VoiceStatus status(UUID userId) {
        BigDecimal spent = eur(usage.monthSpeechCostUsd(userId));
        BigDecimal budget = properties.monthlyBudgetEur().setScale(2, RoundingMode.HALF_UP);
        VoiceRef voice = current(userId);
        return new VoiceStatus(available(voice) && budget.signum() > 0, voice.voice(), budget, spent,
                reached(spent, budget), voice.provider());
    }

    /** La voix est-elle appelable (fournisseur configuré, voix valide) ? Aucun appel. */
    public boolean available(VoiceRef voice) {
        if (ElevenLabsSpeechProvider.NAME.equals(voice.provider())) {
            return elevenLabs != null && elevenLabs.isConfigured() && ElevenLabsSpeechProvider.isVoiceId(voice.voice());
        }
        return "openai".equals(voice.provider()) && speech.isActive() && speech.allows(voice.voice());
    }

    private SpeechProvider providerFor(VoiceRef voice) {
        return ElevenLabsSpeechProvider.NAME.equals(voice.provider()) ? elevenLabs : provider;
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
        requireVoice(userId);
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
        VoiceRef voice = current(reading.userId());
        long written = providerFor(voice).synthesizeStream(reading.text(), voice.voice(), properties.instructions(),
                out);
        account(reading.userId(), reading.workspaceId(), reading.hostId(), voice, reading.text().length());
        return written;
    }

    /**
     * F-233 / SF-233-02 — synthétise d'un coup une phrase <b>fixe</b> (clip de réaction), avec la voix de Jarvis du
     * compte, et la compte. Le plafond est vérifié avant l'appel.
     *
     * @throws SpeechProviderUnavailableException voix éteinte ou non configurée
     * @throws JarvisVoiceBudgetException         plafond du mois atteint
     */
    public byte[] synthesizeClip(UUID userId, String text) {
        return synthesizeWith(userId, current(userId), text);
    }

    /**
     * F-233 / SF-233-07 — synthétise d'un coup une phrase <b>fixe</b> avec une voix donnée (échantillon d'audition),
     * plafonnée et comptée au tarif de son fournisseur.
     *
     * @throws SpeechProviderUnavailableException voix indisponible
     * @throws JarvisVoiceBudgetException         plafond du mois atteint
     */
    public byte[] synthesizeWith(UUID userId, VoiceRef voice, String text) {
        if (!available(voice)) {
            throw new SpeechProviderUnavailableException("Cette voix n'est pas disponible sur cette installation.");
        }
        if (status(userId).budgetReached()) {
            throw new JarvisVoiceBudgetException();
        }
        byte[] audio = providerFor(voice).synthesize(text, voice.voice(), properties.instructions()).bytes();
        account(userId, null, null, voice, text.length());
        return audio;
    }

    /** La voix de Jarvis du compte, « fournisseur:voix » (clé des clips : changer de voix les régénère). */
    public String voice(UUID userId) {
        VoiceRef voice = current(userId);
        return voice.provider() + ':' + voice.voice();
    }

    /** La voix de Jarvis par défaut, « fournisseur:voix ». */
    public String voice() {
        VoiceRef voice = current();
        return voice.provider() + ':' + voice.voice();
    }

    /** Vérifie que la voix du compte est allumée, configurée et dans la liste blanche. */
    void requireVoice(UUID userId) {
        VoiceRef voice = current(userId);
        if (ElevenLabsSpeechProvider.NAME.equals(voice.provider())) {
            if (!available(voice)) {
                throw new SpeechProviderUnavailableException("La voix ElevenLabs de Jarvis n'est pas configurée.");
            }
            return;
        }
        if (!speech.isActive()) {
            throw new SpeechProviderUnavailableException("La voix n'est pas configurée sur cette installation.");
        }
        if (!speech.allows(voice.voice())) {
            throw new SpeechProviderUnavailableException("La voix de Jarvis n'est pas dans la liste des voix.");
        }
    }

    /** Compte une lecture : journal d'usage et journée Jarvis. On journalise la LONGUEUR, jamais le texte. */
    private void account(UUID userId, UUID workspaceId, UUID hostId, VoiceRef voice, long characters) {
        ledger.record(userId, workspaceId, hostId, characters, 0d);
        usage.addSpeech(userId, characters, costUsd(voice, characters));
        log.debug("Jarvis a parlé ({} caractères)", characters);
    }

    /** Le coût en dollars de {@code characters} caractères lus avec la voix par défaut. */
    BigDecimal costUsd(long characters) {
        return costUsd(current(), characters);
    }

    /** Le coût en dollars de {@code characters} caractères lus avec {@code voice}, au tarif de son fournisseur. */
    BigDecimal costUsd(VoiceRef voice, long characters) {
        BigDecimal perThousand = ElevenLabsSpeechProvider.NAME.equals(voice.provider()) && elevenLabsProperties != null
                ? elevenLabsProperties.ttsCostUsdPerThousandChars() : speech.costUsdPerThousandChars();
        return perThousand.multiply(BigDecimal.valueOf(characters))
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
            boolean budgetReached, String provider) {
    }

    /** F-233 / SF-233-06 — une voix : son fournisseur ({@code openai}, {@code elevenlabs}) et son nom ou identifiant. */
    public record VoiceRef(String provider, String voice) {
    }
}
