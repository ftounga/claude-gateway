package fr.claudegateway.jarvis.fastbrain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.agent.AgentCallShape;
import fr.claudegateway.agent.AgentContextPolicy;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentReasoning;
import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnMode;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.jarvis.JarvisUsageService;
import fr.claudegateway.quota.ProviderCostCalculator;
import fr.claudegateway.quota.ProviderPricingProperties;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.quota.TurnCost;
import fr.claudegateway.quota.TurnTokens;
import fr.claudegateway.quota.UsageLedgerService;

/**
 * <b>Le cerveau rapide de Jarvis</b> (F-235 / SF-235-01) : tient la conversation vocale et, dès qu'il faut agir,
 * chercher ou réfléchir, <b>délègue</b> au tour Opus du terminal — inchangé.
 *
 * <p><b>Provider Independence</b> : l'appel passe par {@link AiAgentProvider} ; le modèle est un réglage. Le
 * raisonnement est <b>éteint</b> ({@link AgentReasoning#off(String)}) et la forme d'appel est <b>compacte</b>
 * ({@link AgentCallShape#compact(int)} : plafond court, aucun outil serveur, outil strict).</p>
 *
 * <p><b>Jamais muet, jamais inventé</b> (D9b) : refus, erreur, délai sans premier jeton, réponse vide ⇒ délégation
 * <b>automatique</b> de la parole d'origine. Plafond du mois atteint ou quota du compte épuisé ⇒ <b>repli</b> : l'écran
 * fait ce qu'il faisait avant (tout à Opus), sans erreur à l'oral (D15).</p>
 *
 * <p><b>Coût</b> : chaque appel est inscrit au journal d'usage (appel annexe, au tarif de son modèle) et dans la
 * journée Jarvis du compte, qui porte le plafond propre. <b>Aucun contenu journalisé</b>.</p>
 */
@Service
public class JarvisFastBrainService {

    private static final Logger log = LoggerFactory.getLogger(JarvisFastBrainService.class);
    static final BigDecimal DEFAULT_USD_TO_EUR = new BigDecimal("0.92");
    static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm",
            Locale.FRENCH);

    /** Ce que l'écran demande. */
    public record Request(UUID userId, Workspace workspace, Integer chantier, String text, String heardBeforeCut,
            String screen, boolean presentation) {

        public Request(UUID userId, Workspace workspace, Integer chantier, String text, String heardBeforeCut) {
            this(userId, workspace, chantier, text, heardBeforeCut, null, false);
        }

        public Request(UUID userId, Workspace workspace, Integer chantier, String text, String heardBeforeCut,
                String screen) {
            this(userId, workspace, chantier, text, heardBeforeCut, screen, false);
        }
    }

    /** Ce que le cerveau rapide a décidé. */
    public enum Kind {
        /** Il a répondu lui-même. */
        ANSWER,
        /** Il a délégué (lui-même, ou d'office). */
        DELEGATE,
        /** Il s'efface : l'écran garde le mode d'avant (plafond, quota). */
        FALLBACK
    }

    /** Une délégation : ce qu'Opus doit faire, et ce que Jarvis dit en la lançant. */
    public record Delegation(UUID delegationId, String demande, String label, String annonce, UUID subjectId,
            Integer chantier, boolean automatic, String reason, String diagnostic) {

        public Delegation(UUID delegationId, String demande, String label, String annonce, UUID subjectId,
                Integer chantier, boolean automatic, String reason) {
            this(delegationId, demande, label, annonce, subjectId, chantier, automatic, reason, null);
        }
    }

    /** Le résultat d'un échange. */
    public record Outcome(Kind kind, String spokenText, Delegation delegation, String reason, String model,
            long firstTokenMs, BigDecimal costUsd, Guards guards, String understood, List<JarvisShowCommand> shows) {

        public Outcome {
            shows = shows == null ? List.of() : List.copyOf(shows);
        }

        public Outcome(Kind kind, String spokenText, Delegation delegation, String reason, String model,
                long firstTokenMs, BigDecimal costUsd, Guards guards, String understood) {
            this(kind, spokenText, delegation, reason, model, firstTokenMs, costUsd, guards, understood, List.of());
        }

        /** F-237 / SF-237-08 — la même issue, avec les ordres d'écran relayés pendant l'échange. */
        public Outcome withShows(List<JarvisShowCommand> relayed) {
            return new Outcome(kind, spokenText, delegation, reason, model, firstTokenMs, costUsd, guards, understood,
                    relayed);
        }

        public Outcome(Kind kind, String spokenText, Delegation delegation, String reason, String model,
                long firstTokenMs, BigDecimal costUsd, Guards guards) {
            this(kind, spokenText, delegation, reason, model, firstTokenMs, costUsd, guards, null);
        }

        public Outcome(Kind kind, String spokenText, Delegation delegation, String reason, String model,
                long firstTokenMs, BigDecimal costUsd) {
            this(kind, spokenText, delegation, reason, model, firstTokenMs, costUsd, Guards.NONE, null);
        }

        /** F-239 / SF-239-05 — la même issue, avec la parole relue (ou {@code null}). */
        public Outcome withUnderstood(String text) {
            return new Outcome(kind, spokenText, delegation, reason, model, firstTokenMs, costUsd, guards, text,
                    shows);
        }

        /** Ce que l'utilisateur a voulu dire : la relecture acceptée, sinon {@code said}. */
        public String saidOr(String said) {
            return understood != null && !understood.isBlank() ? understood : said;
        }
    }

    /**
     * Ce que les garde-fous ont fait pendant l'échange (F-235 / SF-235-03, D9, D11) — compté, jamais de contenu ;
     * consommé par la mesure (SF-235-07).
     */
    public record Guards(int groundingTrips, int fillersRemoved, int repeatsRemoved, boolean forcedDelegation,
            boolean toolText, int imitationsRemoved, int portraitBlocked, String personalAllusion,
            int allusionsBlocked, List<fr.claudegateway.jarvis.portrait.JarvisPortraitNote.Pin> pins) {
        public static final Guards NONE = new Guards(0, 0, 0, false, false, 0, 0, null, 0, List.of());

        public Guards {
            pins = pins == null ? List.of() : List.copyOf(pins);
        }

        public Guards(int groundingTrips, int fillersRemoved, int repeatsRemoved, boolean forcedDelegation,
                boolean toolText, int imitationsRemoved, int portraitBlocked, String personalAllusion,
                int allusionsBlocked) {
            this(groundingTrips, fillersRemoved, repeatsRemoved, forcedDelegation, toolText, imitationsRemoved,
                    portraitBlocked, personalAllusion, allusionsBlocked, List.of());
        }

        /** F-241 / SF-241-05 — les mêmes gardes, avec ces lignes 📌 (relayées à l'écran par {@code done}). */
        public Guards withPins(List<fr.claudegateway.jarvis.portrait.JarvisPortraitNote.Pin> relayed) {
            return new Guards(groundingTrips, fillersRemoved, repeatsRemoved, forcedDelegation, toolText,
                    imitationsRemoved, portraitBlocked, personalAllusion, allusionsBlocked, relayed);
        }

        public Guards(int groundingTrips, int fillersRemoved, int repeatsRemoved, boolean forcedDelegation,
                boolean toolText, int imitationsRemoved) {
            this(groundingTrips, fillersRemoved, repeatsRemoved, forcedDelegation, toolText, imitationsRemoved, 0,
                    null, 0);
        }

        public Guards(int groundingTrips, int fillersRemoved, int repeatsRemoved, boolean forcedDelegation,
                boolean toolText, int imitationsRemoved, int portraitBlocked) {
            this(groundingTrips, fillersRemoved, repeatsRemoved, forcedDelegation, toolText, imitationsRemoved,
                    portraitBlocked, null, 0);
        }
    }

    /** Ce qui reçoit les fragments de la réponse au fil de l'eau. */
    @FunctionalInterface
    public interface Sink {
        void text(String delta);

        /** F-239 / SF-239-05 — la parole relue (ligne {@code compris:} acceptée par la garde), jamais dite. */
        default void understood(String text) {
        }

        /** F-237 / SF-237-08 — un ordre d'écran validé, à relayer au navigateur (événement {@code show}). */
        default void show(JarvisShowCommand command) {
        }

        /** F-252 / SF-252-01 — la préparation est finie (ms depuis l'entrée dans la décision) : le modèle est appelé. */
        default void prepared(long prepMs) {
        }
    }

    /** Raisons d'une délégation d'office ou d'un repli (comptées, jamais dites). */
    public static final String REASON_REFUSAL = "refusal";
    public static final String REASON_ERROR = "error";
    public static final String REASON_TIMEOUT = "timeout";
    public static final String REASON_EMPTY = "empty";
    public static final String REASON_BUDGET = "budget";
    public static final String REASON_QUOTA = "quota";
    /** SF-235-03 : un fait non ancré (D9a), un texte d'outil (D9c), une délégation demandée par le PO (D9d). */
    public static final String REASON_GROUNDING = "grounding";
    public static final String REASON_TOOL_TEXT = "tool_text";
    public static final String REASON_FORCED = "forced";
    /** F-240 / SF-240-06 — une réponse qui avouait ignorer ou offrait une source inexistante : déléguée. */
    public static final String REASON_UNKNOWN = "unknown";
    /** Nombre de réponses précédentes de la session dont les phrases ne doivent pas revenir (D11c). */
    static final int REPEAT_WINDOW = 5;

    /** L'annonce d'une délégation d'office : courte, vraie, jamais un fait. */
    /**
     * L'annonce d'une délégation d'office. F-239 / SF-239-02 (D1, D3) : <b>vide</b> — « Je vérifie, Monsieur. » ne
     * disait rien de la demande ; mieux vaut le silence (le cœur montre le travail) qu'une formule creuse.
     */
    static final String AUTO_ANNOUNCE = "";

    /**
     * F-245 / SF-245-06 — le repli d'une annonce de délégation rejetée (creuse, répétée, non ancrée) : une phrase
     * humaine, sans fait ni libellé ; la seconde quand la première vient d'être dite.
     */
    static final List<String> SAFE_ANNOUNCES = List.of("Entendu, Monsieur, je m'y penche.",
            "Bien, je me renseigne.");

    /**
     * L'annonce d'une délégation demandée par le PO (« vérifie »), à défaut d'objet nommable. F-240 / SF-240-05 :
     * « Je vérifie. » seul était jugé creux ; l'annonce dit désormais ce qui est vérifié ({@link #forcedAnnounce}).
     */
    static final String FORCED_ANNOUNCE = "Je vérifie de nouveau ma dernière réponse, Monsieur.";

    /** F-240 / SF-240-05 — au plus ce nombre de tickets nommés dans l'annonce d'office. */
    static final int FORCED_MAX_TICKETS = 3;

    private static final java.util.regex.Pattern FORCED_CHANTIER = java.util.regex.Pattern
            .compile("(?iu)\\bchantier (\\d{1,4})\\b");

    private final AiAgentProvider provider;
    private final JarvisFastBrainProperties properties;
    private final JarvisUsageService usage;
    private final WorkspaceRepository workspaces;
    private final Clock clock;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
    private ByokKeyService byok;
    private UsageLedgerService ledger;
    private ProviderCostCalculator costs;
    private BigDecimal usdToEur = DEFAULT_USD_TO_EUR;
    private JarvisFastContextBuilder contextBuilder;
    private JarvisFastMemory memory;
    private JarvisDelegationRegistry delegations;
    private JarvisFastExchangeWriter exchanges;
    private JarvisFastPieces fastPieces;
    private fr.claudegateway.jarvis.preferences.JarvisPreferenceService preferences;
    private fr.claudegateway.jarvis.preferences.JarvisSobriety sobriety;
    /** Dernier pré-chauffage par compte (D6) : au plus un toutes les {@link #WARM_EVERY}. */
    private final java.util.Map<UUID, java.time.Instant> warmed = new java.util.concurrent.ConcurrentHashMap<>();
    static final java.time.Duration WARM_EVERY = java.time.Duration.ofMinutes(4);
    static final String WARM_TEXT = "[Pré-chauffage — ne réponds rien]";

    public JarvisFastBrainService(AiAgentProvider provider, JarvisFastBrainProperties properties,
            JarvisUsageService usage, WorkspaceRepository workspaces, Clock clock) {
        this.provider = provider;
        this.properties = properties == null ? JarvisFastBrainProperties.defaults() : properties;
        this.usage = usage;
        this.workspaces = workspaces;
        this.clock = clock;
    }

    /**
     * La clé, le journal et la grille du coût.
     *
     * <p>F-244 / SF-244-02 (D4) : le quota Gateway ne garde plus le cerveau rapide — un quota épuisé ne rend plus
     * Jarvis muet ; son seul garde est le budget de Jarvis. Le paramètre {@code quota} est conservé pour la signature,
     * il n'est plus lu (amende SF-239-11, explicitement).</p>
     */
    @Autowired(required = false)
    public void setAccounting(ByokKeyService byok, UsageLedgerService ledger, ProviderCostCalculator costs,
            QuotaService quota) {
        this.byok = byok;
        this.ledger = ledger;
        this.costs = costs;
    }

    /** F-235 / SF-235-02 — le contexte compact, la mémoire de session, les délégations en cours. */
    @Autowired(required = false)
    public void setPersonality(fr.claudegateway.jarvis.preferences.JarvisPreferenceService preferences,
            fr.claudegateway.jarvis.preferences.JarvisSobriety sobriety) {
        this.preferences = preferences;
        this.sobriety = sobriety;
    }

    /** F-235 / SF-235-09 (D18) — la variante figée de la consigne système selon le réglage d'humour du compte. */
    String systemFor(UUID userId) {
        return JarvisFastBrainPrompt.system(preferences == null ? fr.claudegateway.jarvis.preferences.JarvisHumour
                .DEFAULT : preferences.humour(userId));
    }

    // ---- F-241 / SF-241-03 : le portrait (fin de consigne système, préfixe caché) ----
    private fr.claudegateway.jarvis.portrait.JarvisPortraitService portraits;

    @Autowired(required = false)
    public void setPortraits(fr.claudegateway.jarvis.portrait.JarvisPortraitService portraits) {
        this.portraits = portraits;
    }

    /** F-241 / SF-241-03 — le portrait fictif d'un scénario de banc (jamais le portrait réel), pour le fil appelant. */
    private static final ThreadLocal<fr.claudegateway.jarvis.portrait.JarvisPortraitService.Snapshot> BENCH_PORTRAIT =
            new ThreadLocal<>();

    /** F-241 / SF-241-03 — le banc avec un portrait fictif (ou {@code null}) : même décision, à blanc. */
    public Outcome benchWithPortrait(Request request, String model, String fixedContext,
            fr.claudegateway.jarvis.portrait.JarvisPortraitService.Snapshot portrait) {
        return benchWithPortrait(request, model, fixedContext, portrait, List.of());
    }

    /** F-241 / SF-241-04 — la trace fictive des allusions d'un scénario de banc (« aujourd'hui: » = du jour). */
    private static final ThreadLocal<List<String>> BENCH_RECENT = new ThreadLocal<>();

    /** F-241 / SF-241-04 — le banc avec un portrait fictif et une trace fictive : même décision, à blanc. */
    public Outcome benchWithPortrait(Request request, String model, String fixedContext,
            fr.claudegateway.jarvis.portrait.JarvisPortraitService.Snapshot portrait, List<String> recentAllusions) {
        BENCH_PORTRAIT.set(portrait);
        BENCH_RECENT.set(recentAllusions == null ? List.of() : recentAllusions);
        try {
            return bench(request, model, fixedContext);
        } finally {
            BENCH_PORTRAIT.remove();
            BENCH_RECENT.remove();
        }
    }

    // ---- F-241 / SF-241-04 : la trace des allusions ----
    private fr.claudegateway.jarvis.portrait.JarvisAllusionService allusions;

    @Autowired(required = false)
    public void setAllusions(fr.claudegateway.jarvis.portrait.JarvisAllusionService allusions) {
        this.allusions = allusions;
    }

    /**
     * F-241 / SF-241-03 (D5, D17, D20) — le portrait qui sert à cet échange, ou {@code null} : jamais en présentation,
     * jamais coupé, jamais vide ; au banc, seulement la fixture. Illisible (scellé dormant) ⇒ {@code null}.
     */
    fr.claudegateway.jarvis.portrait.JarvisPortraitService.Snapshot portraitFor(UUID userId, boolean presentation,
            boolean bench) {
        if (presentation) {
            return null;
        }
        if (bench) {
            fr.claudegateway.jarvis.portrait.JarvisPortraitService.Snapshot fixture = BENCH_PORTRAIT.get();
            return fr.claudegateway.jarvis.portrait.JarvisPortraitNote.usable(fixture) ? fixture : null;
        }
        if (portraits == null || userId == null) {
            return null;
        }
        try {
            return portraits.snapshot(userId).filter(fr.claudegateway.jarvis.portrait.JarvisPortraitNote::usable)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.debug("Portrait illisible ({})", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * F-241 / SF-241-03 (D5) — la consigne système : celle d'aujourd'hui, suivie du bloc portrait s'il sert. Sans
     * portrait (ou en présentation), identique à l'octet près à celle d'avant F-241. Le bloc ne dépend que du portrait
     * (version) : il est relu en cache d'un appel à l'autre.
     */
    String systemFor(UUID userId, fr.claudegateway.jarvis.portrait.JarvisPortraitService.Snapshot portrait) {
        return systemFor(systemFor(userId), portrait);
    }

    /** F-252 / SF-252-01 — la même consigne, à partir de la variante d'humour déjà lue. */
    static String systemFor(String base, fr.claudegateway.jarvis.portrait.JarvisPortraitService.Snapshot portrait) {
        return portrait == null ? base
                : base + "\n\n" + fr.claudegateway.jarvis.portrait.JarvisPortraitNote.block(portrait)
                        + fr.claudegateway.jarvis.portrait.JarvisPortraitNote.FAST_MARKS;
    }

    /** F-241 / SF-241-03 — vrai si le portrait sert à cet échange (mesure {@code portrait_on}, jamais le texte). */
    public boolean portraitInUse(UUID userId, boolean presentation) {
        return portraitFor(userId, presentation, false) != null;
    }

    /**
     * F-241 / SF-241-05 (D15) — la relance est permise : due (90 jours), petite conversation (pas de tâche dans la
     * parole), pas la première salutation du jour, aucune délégation en cours, aucune alerte depuis 24 h.
     */
    boolean checkinEligible(Request request, fr.claudegateway.jarvis.portrait.JarvisPortraitService.Snapshot portrait,
            java.time.OffsetDateTime now) {
        if (portrait == null || !fr.claudegateway.jarvis.portrait.JarvisPortraitNote.checkinDue(portrait, now)) {
            return false;
        }
        if (!calmTalk(request.text()) || JarvisFastGuards.forcesDelegation(request.text())) {
            return false;
        }
        if (memory == null || memory.recall(request.userId()).isEmpty()) {
            return false;
        }
        if (delegations != null && !delegations.running(request.userId()).isEmpty()) {
            return false;
        }
        return sobriety == null || !sobriety.alertWithin(request.userId(), java.time.Duration.ofHours(24));
    }

    /**
     * F-241 / SF-241-05 (D15) — une parole de petite conversation calme : une politesse reconnue, ou une phrase courte
     * (8 mots au plus) sans question ni chiffre — aucune tâche dedans.
     */
    static boolean calmTalk(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (JarvisFastGuards.isSmallTalk(text)) {
            return true;
        }
        String t = text.strip();
        return !t.contains("?") && !t.matches(".*\\d.*") && t.split("\\s+").length <= 8;
    }

    /** F-241 / SF-241-03 — les notes volatiles du message : présentation (D17), date qui compte (D3). */
    String portraitNotes(Request request, fr.claudegateway.jarvis.portrait.JarvisPortraitService.Snapshot portrait) {
        if (request.presentation()) {
            return "\n\n" + fr.claudegateway.jarvis.portrait.JarvisPortraitNote.PRESENTATION_NOTE;
        }
        if (portrait == null) {
            return "";
        }
        String date = fr.claudegateway.jarvis.portrait.JarvisPortraitNote.importantDate(portrait,
                java.time.LocalDate.now(clock.withZone(ZONE)));
        return date == null ? "" : "\n\n" + date;
    }

    @Autowired(required = false)
    public void setContext(JarvisFastContextBuilder contextBuilder, JarvisFastMemory memory,
            JarvisDelegationRegistry delegations) {
        this.contextBuilder = contextBuilder;
        this.memory = memory;
        this.delegations = delegations;
    }

    @Autowired(required = false)
    public void setPricing(ProviderPricingProperties pricing) {
        if (pricing != null && pricing.usdToEur() != null) {
            this.usdToEur = pricing.usdToEur();
        }
    }

    /** Vrai si le cerveau rapide est allumé (coupe-circuit D17). */
    public boolean enabled() {
        return properties.isEnabled();
    }

    /** Le modèle rapide configuré. */
    public String model() {
        return properties.model();
    }

    /**
     * Un échange : la parole du PO, le contexte, la décision. Ne lève jamais : toute panne devient une délégation
     * d'office ou un repli.
     */
    public Outcome converse(Request request, Sink sink) {
        return settle(request, decide(request, sink), sink);
    }

    /** F-252 / SF-252-02 (D2) — les effets d'une réponse provisoire, retenus jusqu'à son adoption. */
    private static final ThreadLocal<List<Runnable>> HELD_EFFECTS = new ThreadLocal<>();

    /** Un effet de la réplique : tout de suite, ou à l'adoption si la réponse est provisoire. */
    private static void effect(Runnable action) {
        List<Runnable> held = HELD_EFFECTS.get();
        if (held != null) {
            held.add(action);
        } else {
            action.run();
        }
    }

    /**
     * F-252 / SF-252-02 (correctif A, D1, D2) — <b>une réponse provisoire</b> : même décision, mêmes fragments relayés
     * (l'écran les garde en silence), <b>aucun effet</b> (routage, trace, mémoire, relance du portrait, allusions) tant
     * que {@code verdict} n'a pas rendu « adoptée », avec le texte de référence. Vide : la réponse est jetée.
     */
    public java.util.Optional<Outcome> converseProvisional(Request request, Sink sink,
            java.util.function.Supplier<java.util.Optional<JarvisFastVerdictService.Verdict>> verdict) {
        List<Runnable> held = new ArrayList<>();
        HELD_EFFECTS.set(held);
        Outcome decided;
        try {
            decided = decide(request, sink);
        } finally {
            HELD_EFFECTS.remove();
        }
        java.util.Optional<JarvisFastVerdictService.Verdict> adopted = verdict.get()
                .filter(JarvisFastVerdictService.Verdict::adopted);
        if (adopted.isEmpty()) {
            return java.util.Optional.empty();
        }
        held.forEach(Runnable::run);
        String reference = adopted.get().text();
        Request referenced = reference == null || reference.isBlank() ? request : new Request(request.userId(),
                request.workspace(), request.chantier(), reference, request.heardBeforeCut(), request.screen(),
                request.presentation());
        return java.util.Optional.of(settle(referenced, decided, sink));
    }

    /** Ce qui suit la décision : routage, question du sujet, trace au fil, mémoire, registre (effets d'un échange). */
    private Outcome settle(Request request, Outcome decided, Sink sink) {
        Outcome outcome = offlineInstead(request, route(request, decided), sink);
        if (sink != null && decided.kind() == Kind.DELEGATE && outcome.kind() == Kind.ANSWER
                && !REASON_OFFLINE.equals(outcome.reason())) {
            // F-239 / SF-239-02 — la question du sujet est dite (elle n'était jamais poussée au fil de l'eau).
            sink.text(outcome.spokenText() + " ");
        }
        // SF-235-04 (D12) : un échange rapide est tracé (jamais rejoué comme un tour) — F-245 / SF-245-02 (D2a) : dans le
        // FIL JARVIS, jamais dans le sujet en vue, avec ou sans sujet en vue.
        // F-240 / SF-240-04 : « ⤵ » n'est pas une réponse à tracer (l'écran a lu la réponse d'Opus déjà au fil).
        if (exchanges != null && outcome.kind() == Kind.ANSWER && !isMore(outcome)) {
            exchanges.write(request.userId(), outcome.saidOr(request.text()), outcome.spokenText());
        }
        // SF-235-02 : la mémoire de session (mots seuls) et le registre des délégations en cours.
        if (memory != null && outcome.kind() != Kind.FALLBACK) {
            memory.remember(request.userId(), outcome.saidOr(request.text()), outcome.kind() == Kind.ANSWER ? outcome.spokenText()
                    : outcome.delegation().annonce());
        }
        if (delegations != null && outcome.kind() == Kind.DELEGATE) {
            Delegation d = outcome.delegation();
            String name = request.workspace() != null && request.workspace().getId().equals(d.subjectId())
                    ? request.workspace().getName()
                    : workspaces.findByIdAndUserId(d.subjectId(), request.userId()).map(Workspace::getName).orElse("");
            delegations.started(request.userId(), d.delegationId(), d.subjectId(), name, d.chantier(),
                    d.label() == null || d.label().isBlank() ? d.demande() : d.label());
        }
        return outcome;
    }

    /** F-253 / SF-253-05 — la réponse a été faite côté passerelle, le poste étant éteint. */
    static final String REASON_OFFLINE = "offline";
    /** Appels au modèle au plus : trois lectures, puis la réponse. */
    static final int OFFLINE_MAX_CALLS = 4;
    static final String OFFLINE_FAILED = "Je ne peux pas vous en dire davantage pour le moment, Monsieur.";

    /** F-253 / SF-253-05 — le poste éteint se détecte comme la mesure F-247 ; le modèle est celui du terminal. */
    private fr.claudegateway.jarvis.state.JarvisStateTurnMeter offlineMeter;
    private fr.claudegateway.atelier.AtelierProperties atelierProperties;

    @Autowired(required = false)
    public void setOffline(fr.claudegateway.jarvis.state.JarvisStateTurnMeter meter,
            fr.claudegateway.atelier.AtelierProperties atelier,
            @org.springframework.lang.Nullable fr.claudegateway.jarvis.universe.JarvisUniverseService universe) {
        this.offlineMeter = meter;
        this.atelierProperties = atelier;
        this.offlineUniverse = universe;
    }

    private fr.claudegateway.jarvis.universe.JarvisUniverseService offlineUniverse;

    /** L'univers du compte pour nommer le client ; {@code null} si illisible. */
    private fr.claudegateway.jarvis.universe.JarvisUniverse offlineUniverseOf(UUID userId) {
        try {
            return offlineUniverse == null ? null : offlineUniverse.universe(userId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * F-253 / SF-253-05 (D3) — une délégation vers un poste éteint ne part pas : Jarvis le dit, puis Opus répond côté
     * passerelle en lecture seule, sous le compte de l'appelant. Hors de ce cas, l'issue telle quelle. Après l'adoption
     * d'une réponse provisoire seulement ({@code settle}), jamais au banc.
     */
    Outcome offlineInstead(Request request, Outcome outcome, Sink sink) {
        if (sink == null || outcome.kind() != Kind.DELEGATE || outcome.delegation() == null || offlineMeter == null
                || atelierProperties == null || request.userId() == null) {
            return outcome;
        }
        Workspace target;
        try {
            UUID subject = outcome.delegation().subjectId();
            target = subject == null ? null : workspaces.findByIdAndUserId(subject, request.userId()).orElse(null);
            boolean hosted = subject != null && (target == null || target.getHostId() == null);
            if (hosted || !Boolean.TRUE.equals(offlineMeter.hostOffline(request.userId(), target))) {
                return outcome;
            }
        } catch (RuntimeException e) {
            log.debug("Poste non vérifié ({})", e.getClass().getSimpleName());
            return outcome;
        }
        String notice = offlineNotice(offlineUniverseOf(request.userId()), target);
        sink.text(notice + " ");
        String answer = answerOffline(request, target);
        for (String sentence : JarvisFastGuards.split(answer + " ").sentences()) {
            sink.text(sentence + " ");
        }
        return new Outcome(Kind.ANSWER, notice + " " + answer, null, REASON_OFFLINE, atelierProperties.model(),
                outcome.firstTokenMs(), null, outcome.guards(), outcome.understood()).withShows(outcome.shows());
    }

    /** La phrase dite d'abord, au nom du client quand il est connu. */
    static String offlineNotice(fr.claudegateway.jarvis.universe.JarvisUniverse universe, Workspace target) {
        String name = universe == null || target == null ? null : universe.clients().stream()
                .filter(c -> target.getHostId().equals(c.id())).map(fr.claudegateway.jarvis.universe.JarvisUniverse.Client::name)
                .findFirst().orElse(null);
        return (name == null ? "Le poste est éteint" : "Le poste de " + name + " est éteint")
                + " ; je vous réponds avec ce que je sais.";
    }

    /** La boucle de lecture d'Opus : outils de lecture seule, chacun une fois ; jamais d'exception vers la réplique. */
    private String answerOffline(Request request, Workspace target) {
        try {
            String apiKey = byok == null ? null : byok.resolveActiveApiKey(request.userId()).orElse(null);
            List<AgentMessage> conversation = new ArrayList<>();
            conversation.add(AgentMessage.userText(context(request) + "\n\nParole de Monsieur : " + request.text()));
            java.util.Set<String> read = new java.util.HashSet<>();
            for (int n = 0; n < OFFLINE_MAX_CALLS; n++) {
                boolean last = n == OFFLINE_MAX_CALLS - 1;
                AgentTurn turn = provider.nextTurn(new AgentTurnRequest(atelierProperties.model(),
                        JarvisOfflinePrompt.SYSTEM, List.copyOf(conversation),
                        last ? List.of() : JarvisOfflinePrompt.TOOLS, apiKey, AgentReasoning.off(properties.effort()),
                        AgentContextPolicy.none(), AgentTurnMode.ACT, AgentCallShape.compact(properties.maxTokens())));
                account(request.userId(), target, turn, JARVIS_OPUS);
                if (turn.toolCalls().isEmpty()) {
                    return turn.text() == null || turn.text().isBlank() ? OFFLINE_FAILED : turn.text().strip();
                }
                List<fr.claudegateway.agent.AgentContentBlock> said = new ArrayList<>();
                List<fr.claudegateway.agent.AgentContentBlock> results = new ArrayList<>();
                for (AgentToolCall tc : turn.toolCalls()) {
                    said.add(new fr.claudegateway.agent.AgentContentBlock.ToolUse(tc.id(), tc.name(), tc.input()));
                    String text = read.add(tc.name()) ? readOffline(request, tc) : ALREADY_READ;
                    results.add(new fr.claudegateway.agent.AgentContentBlock.ToolResult(tc.id(), text, false));
                }
                conversation.add(AgentMessage.assistant(said));
                conversation.add(AgentMessage.toolResults(results));
            }
        } catch (RuntimeException e) {
            log.warn("Réponse poste éteint en échec ({})", e.getClass().getSimpleName());
        }
        return OFFLINE_FAILED;
    }

    /** Une lecture de l'outil demandé ; tout autre nom est refusé (la liste est celle de {@link JarvisOfflinePrompt}). */
    private String readOffline(Request request, AgentToolCall tc) {
        if (JarvisFastBrainPrompt.PIECES_TOOL.equals(tc.name())) {
            return readPieces(request, tc.input(), false);
        }
        if (JarvisFastBrainPrompt.AGENDA_TOOL.equals(tc.name())) {
            return readAgenda(request, tc.input(), false);
        }
        if (JarvisFastBrainPrompt.ETAT_TOOL.equals(tc.name())) {
            return readState(request, tc.input(), null);
        }
        if (JarvisFastBrainPrompt.CONVERSATIONS_TOOL.equals(tc.name())) {
            return readConversations(request, tc.input());
        }
        return "Outil inconnu.";
    }

    /** F-235 / SF-235-04 — la question posée quand la délégation n'a pas de sujet cible (D19 : jamais au hasard). */
    static final String ASK_SUBJECT = "Pour quel client ou quel sujet, Monsieur ?";

    /** F-245 / SF-245-02 (D4) — la valeur de {@code sujet} qui désigne le fil Jarvis : une demande qui n'est à personne. */
    static final String JARVIS_SUBJECT = "jarvis";

    /**
     * F-245 / SF-245-02 — la cible provisoire « fil Jarvis » d'une délégation, jusqu'au routage : le banc décide à blanc
     * sans créer le fil ; {@link #route} la remplace par l'identifiant réel du fil du compte. Jamais transmise à l'écran.
     */
    static final UUID JARVIS_THREAD_TARGET = UUID.fromString("00000000-0000-0000-0000-000000000245");

    /** F-245 / SF-245-01/02 — le fil Jarvis du compte. Facultatif (sans lui : comportement d'avant). */
    private fr.claudegateway.jarvis.thread.JarvisThreadService threads;

    @Autowired(required = false)
    public void setThreads(fr.claudegateway.jarvis.thread.JarvisThreadService threads) {
        this.threads = threads;
    }

    /** F-245 / SF-245-03 — la ligne-lien du fil Jarvis. Facultative (sans elle : aucune ligne). */
    private fr.claudegateway.jarvis.thread.JarvisLinkService links;

    @Autowired(required = false)
    public void setLinks(fr.claudegateway.jarvis.thread.JarvisLinkService links) {
        this.links = links;
    }

    /** F-235 / SF-235-04 — l'annonce d'une délégation qui devient une précision du tour vivant de son fil (D10). */
    static final String ADDED_TO_RUNNING = "J'ajoute ça à la recherche en cours";

    /**
     * Le routage de la délégation (D10, D19) : sans sujet cible, Jarvis <b>demande</b> le sujet (jamais de choix au
     * hasard) ; un fil dont le tour vit reçoit une <b>précision</b> et l'annonce le dit ; sinon la demande reformulée
     * attend le tour Opus du fil cible, qui la joindra à son message.
     */
    Outcome route(Request request, Outcome outcome) {
        if (outcome.kind() != Kind.DELEGATE || outcome.delegation() == null) {
            return outcome;
        }
        Delegation d = outcome.delegation();
        if (JARVIS_THREAD_TARGET.equals(d.subjectId())) {
            // F-245 / SF-245-02 (D2b, D4) — une demande générale va au fil Jarvis, sans question.
            UUID thread = jarvisThreadOf(request.userId());
            d = new Delegation(d.delegationId(), d.demande(), d.label(), d.annonce(), thread, null, d.automatic(),
                    d.reason());
        }
        if (d.subjectId() == null) {
            return new Outcome(Kind.ANSWER, ASK_SUBJECT, null, "no_subject", outcome.model(), outcome.firstTokenMs(),
                    outcome.costUsd(), outcome.guards());
        }
        if (delegations == null) {
            return d == outcome.delegation() ? outcome : new Outcome(outcome.kind(), outcome.spokenText(), d,
                    outcome.reason(), outcome.model(), outcome.firstTokenMs(), outcome.costUsd(), outcome.guards(),
                    outcome.understood(), outcome.shows());
        }
        UUID linkId = null;
        if (delegations.live(request.userId(), d.subjectId(), d.chantier())) {
            String label = d.label() == null || d.label().isBlank() ? "" : " : " + d.label().strip()
                    .replaceAll("[.!?…]+$", "");
            d = new Delegation(d.delegationId(), d.demande(), d.label(), ADDED_TO_RUNNING + label + ".",
                    d.subjectId(), d.chantier(), d.automatic(), d.reason());
        } else if (links != null) {
            // F-245 / SF-245-03 (D5) — une délégation vers un sujet ou un chantier laisse une ligne-lien « en cours »
            // dans le fil Jarvis (aucune pour le fil Jarvis lui-même, ni pour une précision d'un tour vivant).
            linkId = links.open(request.userId(), d.subjectId(), d.chantier(),
                    d.label() == null || d.label().isBlank() ? d.demande() : d.label());
        }
        delegations.offer(request.userId(), d.subjectId(), d.chantier(), d.delegationId(), d.demande(), d.label(),
                linkId);
        return new Outcome(outcome.kind(), outcome.spokenText(), d, outcome.reason(), outcome.model(),
                outcome.firstTokenMs(), outcome.costUsd(), outcome.guards());
    }

    /** F-245 / SF-245-02 — l'identifiant du fil Jarvis du compte (créé s'il manque), ou {@code null} sans service. */
    UUID jarvisThreadOf(UUID userId) {
        if (threads == null || userId == null) {
            return null;
        }
        try {
            return threads.open(userId).getId();
        } catch (RuntimeException e) {
            log.debug("Fil Jarvis indisponible ({})", e.getClass().getSimpleName());
            return null;
        }
    }

    /** F-237 / SF-237-08 — l'outil {@code pieces} et le bloc {@code ## Pièces}. */
    @Autowired(required = false)
    public void setPieces(JarvisFastPieces fastPieces) {
        this.fastPieces = fastPieces;
    }

    /** F-253 / SF-253-02 — l'univers du compte, pour vérifier une vue avant de la montrer (facultatif). */
    private fr.claudegateway.jarvis.universe.JarvisUniverseService universeService;

    @Autowired(required = false)
    public void setUniverse(fr.claudegateway.jarvis.universe.JarvisUniverseService universeService) {
        this.universeService = universeService;
    }

    /** F-253 / SF-253-02 — l'univers du compte, ou {@code null} (banc, service absent, lecture en échec). */
    fr.claudegateway.jarvis.universe.JarvisUniverse universeOf(UUID userId, boolean bench) {
        if (bench || universeService == null || userId == null) {
            return null;
        }
        try {
            return universeService.universe(userId);
        } catch (RuntimeException e) {
            log.debug("Univers illisible pour la vue ({})", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * F-237 / SF-237-08, F-253 / SF-253-02 — un ordre d'écran validé, puis, pour une vue de l'univers, vérifié contre
     * l'univers du compte : ce que le modèle reçoit est ce qui va apparaître, ou le refus.
     */
    JarvisShowCommand.Parsed checkShow(JsonNode input, int appuiLeft, UUID userId, boolean bench,
            boolean presentation) {
        JarvisShowCommand.Parsed parsed = JarvisShowCommand.parse(input, appuiLeft);
        if (parsed.command() == null || !"univers".equals(parsed.command().action())) {
            return parsed;
        }
        if (presentation) {
            return new JarvisShowCommand.Parsed(null, "refusé : en présentation, l'univers n'est pas montré", 0, 0);
        }
        JarvisUniverseView.Check check = JarvisUniverseView.check(parsed.command(), universeOf(userId, bench));
        return new JarvisShowCommand.Parsed(check.command(), check.command() == null ? check.result() : null, 0, 0,
                check.command() == null ? null : check.result());
    }

    /** F-235 / SF-235-04 — la trace des échanges rapides dans le fil. */
    @Autowired(required = false)
    public void setExchanges(JarvisFastExchangeWriter exchanges) {
        this.exchanges = exchanges;
    }

    /**
     * Pré-chauffage du cache (D6) : un appel {@code max_tokens: 0} portant la consigne et l'outil, au plus toutes les
     * 4 min par compte. Rend vrai s'il est parti. Ne lève jamais.
     */
    /** Le pré-chauffage en tâche de fond : la requête HTTP n'attend jamais le modèle. */
    public void warmAsync(UUID userId, Workspace workspace) {
        calls.execute(() -> warm(userId, workspace));
    }

    public boolean warm(UUID userId, Workspace workspace) {
        java.time.Instant now = clock.instant();
        java.time.Instant last = warmed.get(userId);
        if (last != null && last.plus(WARM_EVERY).isAfter(now)) {
            return false;
        }
        if (budgetState(userId) == Budget.HARD) {
            return false;
        }
        warmed.put(userId, now);
        try {
            String apiKey = byok == null ? null : byok.resolveActiveApiKey(userId).orElse(null);
            AgentTurn turn = provider.nextTurn(new AgentTurnRequest(properties.model(),
                    systemFor(userId, portraitFor(userId, false, false)), List.of(AgentMessage.userText(WARM_TEXT)), JarvisFastBrainPrompt.tools(), apiKey,
                    AgentReasoning.off(properties.effort()), AgentContextPolicy.none(), AgentTurnMode.ACT,
                    AgentCallShape.compact(0)));
            if (turn != null) {
                account(userId, workspace, turn);
            }
            return true;
        } catch (RuntimeException e) {
            log.debug("Cerveau rapide : pré-chauffage en échec ({})", e.getClass().getSimpleName());
            return true;
        }
    }

    Outcome decide(Request request, Sink sink) {
        return decide(request, sink, null, null);
    }

    /**
     * F-235 / SF-235-08 (D16) — <b>le banc</b> : la même décision, à blanc, sur un modèle candidat ; ni mémoire de
     * session, ni trace dans le fil, ni registre, ni synthèse (rien de tout cela n'est dans {@code decide}) ; le coût
     * de l'appel reste inscrit au journal. Le plafond propre ne s'applique pas (le banc est un geste ADMIN borné à
     * 60 cas) ; le quota du compte, si.
     */
    /** F-239 / SF-239-13 — sonde de banc (tests live seulement) : le texte brut du modèle, avant les gardes. */
    volatile StringBuilder probeRaw;

    /** F-239 / SF-239-13 — la même décision que le banc, avec le texte brut du modèle (sonde des tests live). */
    Outcome decideForProbe(Request request, String model, String fixedContext, StringBuilder raw) {
        probeRaw = raw;
        try {
            return bench(request, model, fixedContext);
        } finally {
            probeRaw = null;
        }
    }

    public Outcome bench(Request request, String model, String fixedContext) {
        return decide(request, null, model, fixedContext);
    }

    /** F-237 / SF-237-13 — la lecture des pièces au banc : le texte fixe du jeu, pour le fil appelant. */
    private static final ThreadLocal<String> BENCH_PIECES_FIXTURE = new ThreadLocal<>();

    /** F-237 / SF-237-13 — le banc des pièces : la même décision à blanc, l'outil {@code pieces} rend {@code pieces}. */
    public Outcome benchWithPieces(Request request, String model, String fixedContext, String pieces) {
        BENCH_PIECES_FIXTURE.set(pieces);
        try {
            return bench(request, model, fixedContext);
        } finally {
            BENCH_PIECES_FIXTURE.remove();
        }
    }

    private Outcome decide(Request request, Sink sink, String benchModel, String fixedContext) {
        Sink out = sink == null ? delta -> { } : sink;
        java.util.concurrent.atomic.AtomicReference<String> understood = new java.util.concurrent.atomic.AtomicReference<>();
        Outcome outcome = decideRaw(request, out, benchModel, fixedContext, understood);
        outcome = outcome.withUnderstood(understood.get());
        if (outcome.kind() == Kind.DELEGATE && outcome.delegation() != null && outcome.delegation().automatic()
                && outcome.understood() != null) {
            // F-239 / SF-239-05 — une délégation d'office porte la parole relue, pas la transcription brute.
            Delegation d = outcome.delegation();
            outcome = new Outcome(outcome.kind(), outcome.spokenText(), new Delegation(d.delegationId(),
                    outcome.understood(), d.label(), d.annonce(), d.subjectId(), d.chantier(), d.automatic(),
                    d.reason()), outcome.reason(), outcome.model(), outcome.firstTokenMs(), outcome.costUsd(),
                    outcome.guards(), outcome.understood(), outcome.shows());
        }
        outcome = JarvisFastGuards.isSmallTalk(request.text()) ? courteous(request, outcome, out) : outcome;
        outcome = acknowledged(request, outcome, out, fixedContext);
        outcome = moreWithoutDelegation(request, outcome, out, fixedContext);
        return rememberWithoutDelegation(request, outcome, out, benchModel != null);
    }

    /** F-241 / SF-241-05 — la raison (comptée, jamais dite) d'un « retiens / oublie » tenu par la garde. */
    static final String REASON_REMEMBER = "remember";

    /**
     * F-241 / SF-241-05 (D7, D8) — <b>retenir ou oublier ne délègue jamais</b> : « retiens que X » donne la ligne
     * 📌 noter (l'écran enregistre) ; « oublie que X » donne 📌 oublier du souvenir visé. Si le modèle a déjà posé la
     * bonne marque, rien ne change ; s'il a délégué ou oublié la marque, la garde la pose. En présentation : « Nous en
     * parlerons en privé », aucune marque.
     */
    Outcome rememberWithoutDelegation(Request request, Outcome outcome, Sink out, boolean bench) {
        String fact = fr.claudegateway.jarvis.portrait.JarvisPortraitNote.rememberedFact(request.text());
        String target = fact == null ? fr.claudegateway.jarvis.portrait.JarvisPortraitNote.forgetTarget(request.text()) : null;
        if (fact == null && target == null || outcome.kind() == Kind.FALLBACK) {
            return outcome;
        }
        Guards guards = outcome.guards() == null ? Guards.NONE : outcome.guards();
        String already = outcome.kind() == Kind.ANSWER && outcome.spokenText() != null
                ? outcome.spokenText().strip() : "";
        if (request.presentation()) {
            if (already.contains("privé")) {
                return outcome;
            }
            out.text(fr.claudegateway.jarvis.portrait.JarvisPortraitNote.PRIVATE + " ");
            return new Outcome(Kind.ANSWER, fr.claudegateway.jarvis.portrait.JarvisPortraitNote.PRIVATE, null, REASON_REMEMBER,
                    outcome.model(), outcome.firstTokenMs(), outcome.costUsd(), guards.withPins(List.of()),
                    outcome.understood(), outcome.shows());
        }
        String action = fact != null ? "noter" : "oublier";
        if (outcome.kind() == Kind.ANSWER && guards.pins().stream().anyMatch(p -> action.equals(p.action()))) {
            return outcome;
        }
        List<fr.claudegateway.jarvis.portrait.JarvisPortraitNote.Pin> pins = new ArrayList<>(guards.pins().stream()
                .filter(p -> !"retenir".equals(p.action())).toList());
        String said;
        if (fact != null) {
            fr.claudegateway.jarvis.portrait.JarvisPortraitNote.Pin pin = fr.claudegateway.jarvis.portrait.JarvisPortraitNote.pin("\uD83D\uDCCC noter: " + fact);
            if (pin != null) {
                pins.add(pin);
            }
            said = "C'est noté, Monsieur.";
        } else {
            fr.claudegateway.jarvis.portrait.JarvisPortraitService.Souvenir target0 = fr.claudegateway.jarvis.portrait.JarvisPortraitNote.souvenirFor(target,
                    portraitFor(request.userId(), false, bench));
            if (target0 == null) {
                said = fr.claudegateway.jarvis.portrait.JarvisPortraitNote.NOTHING_TO_FORGET;
            } else {
                pins.add(new fr.claudegateway.jarvis.portrait.JarvisPortraitNote.Pin("oublier", null, target0.number()));
                said = fr.claudegateway.jarvis.portrait.JarvisPortraitNote.FORGOTTEN;
            }
        }
        if (already.isEmpty()) {
            out.text(said + " ");
        } else {
            said = already;
        }
        return new Outcome(Kind.ANSWER, said, null, REASON_REMEMBER, outcome.model(), outcome.firstTokenMs(),
                outcome.costUsd(), guards.withPins(pins), outcome.understood(), outcome.shows());
    }

    /** F-240 / SF-240-04 — la raison (comptée, jamais dite) d'une demande de suite lue sur l'appareil. */
    static final String REASON_MORE = "more";

    /**
     * F-240 / SF-240-04 (D8) — <b>une demande de développement ne délègue jamais</b> quand le fil porte une réponse
     * d'Opus : la décision (outil appelé, délégation d'office) devient la ligne-marque « ⤵ » et l'écran lit lui-même
     * la suite de la réponse déjà calculée — aucun appel à Opus. « Vérifie » n'en est pas une (délégation forcée).
     */
    Outcome moreWithoutDelegation(Request request, Outcome outcome, Sink out, String fixedContext) {
        boolean markWritten = outcome.spokenText() != null
                && outcome.spokenText().contains(JarvisFastBrainPrompt.MORE_MARK);
        if (outcome.kind() != Kind.DELEGATE || JarvisFastGuards.forcesDelegation(request.text())
                || !(JarvisFastGuards.isMoreRequest(request.text()) || markWritten)) {
            return outcome;
        }
        String context;
        try {
            context = fixedContext != null ? fixedContext : context(request);
        } catch (RuntimeException e) {
            return outcome;
        }
        if (!JarvisFastGuards.hasOpusAnswer(context)) {
            return outcome;
        }
        String said = outcome.spokenText() == null ? "" : outcome.spokenText().strip();
        if (!said.contains(JarvisFastBrainPrompt.MORE_MARK)) {
            out.text(JarvisFastBrainPrompt.MORE_MARK + " ");
        }
        return new Outcome(Kind.ANSWER, JarvisFastBrainPrompt.MORE_MARK, null, REASON_MORE, outcome.model(),
                outcome.firstTokenMs(), outcome.costUsd(), outcome.guards(), outcome.understood(), outcome.shows());
    }

    /** F-240 / SF-240-04 — la réponse rapide est la ligne-marque « ⤵ » (l'écran lit la suite, rien n'est dit). */
    static boolean isMore(Outcome outcome) {
        return outcome.kind() == Kind.ANSWER && outcome.spokenText() != null
                && outcome.spokenText().strip().startsWith(JarvisFastBrainPrompt.MORE_MARK);
    }

    /**
     * F-239 / SF-239-02 (D3) — <b>une salutation ou une politesse ne se délègue jamais</b> : si la décision est une
     * délégation (outil appelé, échec d'ancrage, refus, délai, erreur, réponse vide), le cerveau rapide répond quand
     * même — ce qu'il a déjà dit de sûr, sinon une réponse courtoise fixe, dite maintenant. Un repli (plafond, quota)
     * reste un repli.
     */
    Outcome courteous(Request request, Outcome outcome, Sink out) {
        if (outcome.kind() != Kind.DELEGATE) {
            return outcome;
        }
        String said = outcome.spokenText() == null ? "" : outcome.spokenText().strip();
        if (said.isEmpty()) {
            said = JarvisFastGuards.courtesyReply(request.text());
            out.text(said + " ");
        }
        return new Outcome(Kind.ANSWER, said, null, null, outcome.model(), outcome.firstTokenMs(), outcome.costUsd(),
                outcome.guards(), outcome.understood());
    }

    /**
     * F-243 / SF-243-06 — <b>un accusé de réception ne se délègue jamais</b> (« C'est noté. », « Je vois. ») : une
     * délégation (outil appelé, délégation d'office sur une réponse vidée par la porte) devient une réponse — ce que
     * le modèle a déjà dit de sûr, sinon {@link JarvisFastGuards#ACK_REPLY}. Le contexte n'est lu que pour un accusé
     * qui pourrait valoir « oui » après une proposition (« entendu »).
     */
    Outcome acknowledged(Request request, Outcome outcome, Sink out, String fixedContext) {
        if (outcome.kind() != Kind.DELEGATE || JarvisFastGuards.isSmallTalk(request.text())
                || JarvisFastGuards.forcesDelegation(request.text())
                || !JarvisFastGuards.isAcknowledgement(request.text(), null)) {
            return outcome; // sans contexte, la liste la plus large : le contexte n'est lu que pour un candidat
        }
        String context;
        try {
            context = fixedContext != null ? fixedContext : context(request);
        } catch (RuntimeException e) {
            context = null;
        }
        if (!JarvisFastGuards.isAcknowledgement(request.text(), context)) {
            return outcome;
        }
        String said = outcome.spokenText() == null ? "" : outcome.spokenText().strip();
        if (said.isEmpty()) {
            said = JarvisFastGuards.ACK_REPLY;
            out.text(said + " ");
        }
        return new Outcome(Kind.ANSWER, said, null, null, outcome.model(), outcome.firstTokenMs(), outcome.costUsd(),
                outcome.guards(), outcome.understood());
    }

    private Outcome decideRaw(Request request, Sink out, String benchModel, String fixedContext,
            java.util.concurrent.atomic.AtomicReference<String> understood) {
        UUID userId = request.userId();
        boolean bench = benchModel != null;
        // D9d : « vérifie », « demande à Opus », « c'est faux »… ⇒ Opus, sans appel au cerveau rapide.
        if (JarvisFastGuards.forcesDelegation(request.text())) {
            // F-240 / SF-240-04 — au banc (à blanc), la mémoire de session n'est pas lue.
            return forced(request, !bench, fixedContext);
        }
        // F-239 / SF-239-11 — jamais d'escalade de coût : au-delà du budget, le cerveau rapide CONTINUE (10 à 50 fois
        // moins cher qu'Opus) ; au plafond dur, une réponse fixe, aucun appel. F-244 / SF-244-02 (D4) : le quota Gateway
        // ne garde plus Jarvis (un quota épuisé ne le rend plus muet) ; seul son budget propre le garde.
        // F-252 / SF-252-01 (B) — hors banc, les lectures indépendantes partent ensemble ; consommées dans l'ordre d'avant.
        Prepared prepared = bench ? null : prepare(request);
        Budget budget = bench ? Budget.NONE : prepared.budget.get();
        if (budget == Budget.HARD) {
            prepared.prep.cancelUnused();
            return fixed(out, BUDGET_HARD_REPLY, REASON_BUDGET_HARD);
        }
        // F-244 / SF-244-03 (D6) — le plafond global de Jarvis à 2 × : une phrase fixe, aucun appel. Au-delà de 100 %
        // (souple), le cerveau rapide CONTINUE : c'est la délégation qui demande un « oui », jamais une escalade.
        if (!bench && prepared.hardStop.get()) {
            prepared.prep.cancelUnused();
            return fixed(out, fr.claudegateway.jarvis.budget.JarvisBudgetService.HARD_REPLY, REASON_BUDGET_HARD);
        }
        Outcome decided;
        try {
            decided = decideCall(request, out, benchModel, fixedContext, understood, prepared);
        } finally {
            if (prepared != null) {
                prepared.prep.cancelUnused();
            }
        }
        if (budget == Budget.SOFT && decided.kind() == Kind.ANSWER && decided.reason() == null) {
            return new Outcome(decided.kind(), decided.spokenText(), decided.delegation(), REASON_BUDGET_SOFT,
                    decided.model(), decided.firstTokenMs(), decided.costUsd(), decided.guards());
        }
        return decided;
    }

    /** F-239 / SF-239-11 — l'état du budget mensuel du cerveau rapide. */
    enum Budget { NONE, SOFT, HARD }

    /** F-252 / SF-252-01 — le contexte lu, et les lignes du bloc « État connu » qu'il a lues (garde de fraîcheur). */
    record ContextRead(String text, List<fr.claudegateway.jarvis.state.JarvisStateBlock.Line> stateLines) {
    }

    /**
     * F-252 / SF-252-01 (B) — <b>les lectures d'une réplique, parties ensemble</b> : chacune est la même méthode
     * qu'avant, consommée au même endroit et dans le même ordre ; seul le temps d'attente change.
     */
    static final class Prepared {
        final JarvisFastPrep prep;
        final JarvisFastPrep.Early<Budget> budget;
        final JarvisFastPrep.Early<Boolean> hardStop;
        final JarvisFastPrep.Early<ContextRead> context;
        final JarvisFastPrep.Early<String> pieces;
        final JarvisFastPrep.Early<String> apiKey;
        final JarvisFastPrep.Early<String> reference;
        final JarvisFastPrep.Early<fr.claudegateway.jarvis.portrait.JarvisPortraitService.Snapshot> portrait;
        final JarvisFastPrep.Early<String> system;
        final JarvisFastPrep.Early<Boolean> soberRead;

        private Prepared(JarvisFastBrainService s, Request request) {
            UUID userId = request.userId();
            prep = new JarvisFastPrep(s.calls, s.parallelPrep);
            budget = prep.early(() -> s.budgetState(userId), () -> s.budgetState(userId));
            hardStop = prep.early(() -> s.jarvisBudget != null && s.jarvisBudget.hardStop(userId),
                    () -> s.jarvisBudget != null && s.jarvisBudget.hardStop(userId));
            context = prep.early(() -> s.contextRead(request), () -> s.contextRead(request));
            pieces = prep.early(() -> s.screenAndPieces(request), () -> s.screenAndPieces(request));
            apiKey = prep.early(() -> s.apiKeyOf(userId), () -> s.apiKeyOf(userId));
            reference = prep.early(() -> s.reference(request), () -> s.reference(request));
            portrait = prep.early(() -> s.portraitFor(userId, request.presentation(), false),
                    () -> s.portraitFor(userId, request.presentation(), false));
            system = prep.early(() -> s.systemFor(userId), () -> s.systemFor(userId));
            soberRead = prep.early(() -> s.sobriety != null && s.sobriety.sober(userId, request.text()),
                    () -> s.sobriety != null && s.sobriety.sober(userId, request.text()));
        }
    }

    /** F-252 / SF-252-01 — coupe-circuit de la préparation parallèle (faux : en série, comme avant). */
    private boolean parallelPrep = true;

    @Autowired(required = false)
    public void setParallelPrep(
            @org.springframework.beans.factory.annotation.Value("${app.jarvis.fast-brain.parallel-prep:true}")
            boolean parallelPrep) {
        this.parallelPrep = parallelPrep;
    }

    /** F-252 / SF-252-01 — lance les lectures de la réplique (hors banc). */
    Prepared prepare(Request request) {
        return new Prepared(this, request);
    }

    /** F-252 / SF-252-01 — le contexte et les lignes d'état qu'il a lues, sur le fil qui le lit. */
    ContextRead contextRead(Request request) {
        String text = context(request);
        List<fr.claudegateway.jarvis.state.JarvisStateBlock.Line> lines = STATE_LINES.get();
        STATE_LINES.remove();
        return new ContextRead(text, lines);
    }

    /** La clé du compte (BYOK) ou {@code null}. */
    String apiKeyOf(UUID userId) {
        return byok == null ? null : byok.resolveActiveApiKey(userId).orElse(null);
    }

    static final String REASON_BUDGET_SOFT = "budget_soft";
    static final String REASON_BUDGET_HARD = "budget_hard";
    static final String BUDGET_HARD_REPLY = "Monsieur, le plafond mensuel de la conversation est atteint.";
    static final String QUOTA_REPLY = "Le quota de votre compte est atteint, Monsieur.";

    /** Une réponse fixe, dite tout de suite, sans aucun appel au modèle. */
    private Outcome fixed(Sink out, String reply, String reason) {
        out.text(reply + " ");
        return new Outcome(Kind.ANSWER, reply, null, reason, properties.model(), 0L, null);
    }

    /** NONE sous le budget, SOFT au-delà, HARD à 2 × le budget (ou budget nul). Illisible : NONE (le quota garde). */
    Budget budgetState(UUID userId) {
        BigDecimal budgetEur = properties.monthlyBudgetEur();
        if (budgetEur.signum() == 0) {
            return Budget.HARD;
        }
        try {
            BigDecimal spentEur = usage.monthFastBrainCostUsd(userId).multiply(usdToEur);
            if (spentEur.compareTo(budgetEur.multiply(BigDecimal.valueOf(2))) >= 0) {
                return Budget.HARD;
            }
            return spentEur.compareTo(budgetEur) >= 0 ? Budget.SOFT : Budget.NONE;
        } catch (RuntimeException e) {
            return Budget.NONE;
        }
    }

    /** F-237 / SF-237-08 — au plus autant d'appels au modèle par parole (lecture des pièces, phrase après l'écran). */
    static final int MAX_CALLS = 3;
    /** F-237 / SF-237-08 — la phrase dite quand l'écran a montré sans que le modèle ait rien dit. */
    static final String SHOWN_REPLY = "Voici.";
    /** F-237 / SF-237-08 — la réponse à une deuxième lecture des pièces dans la même réponse. */
    static final String ALREADY_READ = "Lecture déjà faite pour cette réponse.";

    /** Un appel au modèle : le tour, le délai du premier jeton, ou la raison de l'échec. */
    private record Called(AgentTurn turn, long firstMs, String failure) {
    }

    /** F-237 / SF-237-15 — la raison d'une réponse fixée par la garde « preuve sans oui » (comptée, jamais dite). */
    public static final String REASON_PROOF_GUARD = "proof_guard";

    private Outcome decideCall(Request request, Sink out, String benchModel, String fixedContext,
            java.util.concurrent.atomic.AtomicReference<String> understood, Prepared prepared) {
        // F-239 / SF-239-08 — l'état du jour (à blanc, le banc ne lit pas la mémoire : « première salutation »).
        // F-237 / SF-237-08 — puis l'écran (« ## Écran ») et les pièces du sujet (« ## Pièces ») : volatils, dans le
        // message, jamais dans la consigne.
        String context = fixedContext != null ? fixedContext : contextOf(request, prepared) + "\n\n"
                + (benchModel != null ? JarvisFastContextBuilder.dayState(true, false) : dayState(request))
                + (prepared != null ? prepared.pieces.get() : screenAndPieces(request));
        // F-237 / SF-237-15 — un doute sur un chantier sans aucune preuve : la proposition, sans appel au modèle.
        JarvisProofGuard.Decision before = JarvisProofGuard.before(request.text(), context, request.chantier());
        if (before != null) {
            out.text(before.said() + " ");
            return new Outcome(Kind.ANSWER, before.said(), null, REASON_PROOF_GUARD, properties.model(), 0L, null);
        }
        return withoutProofDelegation(request, out, context,
                callModel(request, out, benchModel, context, understood, prepared));
    }

    /**
     * F-252 / SF-252-01 — le contexte de la réplique : lu en avance s'il l'a été (les lignes d'état qu'il a lues
     * reviennent sur ce fil, pour la garde de fraîcheur), sinon lu ici comme avant.
     */
    private String contextOf(Request request, Prepared prepared) {
        if (prepared == null) {
            return context(request);
        }
        ContextRead read = prepared.context.get();
        if (read.stateLines() == null) {
            STATE_LINES.remove();
        } else {
            STATE_LINES.set(read.stateLines());
        }
        return read.text();
    }

    /**
     * F-237 / SF-237-15 — <b>le filet « preuve sans oui »</b> : une parole de doute ne part jamais à Opus, ni par le
     * modèle ni d'office. Preuves existantes ⇒ la galerie des preuves ; aucune ⇒ la proposition ; inconnu ⇒
     * « Voulez-vous que je vérifie ? ». Le « oui » suivant n'est pas un doute : sa délégation part.
     */
    Outcome withoutProofDelegation(Request request, Sink out, String context, Outcome outcome) {
        if (outcome.kind() != Kind.DELEGATE) {
            return outcome;
        }
        JarvisProofGuard.Decision instead = JarvisProofGuard.instead(request.text(), context, request.chantier(),
                outcome.delegation() == null ? null : outcome.delegation().chantier());
        if (instead == null) {
            return outcome;
        }
        List<JarvisShowCommand> shows = new ArrayList<>(outcome.shows());
        if (instead.show() != null && JarvisPiecesBench.proactive(shows) < JarvisShowCommand.MAX_APPUI) {
            out.show(instead.show());
            shows.add(instead.show());
        }
        out.text(instead.said() + " ");
        String already = outcome.spokenText() == null ? "" : outcome.spokenText().strip();
        String said = already.isEmpty() ? instead.said() : already + " " + instead.said();
        return new Outcome(Kind.ANSWER, said, null, REASON_PROOF_GUARD, outcome.model(), outcome.firstTokenMs(),
                outcome.costUsd(), outcome.guards(), outcome.understood(), shows);
    }

    private Outcome callModel(Request request, Sink out, String benchModel, String context,
            java.util.concurrent.atomic.AtomicReference<String> understood, Prepared prepared) {
        UUID userId = request.userId();
        boolean bench = benchModel != null;
        String model = bench ? benchModel : properties.model();
        String apiKey = prepared != null ? prepared.apiKey.get() : apiKeyOf(userId);
        // F-239 / SF-239-11 — l'univers de référence (préfixe relu du cache) ; les gardes voient tout le contexte.
        String reference = bench ? "" : prepared != null ? prepared.reference.get() : reference(request);
        String grounding = reference.isEmpty() ? context : reference + "\n\n" + context;
        // F-241 / SF-241-03 — le portrait : il ancre ce que Jarvis en dit, et ses interdits ferment la porte (D12 a).
        fr.claudegateway.jarvis.portrait.JarvisPortraitService.Snapshot portrait = prepared != null
                ? prepared.portrait.get() : portraitFor(userId, request.presentation(), bench);
        if (portrait != null) {
            grounding = grounding + "\n\n" + fr.claudegateway.jarvis.portrait.JarvisPortraitNote.block(portrait);
        }
        SentenceGate gate = new SentenceGate(grounding, request.text(), bench ? List.of() : recentSentences(userId),
                out, JarvisFastGuards.isSmallTalk(request.text()));
        gate.forbid(portrait == null ? java.util.Set.of()
                : fr.claudegateway.jarvis.portrait.JarvisPortraitNote.forbiddenTerms(portrait));
        // F-247 / SF-247-10 (D8) — les faits du bloc lu : une phrase qui en cite un vieux de plus d'1 h est datée.
        List<fr.claudegateway.jarvis.state.JarvisStateBlock.Line> stateLines = STATE_LINES.get();
        STATE_LINES.remove();
        if (stateLines != null && !bench) {
            gate.stale(stateLines, java.time.OffsetDateTime.now(clock));
        }
        // Au banc, la date qui compte vient du contexte figé du scénario (jamais de l'horloge réelle).
        String notes = portraitNotes(request, bench ? null : portrait);
        // F-241 / SF-241-04 (D11, D12 b/c) — la trace des allusions : volatile, dans le message ; et les filets.
        if (portrait != null) {
            java.time.OffsetDateTime now = java.time.OffsetDateTime.now(clock);
            List<String> benchRecent = bench && BENCH_RECENT.get() != null ? BENCH_RECENT.get() : List.of();
            List<fr.claudegateway.jarvis.portrait.JarvisAllusionService.Entry> recent = bench || allusions == null ? List.of()
                    : allusions.recent(userId); // F-252 : lue seulement si le portrait sert (jamais en avance)
            String trace = bench ? fr.claudegateway.jarvis.portrait.JarvisAllusionService.benchNote(benchRecent)
                    : fr.claudegateway.jarvis.portrait.JarvisAllusionService.note(recent, now);
            if (trace != null) {
                notes = notes + "\n\n" + trace;
            }
            boolean spontaneousToday = bench
                    ? benchRecent.stream().anyMatch(r -> r.toLowerCase(java.util.Locale.ROOT).startsWith("aujourd'hui:"))
                    : fr.claudegateway.jarvis.portrait.JarvisAllusionService.spontaneousToday(recent, now);
            List<String> topics = bench
                    ? benchRecent.stream().map(r -> fr.claudegateway.jarvis.portrait.JarvisPortraitNote.normalize(
                            r.replaceFirst("(?i)^aujourd'hui:", ""))).toList()
                    : fr.claudegateway.jarvis.portrait.JarvisAllusionService.topicsWithin(recent, now);
            gate.moment(fr.claudegateway.jarvis.portrait.JarvisPortraitNote.momentStems(portrait));
            gate.allusions(fr.claudegateway.jarvis.portrait.JarvisPortraitNote.allusionTerms(portrait),
                    fr.claudegateway.jarvis.portrait.JarvisPortraitNote.personalSaid(request.text(), portrait), spontaneousToday, topics);
            // F-241 / SF-241-05 (D15) — la relance « Du nouveau ? » : cadence bornée ici, moment choisi par Jarvis.
            // F-240 / SF-240-07 — pas de relance quand une question de politesse a déjà été posée.
            if (!bench && !JarvisFastGuards.courtesyAlreadyAsked(context) && checkinEligible(request, portrait, now)) {
                notes = notes + "\n\n" + fr.claudegateway.jarvis.portrait.JarvisPortraitNote.CHECKIN_NOTE;
            }
        }
        // F-241 / SF-241-05 (D17) — en présentation, aucune ligne 📌 n'est relayée.
        gate.pinsAllowed(!request.presentation());
        List<AgentMessage> conversation = new ArrayList<>(bench
                ? List.of(AgentMessage.userText(message(context, request.text())
                        + (sober(request, context) ? "\n\n" + fr.claudegateway.jarvis.preferences.JarvisSobriety.NOTE
                                : "") + notes))
                : messages(request, context, reference, notes, prepared == null ? null : prepared.soberRead));
        String system = prepared != null ? systemFor(prepared.system.get(), portrait) : systemFor(userId, portrait);
        // F-252 / SF-252-01 — la préparation est finie : le premier appel au modèle part maintenant.
        if (prepared != null) {
            out.prepared(prepared.prep.elapsedMs());
        }

        AgentTurn turn = null;
        long firstMs = -1L;
        boolean read = false;
        boolean agendaRead = false;
        boolean stateRead = false;
        boolean conversationsRead = false;
        int appuiLeft = JarvisShowCommand.MAX_APPUI;
        List<JarvisShowCommand> shown = new ArrayList<>();
        for (int n = 0; n < MAX_CALLS; n++) {
            AgentTurnRequest call = new AgentTurnRequest(model, system, List.copyOf(conversation),
                    JarvisFastBrainPrompt.tools(), apiKey, AgentReasoning.off(properties.effort()),
                    AgentContextPolicy.none(), AgentTurnMode.ACT, AgentCallShape.compact(properties.maxTokens()));
            Called called = call(call, gate, userId, request.workspace());
            if (n == 0) {
                firstMs = called.firstMs();
            }
            if (called.failure() != null) {
                return auto(request, called.failure(), firstMs, gate.guards()).withShows(shown);
            }
            turn = called.turn();
            if (gate.abortReason() != null) {
                // F-237 / SF-237-15 — une légende non ancrée (retirée, jamais dite) ne jette pas l'écran du même tour.
                if (REASON_GROUNDING.equals(gate.abortReason()) && turn.toolCalls().stream()
                        .noneMatch(tc -> JarvisFastBrainPrompt.TOOL_NAME.equals(tc.name()))) {
                    for (AgentToolCall tc : turn.toolCalls()) {
                        if (JarvisFastBrainPrompt.SHOW_TOOL.equals(tc.name())) {
                            JarvisShowCommand.Parsed parsed = checkShow(tc.input(), appuiLeft, userId, bench, request.presentation());
                            if (parsed.command() != null) {
                                out.show(parsed.command());
                                shown.add(parsed.command());
                                appuiLeft -= parsed.appuiUsed();
                            }
                        }
                    }
                }
                break;
            }
            // F-237 / SF-237-08 — la boucle d'outil : l'écran part tout de suite, la lecture des pièces relance.
            List<fr.claudegateway.agent.AgentContentBlock> results = new ArrayList<>();
            boolean delegates = false;
            boolean freshRead = false;
            for (AgentToolCall tc : turn.toolCalls()) {
                if (JarvisFastBrainPrompt.TOOL_NAME.equals(tc.name())) {
                    delegates = true;
                } else if (JarvisFastBrainPrompt.SHOW_TOOL.equals(tc.name())) {
                    JarvisShowCommand.Parsed parsed = checkShow(tc.input(), appuiLeft, userId, bench, request.presentation());
                    if (parsed.command() != null) {
                        out.show(parsed.command());
                        shown.add(parsed.command());
                        appuiLeft -= parsed.appuiUsed();
                    }
                    results.add(new fr.claudegateway.agent.AgentContentBlock.ToolResult(tc.id(), parsed.toolResult(),
                            parsed.command() == null));
                } else if (JarvisFastBrainPrompt.PIECES_TOOL.equals(tc.name())) {
                    String text = ALREADY_READ;
                    if (!read) {
                        read = true;
                        freshRead = true;
                        text = readPieces(request, tc.input(), bench);
                        gate.ground(text);
                    }
                    results.add(new fr.claudegateway.agent.AgentContentBlock.ToolResult(tc.id(), text, false));
                } else if (JarvisFastBrainPrompt.AGENDA_TOOL.equals(tc.name())) {
                    // F-243 / SF-243-02 (D9, D11) — lecture seule du cache des agendas, une fois par réponse.
                    String text = ALREADY_READ;
                    if (!agendaRead) {
                        agendaRead = true;
                        freshRead = true;
                        text = readAgenda(request, tc.input(), bench);
                        gate.ground(text);
                        JarvisShowCommand view = bench ? null : agendaView(request, tc.input(), text);
                        if (view != null) {
                            out.show(view);
                            shown.add(view);
                        }
                    }
                    results.add(new fr.claudegateway.agent.AgentContentBlock.ToolResult(tc.id(), text, false));
                } else if (JarvisFastBrainPrompt.ETAT_TOOL.equals(tc.name())) {
                    // F-247 / SF-247-10 — lecture seule du registre d'état, une fois par réponse, ancrée.
                    String text = ALREADY_READ;
                    if (!stateRead) {
                        stateRead = true;
                        freshRead = true;
                        text = readState(request, tc.input(), gate);
                        gate.ground(text);
                    }
                    results.add(new fr.claudegateway.agent.AgentContentBlock.ToolResult(tc.id(), text, false));
                } else if (JarvisFastBrainPrompt.CONVERSATIONS_TOOL.equals(tc.name())) {
                    // F-247 / SF-247-13 — lecture seule des conversations capturées, une fois par réponse, ancrée.
                    String text = ALREADY_READ;
                    if (!conversationsRead) {
                        conversationsRead = true;
                        freshRead = true;
                        text = readConversations(request, tc.input());
                        gate.ground(text);
                    }
                    results.add(new fr.claudegateway.agent.AgentContentBlock.ToolResult(tc.id(), text, false));
                } else {
                    results.add(new fr.claudegateway.agent.AgentContentBlock.ToolResult(tc.id(), "Outil inconnu.",
                            true));
                }
            }
            boolean needPhrase = !shown.isEmpty() && gate.spoken().isEmpty();
            if (delegates || results.isEmpty() || !(freshRead || needPhrase)) {
                break;
            }
            List<fr.claudegateway.agent.AgentContentBlock> said = new ArrayList<>();
            if (turn.text() != null && !turn.text().isBlank()) {
                said.add(new fr.claudegateway.agent.AgentContentBlock.Text(turn.text()));
            }
            for (AgentToolCall tc : turn.toolCalls()) {
                said.add(new fr.claudegateway.agent.AgentContentBlock.ToolUse(tc.id(), tc.name(), tc.input()));
            }
            conversation.add(AgentMessage.assistant(said));
            conversation.add(AgentMessage.toolResults(results));
            gate.nextCall();
        }
        understood.set(gate.understood());
        // F-241 / SF-241-05 (D15) — la relance a été posée : la suivante attend 90 jours.
        if (!bench && portrait != null && portraits != null && gate.abortReason() == null
                && fr.claudegateway.jarvis.portrait.JarvisPortraitNote.isCheckin(gate.spoken())) {
            effect(() -> {
                try {
                    portraits.markCheckin(userId);
                } catch (RuntimeException e) {
                    log.debug("Relance du portrait non datée ({})", e.getClass().getSimpleName());
                }
            });
        }
        // F-241 / SF-241-04 (D11) — chaque allusion dite est journalisée, déclarée (✦) ou rattrapée ; pas au banc.
        if (!bench && portrait != null && allusions != null && gate.abortReason() == null) {
            effect(() -> gate.journal((kind, topic, phrase, spontaneous) -> allusions.record(userId, kind, topic, phrase,
                    spontaneous)));
        }
        if (gate.abortReason() != null) {
            if (REASON_GROUNDING.equals(gate.abortReason()) && !shown.isEmpty() && turn.toolCalls().stream()
                    .noneMatch(tc -> JarvisFastBrainPrompt.TOOL_NAME.equals(tc.name()))) {
                // F-237 / SF-237-15 — l'écran a montré : la phrase non ancrée est retirée, pas d'escalade vers Opus.
                String text = gate.spoken();
                if (text.isEmpty()) {
                    out.text(SHOWN_REPLY + " ");
                    text = SHOWN_REPLY;
                }
                return new Outcome(Kind.ANSWER, text, null, null, properties.model(), firstMs, null, gate.guards())
                        .withShows(shown);
            }
            return auto(request, gate.abortReason(), firstMs, gate.guards()).withShows(shown);
        }
        Optional<AgentToolCall> delegate = turn.toolCalls().stream()
                .filter(tc -> JarvisFastBrainPrompt.TOOL_NAME.equals(tc.name())).findFirst();
        if (delegate.isPresent()) {
            Delegation d = withSafeAnnounce(delegation(request, delegate.get().input()), gate);
            return new Outcome(Kind.DELEGATE, gate.spoken(), d, null, properties.model(), firstMs, null,
                    gate.guards()).withShows(shown);
        }
        String text = gate.spoken();
        if (text.isEmpty() && !shown.isEmpty()) {
            out.text(SHOWN_REPLY + " ");
            text = SHOWN_REPLY;
        }
        if (text.isEmpty() || (turn.truncated() && shown.isEmpty())) {
            return autoEmpty(request, firstMs, gate.guards(), turn).withShows(shown);
        }
        return new Outcome(Kind.ANSWER, text, null, null, properties.model(), firstMs, null, gate.guards())
                .withShows(shown);
    }

    /**
     * Un appel au modèle, en flux, sous le délai du premier jeton (D9b) ; ses fragments passent la porte des phrases.
     * Le coût est compté à la fin de l'appel, en tâche de fond.
     */
    private Called call(AgentTurnRequest call, SentenceGate gate, UUID userId, Workspace workspace) {
        long started = System.nanoTime();
        AtomicLong firstToken = new AtomicLong(-1L);
        CountDownLatch first = new CountDownLatch(1);
        Object lock = new Object();
        boolean[] abandoned = { false };
        boolean[] streamed = { false };
        CompletableFuture<AgentTurn> pending = CompletableFuture.supplyAsync(() -> provider.nextTurn(call, delta -> {
            if (firstToken.compareAndSet(-1L, (System.nanoTime() - started) / 1_000_000L)) {
                first.countDown();
            }
            synchronized (lock) {
                StringBuilder probe = probeRaw;
                if (probe != null) {
                    probe.append(delta);
                }
                if (!abandoned[0]) {
                    if (delta != null && !delta.isEmpty()) {
                        streamed[0] = true;
                    }
                    gate.accept(delta);
                }
            }
        }), calls);
        pending.whenComplete((turn, failure) -> {
            first.countDown();
            if (turn != null) {
                account(userId, workspace, turn);
            }
        });

        AgentTurn turn;
        try {
            boolean started1 = first.await(properties.firstTokenTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!started1 && !pending.isDone()) {
                synchronized (lock) {
                    abandoned[0] = true;
                }
                return new Called(null, -1L, REASON_TIMEOUT);
            }
            turn = pending.get(Math.max(1L, properties.firstTokenTimeout().toMillis()) * 20L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Called(null, firstToken.get(), REASON_ERROR);
        } catch (Exception e) {
            log.warn("Cerveau rapide : appel en échec ({})", e.getClass().getSimpleName());
            return new Called(null, firstToken.get(), REASON_ERROR);
        }
        long firstMs = firstToken.get() >= 0 ? firstToken.get() : (System.nanoTime() - started) / 1_000_000L;
        if (turn == null || turn.refused()) {
            return new Called(turn, firstMs, REASON_REFUSAL);
        }
        boolean delegates = turn.toolCalls().stream()
                .anyMatch(tc -> JarvisFastBrainPrompt.TOOL_NAME.equals(tc.name()));
        synchronized (lock) {
            if (!delegates && !streamed[0] && turn.text() != null) {
                gate.accept(turn.text()); // fournisseur sans flux : le texte entier passe la même porte
            }
            gate.finish();
        }
        return new Called(turn, firstMs, null);
    }

    /** F-237 / SF-237-08 — le plafond du bloc « ## Écran » reçu de l'écran. */
    static final int MAX_SCREEN = 1_500;
    /** F-237 / SF-237-13 — la lecture des pièces au banc : fixe (aucune base lue). */
    static final String BENCH_PIECES = JarvisFastPieces.NONE;

    /** L'outil {@code pieces} : lu par la passerelle sous {@code user_id} (au banc : réponse fixe). */
    String readPieces(Request request, JsonNode input, boolean bench) {
        if (bench || fastPieces == null) {
            String fixture = BENCH_PIECES_FIXTURE.get();
            return bench && fixture != null ? fixture : BENCH_PIECES;
        }
        return fastPieces.read(request.userId(), request.workspace() == null ? null : request.workspace().getId(),
                input);
    }

    /** F-243 / SF-243-02 — la lecture de l'agenda au banc : le texte fixe du scénario, pour le fil appelant. */
    private static final ThreadLocal<String> BENCH_AGENDA_FIXTURE = new ThreadLocal<>();

    /** F-243 / SF-243-02 — exécute {@code body} avec l'agenda fictif {@code agenda} (ou « aucun agenda relié »). */
    public <T> T withBenchAgenda(String agenda, java.util.function.Supplier<T> body) {
        BENCH_AGENDA_FIXTURE.set(agenda);
        try {
            return body.get();
        } finally {
            BENCH_AGENDA_FIXTURE.remove();
        }
    }

    /** F-243 / SF-243-02 — les agendas du compte (lecture seule). Facultatif. */
    private fr.claudegateway.jarvis.calendar.JarvisCalendarReader calendar;

    @Autowired(required = false)
    public void setCalendar(fr.claudegateway.jarvis.calendar.JarvisCalendarReader calendar) {
        this.calendar = calendar;
    }

    /**
     * F-243 / SF-243-02 (D9, D11) — l'outil {@code agenda} : le cache lu sous {@code user_id}, sans les agendas
     * personnels en présentation (SF-243-05) ;
     * au banc, le texte fixe du scénario. Best-effort : une panne rend « aucun agenda relié » (la question se délègue).
     */
    String readAgenda(Request request, JsonNode input, boolean bench) {
        if (bench) {
            String fixture = BENCH_AGENDA_FIXTURE.get();
            if (fixture == null) {
                return fr.claudegateway.jarvis.calendar.JarvisCalendarReader.NO_FEED;
            }
            // SF-243-05 — la même fonction qu'en prod : les agendas personnels retirés en présentation.
            return request.presentation() ? fr.claudegateway.jarvis.calendar.JarvisCalendarReader.forPresentation(fixture)
                    : fixture;
        }
        if (calendar == null) {
            return fr.claudegateway.jarvis.calendar.JarvisCalendarReader.NO_FEED;
        }
        try {
            return calendar.toolText(request.userId(), input == null ? "" : input.path("periode").asText(""),
                    input == null ? "" : input.path("jour").asText(""),
                    input == null ? "" : input.path("date").asText(""), request.presentation());
        } catch (RuntimeException e) {
            log.debug("Cerveau rapide : agenda illisible ({})", e.getClass().getSimpleName());
            return fr.claudegateway.jarvis.calendar.JarvisCalendarReader.NO_FEED;
        }
    }

    /**
     * F-243 / SF-243-02 (D10) — l'ordre d'écran qui ouvre la vue d'agenda ({@code agenda:<vue>:<date>}), seulement
     * quand des rendez-vous ont été lus (jamais en présentation, jamais sans agenda relié).
     */
    JarvisShowCommand agendaView(Request request, JsonNode input, String text) {
        if (request.presentation() || calendar == null || text == null || !text.startsWith("Agenda,")) {
            return null;
        }
        fr.claudegateway.jarvis.calendar.JarvisCalendarReader.Period p = calendar.period(
                input == null ? "" : input.path("periode").asText(""), input == null ? "" : input.path("jour").asText(""),
                input == null ? "" : input.path("date").asText(""));
        String view = "semaine".equals(p.name()) ? "semaine" : "jour";
        return new JarvisShowCommand("piece", List.of("agenda:" + view + ":" + p.first()), null, null, null, null,
                null, null, null, "demande", null, null);
    }

    /** Les blocs « ## Écran » (de l'écran, coupé) et « ## Pièces » (du sujet courant), précédés d'une ligne vide. */
    String screenAndPieces(Request request) {
        StringBuilder out = new StringBuilder();
        String screen = request.screen() == null ? "" : request.screen().strip();
        if (!screen.isEmpty()) {
            if (!screen.startsWith("## Écran")) {
                screen = "## Écran\n" + screen;
            }
            if (screen.length() > MAX_SCREEN) {
                screen = screen.substring(0, MAX_SCREEN) + "…";
            }
            out.append("\n\n").append(screen);
        }
        if (fastPieces != null && request.workspace() != null) {
            String block = fastPieces.block(request.userId(), request.workspace().getId());
            if (block != null && !block.isBlank()) {
                out.append("\n\n").append(block);
            }
        }
        return out.toString();
    }

    /**
     * Délégation demandée par le PO (D9d) : la parole <b>précédente</b> de la session part à Opus (à défaut, la
     * parole elle-même), sans appel au cerveau rapide.
     */
    Outcome forced(Request request) {
        return forced(request, true, null);
    }

    private Outcome forced(Request request, boolean withMemory, String fixedContext) {
        String previous = null;
        String previousAnswer = null;
        if (memory != null && withMemory) {
            List<JarvisFastMemory.Exchange> recalled = memory.recall(request.userId());
            if (!recalled.isEmpty()) {
                previous = recalled.get(recalled.size() - 1).said();
                previousAnswer = recalled.get(recalled.size() - 1).answered();
            }
        }
        // F-240 / SF-240-05 — l'annonce dit ce qui est vérifié : la dernière réponse (mémoire, sinon le fil).
        String context;
        try {
            context = fixedContext != null ? fixedContext : context(request);
        } catch (RuntimeException e) {
            context = null;
        }
        String announce = forcedAnnounce(previousAnswer, JarvisFastGuards.lastOpusAnswer(context),
                request.workspace() == null ? null : request.workspace().getName());
        String said = request.text() == null ? "" : request.text().strip();
        String demande = previous == null || previous.isBlank() ? said
                : previous + "\n(L'utilisateur demande de vérifier : « " + said + " ». La réponse rapide précédente "
                        + "n'était pas vérifiée.)";
        UUID subject = request.workspace() == null ? null : request.workspace().getId();
        return new Outcome(Kind.DELEGATE, "", new Delegation(UUID.randomUUID(), demande, "", announce, subject,
                request.chantier(), true, REASON_FORCED), REASON_FORCED, properties.model(), -1L, null,
                new Guards(0, 0, 0, true, false, 0));
    }

    /**
     * F-240 / SF-240-05 — <b>l'annonce d'une délégation d'office dit ce qui est vérifié</b>, sans appel au modèle,
     * d'après des faits déjà présents (donc ancrée) : les tickets de la dernière réponse (au plus
     * {@value #FORCED_MAX_TICKETS}), sinon son chantier, sinon le sujet courant, sinon la dernière réponse elle-même.
     *
     * @param lastAnswer la dernière réponse de la session (mémoire), ou {@code null}
     * @param opusAnswer la dernière réponse d'Opus des derniers échanges du fil, ou {@code null}
     * @param subject    le nom du sujet courant, ou {@code null}
     */
    static String forcedAnnounce(String lastAnswer, String opusAnswer, String subject) {
        for (String source : new String[] { lastAnswer, opusAnswer }) {
            if (source == null || source.isBlank()) {
                continue;
            }
            List<String> tickets = JarvisFastGuards.tickets(source).stream().limit(FORCED_MAX_TICKETS).toList();
            if (tickets.size() == 1) {
                return "Je regarde de nouveau où en est " + tickets.get(0) + ", Monsieur.";
            }
            if (tickets.size() > 1) {
                String named = String.join(", ", tickets.subList(0, tickets.size() - 1)) + " et "
                        + tickets.get(tickets.size() - 1);
                return "Je regarde de nouveau où en sont " + named + ", Monsieur.";
            }
            java.util.regex.Matcher chantier = FORCED_CHANTIER.matcher(source);
            if (chantier.find()) {
                return "Je regarde de nouveau où en est le chantier " + chantier.group(1) + ", Monsieur.";
            }
        }
        if (subject != null && !subject.isBlank()) {
            return "Je vérifie de nouveau ce point sur " + subject.strip() + ", Monsieur.";
        }
        return FORCED_ANNOUNCE;
    }

    /**
     * L'annonce d'une délégation (D11b) : creuse, répétée ou non ancrée ⇒ remplacée par une phrase humaine
     * ({@link #SAFE_ANNOUNCES}, F-245 / SF-245-06), jamais par le libellé brut.
     */
    Delegation withSafeAnnounce(Delegation d, SentenceGate gate) {
        String annonce = d.annonce();
        boolean bad = annonce == null || annonce.isBlank()
                || JarvisFastGuards.isFillerAlone(annonce) || gate.repeats(annonce)
                || JarvisFastGuards.looksLikeToolCall(annonce) || gate.ungrounded(annonce) != null;
        if (!bad) {
            return d;
        }
        // F-245 / SF-245-06 — le repli est une phrase humaine, jamais le libellé brut (« Je regarde : idées cadeau
        // anniversaire épouse. », banc prod du 04/11, T03) ; la seconde si la première vient d'être dite, sinon le
        // silence.
        String safe = SAFE_ANNOUNCES.stream().filter(a -> !gate.repeats(a)).findFirst().orElse(AUTO_ANNOUNCE);
        return new Delegation(d.delegationId(), d.demande(), d.label(), safe, d.subjectId(), d.chantier(),
                d.automatic(), d.reason());
    }

    /** Les phrases des {@value #REPEAT_WINDOW} dernières réponses de la session (D11c). */
    List<String> recentSentences(UUID userId) {
        if (memory == null) {
            return List.of();
        }
        List<JarvisFastMemory.Exchange> recalled = memory.recall(userId);
        List<String> sentences = new ArrayList<>();
        for (int i = Math.max(0, recalled.size() - REPEAT_WINDOW); i < recalled.size(); i++) {
            String answered = recalled.get(i).answered();
            if (answered == null || answered.isBlank()) {
                continue;
            }
            JarvisFastGuards.Split split = JarvisFastGuards.split(answered);
            sentences.addAll(split.sentences());
            if (!split.rest().isBlank()) {
                sentences.add(split.rest().strip());
            }
        }
        return sentences;
    }

    /**
     * <b>La porte des phrases</b> (D9, D11) : retient les fragments jusqu'à la fin d'une phrase, la contrôle, puis
     * seulement la laisse partir vers l'écran. Un fait non ancré ou un texte d'outil ferme la porte (plus rien ne
     * part) et l'échange devient une délégation d'office.
     */
    static final class SentenceGate {
        private static final java.util.regex.Pattern MONSIEUR = java.util.regex.Pattern
                .compile("(?i)(,\\s*)?\\bmonsieur\\b");
        private String context;
        private final String said;
        private final List<String> recent;
        private final Sink out;
        private final StringBuilder pending = new StringBuilder();
        private final StringBuilder spoken = new StringBuilder();
        private final List<String> saidNow = new ArrayList<>();
        private boolean streamed;
        private boolean addressed;
        /** F-240 / SF-240-04 — la ligne-marque « ⤵ » a été relayée. */
        private boolean more;
        private String abortReason;
        private int groundingTrips;
        private int fillersRemoved;
        private int repeatsRemoved;
        private boolean toolText;
        private int imitationsRemoved;
        /** F-239 / SF-239-13 — au plus autant de phrases dites par réponse. */
        static final int MAX_SENTENCES = 3;
        /** F-239 / SF-239-02 (D3) — une salutation : une phrase non ancrée est retirée, jamais déléguée. */
        private final boolean smallTalk;
        /** F-241 / SF-241-03 (D12 a) — les termes « À ne pas aborder » du portrait. */
        private java.util.Set<String> forbidden = java.util.Set.of();
        private int portraitBlocked;

        void forbid(java.util.Set<String> terms) {
            this.forbidden = terms == null ? java.util.Set.of() : terms;
        }

        // ---- F-247 / SF-247-10 (D8) : la garde de fraîcheur ----
        private final List<fr.claudegateway.jarvis.state.JarvisStateBlock.Line> stale = new ArrayList<>();
        private java.time.OffsetDateTime staleNow;

        /** Les faits lus (bloc ou outil {@code etat}) : une phrase qui en cite un de plus d'1 h est datée. */
        void stale(List<fr.claudegateway.jarvis.state.JarvisStateBlock.Line> lines, java.time.OffsetDateTime now) {
            if (lines != null) {
                stale.addAll(lines);
            }
            staleNow = now;
        }

        // ---- F-241 / SF-241-04 : les allusions (D11, D12 b/c) et les lignes-marques ----
        private java.util.Set<String> allusionTerms = java.util.Set.of();
        private boolean saidPersonal;
        private boolean spontaneousBefore;
        private List<String> recentTopics = List.of();
        private boolean spontaneousNow;
        private int allusionsBlocked;
        /** Une allusion repérée dans une phrase dite (rattrapage déterministe). */
        private record Detected(String term, String sentence, boolean spontaneous) {
        }
        private final List<Detected> detected = new ArrayList<>();
        /** Une déclaration ✦ du modèle : {@code ALLUSION} ou {@code HUMOUR} et son sujet. */
        private record Declared(String kind, String topic) {
        }
        private final List<Declared> declared = new ArrayList<>();
        /** F-241 / SF-241-05 — les lignes 📌 relevées (jamais dites). */
        private final List<String> pins = new ArrayList<>();
        private static final java.util.regex.Pattern MARK_LINE = java.util.regex.Pattern
                .compile("(?m)^[ \\t]*(✦|\uD83D\uDCCC)[^\\n]*(\\n|$)");
        private static final java.util.regex.Pattern DECLARATION = java.util.regex.Pattern
                .compile("(?i)^✦\\s*(allusion|humour)\\s*:?\\s*(.*)$");

        /** F-243 / SF-243-06 — les racines du « moment » du portrait (la cause connue). */
        private java.util.Set<String> momentStems = java.util.Set.of();

        void moment(java.util.Set<String> stems) {
            this.momentStems = stems == null ? java.util.Set.of() : stems;
        }

        void allusions(java.util.Set<String> terms, boolean saidPersonal, boolean spontaneousBefore,
                List<String> recentTopics) {
            this.allusionTerms = terms == null ? java.util.Set.of() : terms;
            this.saidPersonal = saidPersonal;
            this.spontaneousBefore = spontaneousBefore;
            this.recentTopics = recentTopics == null ? List.of() : recentTopics;
        }

        List<String> pins() {
            return List.copyOf(pins);
        }

        private boolean pinsAllowed = true;

        void pinsAllowed(boolean allowed) {
            this.pinsAllowed = allowed;
        }

        /**
         * F-241 / SF-241-05 (D8, D9) — les lignes 📌 relayées : aucune en présentation ; une seule proposition, et
         * jamais dans la même réponse qu'une confirmation ou une invitation ↪.
         */
        List<fr.claudegateway.jarvis.portrait.JarvisPortraitNote.Pin> relayedPins() {
            if (!pinsAllowed) {
                return List.of();
            }
            String said = spoken();
            boolean question = said.contains("Vous confirmez") || said.contains("↪");
            List<fr.claudegateway.jarvis.portrait.JarvisPortraitNote.Pin> out = new ArrayList<>();
            boolean proposed = false;
            for (String line : pins) {
                fr.claudegateway.jarvis.portrait.JarvisPortraitNote.Pin pin =
                        fr.claudegateway.jarvis.portrait.JarvisPortraitNote.pin(line);
                if (pin == null) {
                    continue;
                }
                if ("retenir".equals(pin.action())) {
                    if (proposed || question) {
                        continue;
                    }
                    proposed = true;
                }
                out.add(pin);
            }
            return out;
        }

        /** Une ligne-marque : jamais dite. ✦ ⇒ déclaration ; 📌 ⇒ relevée (SF-241-05). */
        private void mark(String line) {
            String l = line.strip();
            if (l.startsWith("✦")) {
                java.util.regex.Matcher m = DECLARATION.matcher(l);
                if (m.matches() && !m.group(2).isBlank()) {
                    declared.add(new Declared(m.group(1).equalsIgnoreCase("humour") ? "HUMOUR" : "ALLUSION",
                            m.group(2).strip()));
                }
            } else if (!l.isEmpty()) {
                pins.add(l);
            }
        }

        /** Retire du tampon les lignes-marques complètes (ou la dernière, à la fin). Rend vrai s'il faut attendre. */
        private boolean extractMarks(boolean last) {
            java.util.regex.Matcher m = MARK_LINE.matcher(pending);
            StringBuilder kept = new StringBuilder();
            int from = 0;
            while (m.find()) {
                boolean complete = !m.group(2).isEmpty() || last;
                if (!complete) {
                    break;
                }
                kept.append(pending, from, m.start());
                mark(m.group());
                from = m.end();
            }
            kept.append(pending.substring(from));
            pending.setLength(0);
            pending.append(kept);
            String rest = pending.toString().stripLeading();
            return !last && (rest.startsWith("✦") || rest.startsWith("\uD83D\uDCCC"));
        }

        /** D11 — l'allusion de la journée : {@code SPONTANEE}, {@code REPONSE} ou {@code AUCUNE} ({@code null} sans portrait). */
        String personalAllusion() {
            if (allusionTerms.isEmpty() && declared.isEmpty() && detected.isEmpty()) {
                return null;
            }
            boolean any = !detected.isEmpty() || declared.stream().anyMatch(d -> "ALLUSION".equals(d.kind()));
            if (!any) {
                return "AUCUNE";
            }
            return saidPersonal ? "REPONSE" : "SPONTANEE";
        }

        /** Ce qui est journalisé : les déclarations, puis les allusions rattrapées que rien ne déclarait. */
        interface Journal {
            void record(String kind, String topic, String phrase, boolean spontaneous);
        }

        void journal(Journal out) {
            String phrase = spoken();
            for (Declared d : declared) {
                out.record(d.kind(), d.topic(), phrase, !saidPersonal);
            }
            for (Detected d : detected) {
                boolean covered = declared.stream().anyMatch(x -> fr.claudegateway.jarvis.portrait.JarvisPortraitNote
                        .repeats(d.term(), List.of(fr.claudegateway.jarvis.portrait.JarvisPortraitNote
                                .normalize(x.topic()))));
                if (!covered) {
                    out.record("ALLUSION", d.term(), d.sentence(), d.spontaneous());
                }
            }
        }

        int portraitBlocked() {
            return portraitBlocked;
        }

        SentenceGate(String context, String said, List<String> recent, Sink out) {
            this(context, said, recent, out, false);
        }

        SentenceGate(String context, String said, List<String> recent, Sink out, boolean smallTalk) {
            this.context = context == null ? "" : context;
            this.said = said == null ? "" : said;
            this.recent = recent == null ? List.of() : recent;
            this.out = out;
            this.smallTalk = smallTalk;
            this.courtesyAsked = JarvisFastGuards.courtesyAlreadyAsked(this.context);
        }

        /** F-240 / SF-240-07 — le contexte dit qu'une question de politesse a déjà été posée. */
        private final boolean courtesyAsked;

        // ---- F-239 / SF-239-05 : la ligne « compris: » (jamais dite) ----
        private static final java.util.regex.Pattern UNDERSTOOD = java.util.regex.Pattern
                .compile("(?is)^\\s*compris\\s*:\\s*(.*)$");
        private static final java.util.regex.Pattern LEAKED_HEADER = java.util.regex.Pattern.compile("compris\\s*:");
        private final StringBuilder header = new StringBuilder();
        private boolean headerDone;
        private boolean headerSeen;
        private String understood;
        private int understandingRejected;

        /** La relecture acceptée par la garde, ou {@code null}. */
        String understood() {
            return understood;
        }

        int understandingRejected() {
            return understandingRejected;
        }

        /**
         * Retient le début de la réponse tant qu'il peut être la ligne {@code compris:} ; rend ce qui reste à traiter
         * comme réponse (vide tant que l'en-tête n'est pas tranché).
         */
        private String header(String delta, boolean last) {
            if (headerDone) {
                return delta;
            }
            header.append(delta);
            String text = header.toString();
            String start = text.stripLeading().toLowerCase(java.util.Locale.ROOT);
            boolean maybe = "compris:".startsWith(start.length() >= 8 ? start.substring(0, 8) : start)
                    || start.startsWith("compris");
            if (!maybe) {
                // F-240 / SF-240-06 — une note du modèle écrite AVANT la ligne « compris: » (« Phrase
                // incompréhensible, je demande de répéter sans la citer. », banc prod) n'est jamais dite : la
                // première ligne est retenue jusqu'à la suivante ; si celle-ci est l'en-tête, la note est écartée.
                int nl = text.indexOf('\n');
                if (nl < 0 && !last && text.length() < 1_200) {
                    return "";
                }
                if (nl >= 0) {
                    String after = text.substring(nl + 1);
                    String next = after.stripLeading().toLowerCase(java.util.Locale.ROOT);
                    if (!last && next.length() < 8 && "compris:".startsWith(next) && text.length() < 1_200) {
                        return "";
                    }
                    if (LEAKED_HEADER.matcher(next).lookingAt()) {
                        header.setLength(0);
                        return header(after, last);
                    }
                }
                headerDone = true;
                header.setLength(0);
                return text;
            }
            int newline = text.indexOf('\n');
            if (newline < 0 && !last && text.length() < 1_200) {
                return "";
            }
            headerDone = true;
            header.setLength(0);
            String line = newline < 0 ? text : text.substring(0, newline);
            java.util.regex.Matcher m = UNDERSTOOD.matcher(line);
            if (m.matches() && headerSeen) {
                return newline < 0 ? "" : text.substring(newline + 1);
            }
            if (m.matches()) {
                String read = m.group(1).strip().replaceAll("^[«\"]+|[»\"]+$", "").strip();
                understood = JarvisUnderstanding.accept(said, read);
                if (understood == null) {
                    understandingRejected++;
                } else {
                    out.understood(understood);
                }
                return newline < 0 ? "" : text.substring(newline + 1);
            }
            return text;
        }

        void accept(String delta) {
            if (delta == null || delta.isEmpty() || abortReason != null) {
                return;
            }
            streamed = true;
            delta = header(delta, false);
            if (delta.isEmpty()) {
                return;
            }
            feed(delta);
        }

        /** Ce qui suit l'en-tête : découpé en phrases, chacune passée aux gardes. */
        private void feed(String delta) {
            streamed = true;
            pending.append(delta);
            // F-241 / SF-241-04 — les lignes-marques (✦, 📌) sortent du flux avant le découpage en phrases.
            if (extractMarks(false)) {
                return;
            }
            JarvisFastGuards.Split split = JarvisFastGuards.split(pending.toString());
            pending.setLength(0);
            pending.append(split.rest());
            for (String sentence : split.sentences()) {
                check(sentence);
            }
            // Un appel d'outil écrit en texte se voit souvent avant la fin de « phrase » : on coupe tout de suite.
            if (abortReason == null && JarvisFastGuards.looksLikeToolCall(pending.toString())) {
                abort(REASON_TOOL_TEXT);
            }
        }

        void finish() {
            if (!headerDone) {
                String rest = header("", true);
                if (!rest.isEmpty() && abortReason == null) {
                    feed(rest); // F-240 / SF-240-06 — une première ligne retenue : découpée comme le reste
                }
            }
            extractMarks(true);
            String rest = pending.toString().strip();
            pending.setLength(0);
            if (!rest.isEmpty()) {
                check(rest);
            }
        }

        private void check(String raw) {
            if (abortReason != null || more) {
                return; // F-240 / SF-240-04 : après « ⤵ », plus rien n'est dit
            }
            String sentence = raw.strip();
            if (sentence.isEmpty()) {
                return;
            }
            if (sentence.startsWith("✦") || sentence.startsWith("\uD83D\uDCCC")) {
                mark(sentence); // F-241 / SF-241-04 — une ligne-marque restée collée : jamais dite
                return;
            }
            if (sentence.startsWith(JarvisFastBrainPrompt.MORE_MARK)) {
                // F-240 / SF-240-04 (D8) — la ligne-marque : relayée telle quelle (jamais dite, jamais un fait), sans
                // passer par les gardes (une seconde « ⤵ » n'est pas une répétition creuse).
                more = true;
                if (spoken.length() > 0) {
                    spoken.append(' ');
                }
                spoken.append(JarvisFastBrainPrompt.MORE_MARK);
                out.text(JarvisFastBrainPrompt.MORE_MARK + " ");
                return;
            }
            try {
                if (JarvisFastGuards.looksLikeToolCall(sentence)) {
                    abort(REASON_TOOL_TEXT);
                    return;
                }
                // F-243 / SF-243-06 — « Je vois cela, Monsieur : … » : la formule creuse d'ouverture est retirée.
                String unled = JarvisFastGuards.withoutHollowLead(sentence);
                if (!unled.equals(sentence)) {
                    fillersRemoved++;
                    sentence = unled;
                }
                if (fr.claudegateway.jarvis.portrait.JarvisPortraitNote.blocked(sentence, said, forbidden)) {
                    portraitBlocked++; // D12 (a) : un sujet interdit que le PO n'a pas amené n'est jamais dit
                    return;
                }
                if (JarvisFastGuards.imitates(sentence, context, said)) {
                    imitationsRemoved++; // D18 : aucune imitation de l'œuvre — la phrase est retirée
                    return;
                }
                // F-240 / SF-240-07 — un lien de cause ou de dépendance que le contexte n'énonce pas (« … et sa
                // validation en dépend ») : la partie liée est retirée ; si rien de dicible ne reste, la phrase est
                // retirée (une autre a été dite) ou traitée comme un fait non ancré.
                String unlinked = JarvisFastGuards.withoutInventedLink(sentence, context, said);
                if (!sentence.equals(unlinked)) {
                    groundingTrips++;
                    if (unlinked.isEmpty()) {
                        if (smallTalk || !saidNow.isEmpty()) {
                            return;
                        }
                        abort(REASON_GROUNDING);
                        return;
                    }
                    sentence = unlinked;
                }
                if (ungrounded(sentence) != null) {
                    groundingTrips++;
                    if (smallTalk) {
                        return; // F-239 (D3) : sur une salutation, la partie non ancrée est retirée, sans délégation
                    }
                    abort(REASON_GROUNDING);
                    return;
                }
                // F-240 / SF-240-06 — ni aveu d'ignorance, ni source inexistante offerte (« je peux consulter votre
                // agenda ») : une information absente se délègue (hors petite conversation).
                if (!smallTalk && (JarvisFastGuards.claimsIgnorance(sentence)
                        || JarvisFastGuards.namesMissingSource(sentence, context))) {
                    // F-240 / SF-240-07 — sur une remarque (« Grosse journée qui s'annonce. »), rien n'est à chercher :
                    // la source est retirée de la phrase, ou la phrase après une autre ; jamais de délégation inutile.
                    String kept = JarvisFastGuards.isRemark(said)
                            ? JarvisFastGuards.withoutMissingSource(sentence, context) : "";
                    if (kept.isEmpty() && JarvisFastGuards.isRemark(said) && !saidNow.isEmpty()) {
                        fillersRemoved++;
                        return;
                    }
                    if (kept.isEmpty()) {
                        abort(REASON_UNKNOWN);
                        return;
                    }
                    sentence = kept;
                }
                if (JarvisFastGuards.isFillerAlone(sentence) || JarvisFastGuards.isListeningInvitation(sentence)
                        || (!saidNow.isEmpty() && JarvisFastGuards.isClosingPrompt(sentence, said))
                        // F-243 / SF-243-06 — « J'en prends bonne note. » en conclusion (banc prod, C08#1).
                        || (!saidNow.isEmpty() && JarvisFastGuards.isAdministrativeAck(sentence))) {
                    fillersRemoved++; // F-239 (D3) : « Je vous écoute… » retiré, seul ou en conclusion
                    return;
                }
                // F-239 / SF-239-13 — ni phrase sur « le contexte », ni offre de détail d'office, ni plus de trois
                // phrases dites (banc prod du 02/11).
                if (JarvisFastGuards.isMetaTalk(sentence) || JarvisFastGuards.isReasoningLeak(sentence)
                        || JarvisFastGuards.isDetailOffer(sentence)
                        || saidNow.size() >= MAX_SENTENCES) {
                    fillersRemoved++;
                    return;
                }
                if (repeats(sentence)) {
                    repeatsRemoved++;
                    return;
                }
                // F-240 / SF-240-07 — au plus une question de politesse par conversation (banc prod du 03/11, P16) :
                // déjà posée ⇒ la nouvelle est retirée, pourvu qu'une autre phrase ait été dite.
                if (courtesyAsked && !saidNow.isEmpty() && JarvisFastGuards.isCourtesyQuestion(sentence)) {
                    fillersRemoved++;
                    return;
                }
                // F-243 / SF-243-06 — Monsieur amène lui-même sa vie (« je suis fatigué ») alors qu'une allusion vient
                // d'être faite : la cause connue (« ces nuits courtes ») n'est pas redite (banc, P07), pourvu qu'une
                // autre phrase ait été dite.
                if (saidPersonal && !recentTopics.isEmpty() && !saidNow.isEmpty()
                        && fr.claudegateway.jarvis.portrait.JarvisPortraitNote.restatesMoment(sentence, said,
                                momentStems)) {
                    allusionsBlocked++;
                    return;
                }
                // F-241 / SF-241-04 (D12 b, c) — une allusion spontanée : une seule par jour, jamais un sujet récent.
                String term = fr.claudegateway.jarvis.portrait.JarvisPortraitNote.allusionTerm(sentence, said,
                        allusionTerms);
                if (term != null) {
                    boolean spontaneous = !saidPersonal;
                    if (spontaneous && (spontaneousBefore || spontaneousNow
                            || fr.claudegateway.jarvis.portrait.JarvisPortraitNote.repeats(term, recentTopics))) {
                        allusionsBlocked++;
                        return;
                    }
                    detected.add(new Detected(term, sentence, spontaneous));
                    spontaneousNow |= spontaneous;
                }
            } catch (RuntimeException e) {
                groundingTrips++;
                abort(REASON_GROUNDING); // une garde en panne ne laisse jamais passer un fait
                return;
            }
            if (!stale.isEmpty() && staleNow != null) {
                sentence = fr.claudegateway.jarvis.state.JarvisStateBlock.withFreshness(sentence, stale, staleNow);
            }
            String kept = oneMonsieur(sentence);
            saidNow.add(kept);
            if (spoken.length() > 0) {
                spoken.append(' ');
            }
            spoken.append(kept);
            out.text(kept + " ");
        }

        /** « Monsieur » au plus une fois par réponse (D11e) : les suivants sont retirés. */
        private String oneMonsieur(String sentence) {
            java.util.regex.Matcher m = MONSIEUR.matcher(sentence);
            StringBuilder b = new StringBuilder();
            while (m.find()) {
                if (!addressed) {
                    addressed = true;
                    m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(m.group()));
                } else {
                    m.appendReplacement(b, "");
                }
            }
            m.appendTail(b);
            String cleaned = b.toString().replaceAll("\\s+([.!?…,])", "$1").replaceAll("\\s{2,}", " ").strip();
            return cleaned.isEmpty() ? sentence : cleaned;
        }

        String ungrounded(String sentence) {
            return JarvisFastGuards.ungroundedFact(sentence, context, said);
        }

        /** F-237 / SF-237-08 — ce que la lecture des pièces a rendu ancre les phrases suivantes. */
        void ground(String extra) {
            if (extra != null && !extra.isBlank()) {
                context = context + "\n" + extra;
            }
        }

        /**
         * F-237 / SF-237-08 — un appel de plus dans la même réponse : sa ligne {@code compris:} éventuelle est retirée
         * (la relecture du premier appel reste celle de l'échange).
         */
        void nextCall() {
            headerDone = false;
            header.setLength(0);
            headerSeen = true;
        }

        boolean repeats(String sentence) {
            return JarvisFastGuards.isRepeat(sentence, recent) || JarvisFastGuards.isRepeat(sentence, saidNow);
        }

        private void abort(String reason) {
            if (REASON_TOOL_TEXT.equals(reason)) {
                toolText = true;
            }
            abortReason = reason;
            pending.setLength(0);
        }

        boolean streamedAnything() {
            return streamed;
        }

        String abortReason() {
            return abortReason;
        }

        String spoken() {
            return spoken.toString().strip();
        }

        Guards guards() {
            return new Guards(groundingTrips, fillersRemoved, repeatsRemoved, false, toolText, imitationsRemoved,
                    portraitBlocked, personalAllusion(), allusionsBlocked, relayedPins());
        }
    }


    /** Les messages de l'appel : la parole et son contexte, dans le message (jamais dans la consigne système). */
    List<AgentMessage> messages(Request request) {
        return messages(request, context(request), reference(request));
    }

    /** F-239 / SF-239-11 — le message d'ouverture qui porte l'univers de référence, et la réponse fixe qui le suit. */
    static final String REFERENCE_HEADER = "[Référence — univers]\n";
    static final String REFERENCE_ACK = "Noté.";

    /**
     * F-239 / SF-239-08 — l'état de la conversation du jour : aucune trace de la journée ⇒ première salutation ; une des
     * trois dernières réponses porte une question ⇒ question de politesse déjà posée. Lu dans la mémoire du compte.
     */
    String dayState(Request request) {
        List<JarvisFastMemory.Exchange> recalled = memory == null ? List.of() : memory.recall(request.userId());
        boolean asked = recalled.stream().skip(Math.max(0, recalled.size() - 3))
                .anyMatch(e -> e.answered() != null && e.answered().contains("?"));
        return JarvisFastContextBuilder.dayState(recalled.isEmpty(), asked);
    }

    /** F-239 / SF-239-11 — l'univers de référence du compte (vide sans constructeur de contexte, ou illisible). */
    String reference(Request request) {
        if (contextBuilder == null) {
            return "";
        }
        try {
            return contextBuilder.reference(request.userId());
        } catch (RuntimeException e) {
            return "";
        }
    }

    List<AgentMessage> messages(Request request, String context) {
        return messages(request, context, "");
    }

    /**
     * Les échanges de la session (mots seuls, D6) puis le dernier message, seul à porter le contexte. Un échange
     * dont la réponse est vide n'est pas rejoué (la conversation doit alterner).
     */
    /**
     * F-239 / SF-239-11 — {@code [univers de référence] → [« Noté. »] → échanges de la session → [contexte volatil +
     * parole]} : tout ce qui précède le dernier message est stable d'un appel à l'autre (relu en cache) ; le dernier,
     * propre à l'appel, n'est pas écrit en cache (forme {@code compact}).
     */
    List<AgentMessage> messages(Request request, String context, String reference) {
        return messages(request, context, reference, "");
    }

    /** F-241 / SF-241-03 — les mêmes messages, le dernier suivi des notes volatiles du portrait (ou rien). */
    List<AgentMessage> messages(Request request, String context, String reference, String notes) {
        return messages(request, context, reference, notes, null);
    }

    /** F-252 / SF-252-01 — les mêmes messages ; {@code soberRead} : la lecture de sobriété partie en avance, ou rien. */
    List<AgentMessage> messages(Request request, String context, String reference, String notes,
            JarvisFastPrep.Early<Boolean> soberRead) {
        List<AgentMessage> messages = new ArrayList<>();
        if (reference != null && !reference.isBlank()) {
            messages.add(AgentMessage.userText(REFERENCE_HEADER + reference.strip()));
            messages.add(AgentMessage.assistant(List.of(new fr.claudegateway.agent.AgentContentBlock.Text(
                    REFERENCE_ACK))));
        }
        if (memory != null) {
            for (JarvisFastMemory.Exchange e : memory.recall(request.userId())) {
                if (e.answered() == null || e.answered().isBlank()) {
                    continue;
                }
                messages.add(AgentMessage.userText(e.said()));
                messages.add(AgentMessage.assistant(List.of(new fr.claudegateway.agent.AgentContentBlock.Text(
                        e.answered()))));
            }
        }
        String last = message(context, request.text());
        if (sober(request, context, soberRead)) {
            last = last + "\n\n" + fr.claudegateway.jarvis.preferences.JarvisSobriety.NOTE;
        }
        messages.add(AgentMessage.userText(last + (notes == null ? "" : notes)));
        return messages;
    }

    /**
     * F-235 / SF-235-09 (D18) — ton sobre forcé : alerte de veille récente, incident ou erreur dans la parole, ou
     * alerte / échec dans le contexte. Best-effort.
     */
    boolean sober(Request request, String context) {
        return sober(request, context, null);
    }

    /** F-252 / SF-252-01 — idem, avec la lecture partie en avance ({@code null} : lue ici). */
    boolean sober(Request request, String context, JarvisFastPrep.Early<Boolean> soberRead) {
        try {
            if (soberRead != null ? soberRead.get()
                    : sobriety != null && sobriety.sober(request.userId(), request.text())) {
                return true;
            }
            return fr.claudegateway.jarvis.preferences.JarvisSobriety.mentionsTrouble(request.text());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** F-247 / SF-247-10 — le bloc « État connu » préparé à l'avance et l'outil {@code etat}. Facultatifs. */
    private fr.claudegateway.jarvis.state.JarvisStateBlock stateBlock;
    private fr.claudegateway.jarvis.state.JarvisStateTool stateTool;
    /** F-247 / SF-247-13 — l'outil {@code conversations} (déclaré en v19 ; sans lui, « aucune conversation lue »). */
    private fr.claudegateway.jarvis.state.JarvisConversationLookup conversationLookup;
    /** F-247 / SF-247-10 (D8) — les lignes du bloc lues pour la réplique en cours (fil appelant). */
    private static final ThreadLocal<List<fr.claudegateway.jarvis.state.JarvisStateBlock.Line>> STATE_LINES =
            new ThreadLocal<>();

    @Autowired(required = false)
    public void setState(fr.claudegateway.jarvis.state.JarvisStateBlock stateBlock,
            fr.claudegateway.jarvis.state.JarvisStateTool stateTool) {
        this.stateBlock = stateBlock;
        this.stateTool = stateTool;
    }

    @Autowired(required = false)
    public void setConversationLookup(fr.claudegateway.jarvis.state.JarvisConversationLookup conversationLookup) {
        this.conversationLookup = conversationLookup;
    }

    /**
     * F-247 / SF-247-10 (Z9, Z12, D11) — le bloc « État connu », <b>lu tout prêt</b> (aucune requête au registre dans la
     * réplique), joint au message ; vide en présentation, sans registre ou sans cache (pod neuf). Jamais d'exception.
     */
    String stateBlock(Request request) {
        STATE_LINES.remove();
        if (stateBlock == null || request.userId() == null || request.presentation()) {
            return "";
        }
        try {
            Workspace w = request.workspace();
            fr.claudegateway.jarvis.state.JarvisStateBlock.Selected s = stateBlock.ready(request.userId(),
                    w == null ? null : w.getHostId(), w == null ? null : w.getId());
            if (s.text().isBlank()) {
                return "";
            }
            STATE_LINES.set(s.lines());
            // F-248 / SF-248-07 (Z12) — les pancartes du bloc, des faits préparés dans le même cache (aucune requête) ;
            // lecture d'office : montrées si citées. Poste éteint : la mention du pied du jeu.
            if (fr.claudegateway.jarvis.cards.JarvisCardCatalog.active()) {
                List<fr.claudegateway.jarvis.state.JarvisStateBlock.Client> clients =
                        stateBlock.preparedClients(request.userId());
                java.util.Map<UUID, String> names = new java.util.HashMap<>();
                clients.forEach(c -> names.put(c.hostId(), c.name()));
                fr.claudegateway.jarvis.cards.JarvisCardCatalog.collectAll(
                        () -> fr.claudegateway.jarvis.cards.JarvisStateCards.fromFacts(
                                stateBlock.factsFor(request.userId(), s.lines()), names));
                java.util.Set<UUID> shownHosts = new java.util.HashSet<>();
                s.lines().forEach(l -> shownHosts.add(l.hostId()));
                clients.stream().filter(c -> !c.connected() && shownHosts.contains(c.hostId())).findFirst()
                        .ifPresent(c -> fr.claudegateway.jarvis.cards.JarvisCardCatalog.note(
                                fr.claudegateway.jarvis.cards.JarvisStateCards.offlineNote(c.name(),
                                        c.disconnectedAt())));
            }
            return "\n\n" + s.text();
        } catch (RuntimeException e) {
            log.debug("Cerveau rapide : bloc d'état omis ({})", e.getClass().getSimpleName());
            return "";
        }
    }

    /** F-247 / SF-247-10 — l'outil {@code etat} : lecture par index sous {@code user_id}, ancrée ; jamais d'exception. */
    String readState(Request request, JsonNode input, SentenceGate gate) {
        if (stateTool == null) {
            return fr.claudegateway.jarvis.state.JarvisStateTool.OPEN + "\nRien de relevé.\n"
                    + fr.claudegateway.jarvis.state.JarvisStateTool.CLOSE;
        }
        Workspace w = request.workspace();
        fr.claudegateway.jarvis.state.JarvisStateTool.Read r = stateTool.read(request.userId(),
                w == null ? null : w.getHostId(), input, request.presentation());
        if (gate != null) {
            gate.stale(r.lines(), java.time.OffsetDateTime.now(clock));
        }
        return r.text();
    }

    /** F-247 / SF-247-13 — l'outil {@code conversations} ; sans lecteur branché, « aucune conversation lue ». */
    String readConversations(Request request, JsonNode input) {
        if (request.presentation()) {
            return fr.claudegateway.jarvis.state.JarvisConversationLookup.PRESENTATION;
        }
        if (conversationLookup == null) {
            return fr.claudegateway.jarvis.state.JarvisConversationLookup.NONE;
        }
        try {
            Workspace w = request.workspace();
            return conversationLookup.read(request.userId(), w == null ? null : w.getHostId(), input, false);
        } catch (RuntimeException e) {
            return fr.claudegateway.jarvis.state.JarvisConversationLookup.NONE;
        }
    }

    /** F-242 / SF-242-04 — le courrier trié (bloc volatil du message, balisé non fiable). Facultatif. */
    private fr.claudegateway.jarvis.mail.JarvisMailBriefService mail;

    @Autowired(required = false)
    public void setMail(fr.claudegateway.jarvis.mail.JarvisMailBriefService mail) {
        this.mail = mail;
    }

    /**
     * F-242 / SF-242-04 (D8, D16) — le bloc « Courrier » joint au contexte du MESSAGE (jamais à la consigne système) ;
     * vide sans boîte reliée ; en présentation, « indisponible ». Best-effort : une panne ne retire que le bloc.
     */
    String mailBlock(Request request) {
        if (mail == null || request.userId() == null) {
            return "";
        }
        try {
            return mail.contextBlock(request.userId(), request.presentation());
        } catch (RuntimeException e) {
            log.debug("Cerveau rapide : bloc courrier omis ({})", e.getClass().getSimpleName());
            return "";
        }
    }

    /** Le message d'un échange : le contexte, puis la parole. */
    static String message(String context, String text) {
        return "[Contexte]\n" + (context == null ? "" : context.strip()) + "\n\n[Parole de l'utilisateur]\n"
                + (text == null ? "" : text.strip());
    }

    /** Le contexte de l'échange (SF-235-02) ; à défaut de constructeur, l'heure, le sujet et le chantier courants. */
    String context(Request request) {
        if (contextBuilder != null) {
            // F-239 / SF-239-11 — sans l'univers : il est donné en référence en tête de conversation.
            return contextBuilder.build(request.userId(), request.workspace(), request.chantier(),
                    request.heardBeforeCut(), false) + mailBlock(request) + stateBlock(request);
        }
        StringBuilder context = new StringBuilder();
        context.append("Maintenant : ").append(ZonedDateTime.now(clock).withZoneSameInstant(ZONE).format(STAMP))
                .append('\n');
        Workspace workspace = request.workspace();
        if (workspace != null) {
            context.append("Sujet courant : ").append(workspace.getName()).append(" (id ").append(workspace.getId())
                    .append(")\n");
        }
        if (request.chantier() != null) {
            context.append("Chantier courant : ").append(request.chantier()).append('\n');
        }
        return context + mailBlock(request) + stateBlock(request);
    }

    /** La délégation décidée par le modèle ; sujet cible vérifié sous {@code user_id}, sinon le sujet courant. */
    Delegation delegation(Request request, JsonNode input) {
        String demande = text(input, "demande");
        String label = text(input, "label");
        String annonce = text(input, "annonce");
        UUID subject = request.workspace() == null ? null : request.workspace().getId();
        Integer chantier = request.chantier();
        String asked = text(input, "sujet");
        if (JARVIS_SUBJECT.equalsIgnoreCase(asked.strip())) {
            // F-245 / SF-245-02 (D4) — la demande n'est à personne : elle part au fil Jarvis, fil commun (aucun chantier).
            subject = JARVIS_THREAD_TARGET;
            chantier = null;
            asked = "";
        }
        if (!asked.isEmpty()) {
            try {
                UUID candidate = UUID.fromString(asked);
                if (workspaces.findByIdAndUserId(candidate, request.userId()).isPresent()) {
                    if (!candidate.equals(subject)) {
                        chantier = null;
                    }
                    subject = candidate;
                }
            } catch (IllegalArgumentException ignored) {
                // Identifiant illisible : le sujet courant reste la cible.
            }
        }
        JsonNode site = input == null ? null : input.get("chantier");
        if (site != null && site.isIntegralNumber() && !JARVIS_THREAD_TARGET.equals(subject)) {
            chantier = site.asInt();
        }
        if (demande.isEmpty()) {
            demande = request.text() == null ? "" : request.text().strip();
        }
        if (annonce.isEmpty()) {
            annonce = AUTO_ANNOUNCE;
        }
        return new Delegation(UUID.randomUUID(), demande, label, annonce, subject, chantier, false, null);
    }

    /** Délégation d'office de la parole d'origine (D9b) : jamais de silence, jamais d'invention. */
    Outcome auto(Request request, String reason, long firstMs) {
        return auto(request, reason, firstMs, Guards.NONE);
    }

    /** F-253 / SF-253-06 — un tour vide : la délégation d'office porte sa cause, lisible au journal et en mesure. */
    private Outcome autoEmpty(Request request, long firstMs, Guards guards, AgentTurn turn) {
        String diagnostic = emptyDiagnostic(turn, guards);
        log.info("Cerveau rapide : tour vide ({})", diagnostic);
        Outcome o = auto(request, REASON_EMPTY, firstMs, guards);
        Delegation d = o.delegation();
        return new Outcome(o.kind(), o.spokenText(), new Delegation(d.delegationId(), d.demande(), d.label(),
                d.annonce(), d.subjectId(), d.chantier(), d.automatic(), d.reason(), diagnostic), o.reason(), o.model(),
                o.firstTokenMs(), o.costUsd(), o.guards());
    }

    /**
     * F-253 / SF-253-06 — pourquoi un tour n'a rien dit : l'arrêt du modèle, ses outils, chaque motif de rejet de la
     * porte des phrases. Aucun contenu (noms d'outils de la liste fermée, compteurs).
     */
    static String emptyDiagnostic(AgentTurn turn, Guards g) {
        String stop = turn == null ? "aucun" : turn.truncated() ? "max_tokens"
                : turn.toolCalls().isEmpty() ? "end_turn" : "tool_use";
        String tools = turn == null || turn.toolCalls().isEmpty() ? "aucun"
                : String.join(",", turn.toolCalls().stream().map(AgentToolCall::name).toList());
        List<String> rejects = new ArrayList<>();
        addReject(rejects, "ancrage", g.groundingTrips());
        addReject(rejects, "remplissage", g.fillersRemoved());
        addReject(rejects, "repetition", g.repeatsRemoved());
        addReject(rejects, "imitation", g.imitationsRemoved());
        addReject(rejects, "portrait", g.portraitBlocked());
        addReject(rejects, "allusion", g.allusionsBlocked());
        if (g.toolText()) {
            rejects.add("texte_outil:1");
        }
        return "stop=" + stop + " outils=" + tools + " rejets=" + (rejects.isEmpty() ? "aucun" : String.join(",", rejects));
    }

    private static void addReject(List<String> rejects, String motive, int count) {
        if (count > 0) {
            rejects.add(motive + ":" + count);
        }
    }

    Outcome auto(Request request, String reason, long firstMs, Guards guards) {
        UUID subject = request.workspace() == null ? null : request.workspace().getId();
        String said = request.text() == null ? "" : request.text().strip();
        return new Outcome(Kind.DELEGATE, "", new Delegation(UUID.randomUUID(), said, "", AUTO_ANNOUNCE, subject,
                request.chantier(), true, reason), reason, properties.model(), firstMs, null, guards);
    }


    /** F-244 / SF-244-03 — le plafond global de Jarvis (dur à 2 ×). Facultatif. */
    private fr.claudegateway.jarvis.budget.JarvisBudgetService jarvisBudget;

    @Autowired(required = false)
    public void setJarvisBudget(fr.claudegateway.jarvis.budget.JarvisBudgetService jarvisBudget) {
        this.jarvisBudget = jarvisBudget;
    }

    /** F-244 / SF-244-01 — l'étiquette du cerveau rapide au journal du coût. */
    static final fr.claudegateway.quota.UsageTag JARVIS_FAST =
            fr.claudegateway.quota.UsageTag.jarvis(fr.claudegateway.quota.UsageTurn.JarvisPart.FAST);

    /** Coût de l'appel : journal d'usage (annexe, étiqueté JARVIS / FAST) et journée Jarvis. Best-effort. */
    void account(UUID userId, Workspace workspace, AgentTurn turn) {
        account(userId, workspace, turn, JARVIS_FAST);
    }

    /** F-253 / SF-253-05 (D3) — l'Opus du poste éteint : au budget Jarvis, jamais au quota du terminal. */
    static final fr.claudegateway.quota.UsageTag JARVIS_OPUS =
            fr.claudegateway.quota.UsageTag.jarvis(fr.claudegateway.quota.UsageTurn.JarvisPart.OPUS);

    private void account(UUID userId, Workspace workspace, AgentTurn turn, fr.claudegateway.quota.UsageTag tag) {
        try {
            String model = turn.servedModel() != null ? turn.servedModel() : properties.model();
            if (ledger != null) {
                ledger.recordAnnex(userId, workspace == null ? null : workspace.getId(), model, turn.inputTokens(),
                        turn.outputTokens(), turn.cacheReadTokens(), turn.cacheWriteTokens(), tag);
            }
            BigDecimal usd = BigDecimal.ZERO;
            if (costs != null) {
                TurnCost cost = costs.calculate(new TurnTokens(turn.fullPriceInputTokens(), turn.outputTokens(),
                        turn.cacheReadTokens(), turn.cacheWriteTokens()), model);
                usd = cost == null || cost.amountUsd() == null ? BigDecimal.ZERO : cost.amountUsd();
            }
            usage.addFastBrain(userId, usd);
        } catch (RuntimeException e) {
            log.warn("Cerveau rapide : coût non inscrit ({})", e.getClass().getSimpleName());
        }
    }

    /**
     * F-241 / SF-241-02 — le coût d'un appel annexe du cerveau rapide (rangement de l'entretien du portrait) : même
     * journal et même journée Jarvis qu'un échange. Best-effort.
     */
    public void accountAnnex(UUID userId, AgentTurn turn) {
        if (turn != null) {
            account(userId, null, turn);
        }
    }

    /** F-241 / SF-241-02 — la clé du compte pour un appel annexe (BYOK si actif, sinon celle de la plateforme). */
    public String apiKeyFor(UUID userId) {
        try {
            return byok == null ? null : byok.resolveActiveApiKey(userId).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** F-241 / SF-241-02 — l'effort configuré du cerveau rapide. */
    public String effort() {
        return properties.effort();
    }

    /** Le plafond du mois en euros et ce qui en est dépensé (lecture admin, mesure). */
    public BigDecimal monthSpentEur(UUID userId) {
        return usage.monthFastBrainCostUsd(userId).multiply(usdToEur).setScale(2, RoundingMode.HALF_UP);
    }

    private static String text(JsonNode input, String field) {
        if (input == null) {
            return "";
        }
        JsonNode node = input.get(field);
        return node == null || node.isNull() ? "" : node.asText("").strip();
    }
}
