package fr.claudegateway.quota;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionService;
import fr.claudegateway.byok.ByokKeyService;

/**
 * Cœur du contrôle de quota (F-10). Vérifie l'entitlement <b>avant</b> chaque appel au fournisseur
 * et enregistre la consommation <b>après</b>. Point d'isolation : toutes les opérations prennent le
 * {@code userId} du contexte de sécurité (jamais un paramètre client) et filtrent dessus.
 *
 * <p>Période = mois calendaire UTC : le compteur est remis à zéro (nouvelle ligne) à chaque mois.
 * Le pré-contrôle ne réserve pas de tokens (le coût d'un appel est inconnu à l'avance) : il bloque
 * dès que le cumul de la période a atteint le quota.</p>
 *
 * <p><b>Exception BYOK (F-41)</b> : sur une offre servie par la clé du client, la plateforme
 * n'alloue aucun jeton et n'en contrôle donc aucun. Le pré-contrôle passe sans bloquer — la limite
 * réelle est celle du compte fournisseur du client. La consommation reste <b>enregistrée</b>
 * (observabilité et rapport d'usage F-16), elle cesse simplement d'être opposable. En contrepartie,
 * le pré-contrôle exige alors que cette clé <b>existe</b> : sans elle, l'appel repartirait sur la
 * clé de la plateforme (SF-41-02).</p>
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private final UsageCounterRepository usageCounterRepository;
    private final SubscriptionService subscriptionService;
    private final EntitlementService entitlementService;
    private final ByokKeyService byokKeyService;
    private final QuotaWindowService quotaWindowService;
    private final QuotaAlertService quotaAlertService;
    private final UsageLedgerService usageLedgerService;
    private final BilledTokensCalculator billedTokensCalculator;
    private final QuotaProperties quotaProperties;
    private final Clock clock;

    public QuotaService(
            UsageCounterRepository usageCounterRepository,
            SubscriptionService subscriptionService,
            EntitlementService entitlementService,
            ByokKeyService byokKeyService,
            QuotaWindowService quotaWindowService,
            QuotaAlertService quotaAlertService,
            UsageLedgerService usageLedgerService,
            BilledTokensCalculator billedTokensCalculator,
            QuotaProperties quotaProperties,
            Clock clock) {
        this.usageCounterRepository = usageCounterRepository;
        this.subscriptionService = subscriptionService;
        this.entitlementService = entitlementService;
        this.byokKeyService = byokKeyService;
        this.quotaWindowService = quotaWindowService;
        this.quotaAlertService = quotaAlertService;
        this.usageLedgerService = usageLedgerService;
        this.billedTokensCalculator = billedTokensCalculator;
        this.quotaProperties = quotaProperties;
        this.clock = clock;
    }

    /**
     * Vérifie que l'utilisateur peut encore consommer sur la période courante.
     *
     * @param userId utilisateur authentifié (contexte de sécurité)
     * @throws QuotaExceededException   si le cumul de la période a atteint le quota de l'entitlement
     *                                  (jamais levée sur une offre BYOK en cours : aucun quota plateforme)
     * @throws fr.claudegateway.byok.ByokKeyRequiredException si l'offre est BYOK et qu'aucune clé
     *                                  active n'est enregistrée (F-41 / SF-41-02)
     */
    @Transactional(readOnly = true)
    public void assertWithinQuota(UUID userId) {
        Subscription subscription = subscriptionService.getOrCreateForUser(userId);
        if (entitlementService.isCustomerKeyBilled(subscription)) {
            // Offre BYOK (F-41) : les jetons sont sur le compte fournisseur du client, la plateforme
            // ne lui en alloue aucun. Son quota vaut donc 0 — mais ce zéro-là n'est PAS un impayé :
            // le laisser tomber dans le test `used >= quota` bloquerait un client parfaitement à jour
            // dès son premier appel. Il n'y a rien à contrôler ici : la limite est chez le fournisseur.
            //
            // En revanche il y a une condition à poser (SF-41-02) : cette clé doit exister. Sans elle,
            // l'appel repartirait sur la clé de la PLATEFORME — c'est ce que fait `.orElse(null)` chez
            // tous les appelants — et un client qui ne paie aucun jeton consommerait ceux de la
            // gateway. Le refus est posé ici, sur le pré-vol commun aux quatre chemins servis, plutôt
            // qu'aux trois endroits où la clé est résolue : un chemin ajouté demain hérite du
            // garde-fou au lieu de l'oublier.
            byokKeyService.requireActiveApiKey(userId);
            return;
        }
        // Quota effectif : l'allocation du plan, la part apportée par les postes supplémentaires
        // (F-65) et les jetons rachetés (F-21). Ce que le pré-vol oppose doit être exactement ce que
        // la jauge annonce — d'où le même calcul des deux côtés.
        //
        // Le tout s'oppose sur la FENÊTRE de l'abonnement (F-66) et non sur le mois : pour un
        // abonnement payant les deux coïncident, pour un essai en cours la fenêtre couvre tout
        // l'essai. Sans cela, un essai à cheval sur un 1er du mois disposait de deux fois son
        // plafond, le compteur mensuel repartant de zéro au milieu de l'essai.
        QuotaWindow window = quotaWindowService.resolve(subscription);
        long quota = entitlementService.resolveEffectiveMonthlyTokenQuota(subscription)
                + currentPeriodBonus(userId) + window.carryOverBonusTokens();
        long used = currentPeriodUsage(userId) + window.carryOverBilledTokens();
        if (used >= quota) {
            throw new QuotaExceededException(
                    "Quota de consommation atteint pour la période courante.");
        }
    }

    /**
     * Ajoute la consommation d'un appel au compteur de la période courante de l'utilisateur.
     * Idempotence structurelle : une seule ligne par ({@code user_id}, période) grâce à la contrainte
     * d'unicité ; une création concurrente est rattrapée par relecture.
     *
     * @param userId       utilisateur authentifié
     * @param inputTokens  tokens d'entrée rapportés par le fournisseur (négatif ignoré → 0)
     * @param outputTokens tokens de sortie rapportés par le fournisseur (négatif ignoré → 0)
     */
    @Transactional
    public void recordUsage(UUID userId, int inputTokens, int outputTokens) {
        recordUsage(userId, inputTokens, outputTokens, null, null);
    }

    /**
     * Même décompte, en disant <b>pour quel projet</b> et <b>sous quel poste</b> (F-61 / SF-61-01).
     *
     * <p>Le compteur de période est incrémenté exactement comme avant — c'est lui qui fait foi pour
     * le quota et la facturation. S'y ajoute une ligne du <b>journal par tour</b>, qui seule sait
     * répondre à « combien ce client me coûte-t-il ». Les deux écritures sont indépendantes : le
     * journal ne peut ni bloquer, ni annuler le décompte (voir {@link UsageLedgerService}).</p>
     *
     * <p>La ventilation n'est <b>pas</b> lue depuis {@code workspaces.agent_*_tokens} : ces colonnes
     * sont remises à zéro à chaque session, et les agréger ferait rétrécir les totaux.</p>
     *
     * @param workspaceId projet du tour, ou {@code null} pour un tour hors projet ({@code /chat},
     *                    {@code /ask})
     * @param hostId      poste du projet <b>au moment du tour</b>, ou {@code null}
     */
    @Transactional
    public void recordUsage(UUID userId, int inputTokens, int outputTokens,
            UUID workspaceId, UUID hostId) {
        recordUsage(userId, TurnTokens.of(inputTokens, outputTokens), null, workspaceId, hostId);
    }

    /**
     * Décompte d'un tour <b>au coût réel</b> (F-63) : chaque nature de token pèse le sien.
     *
     * <p>Deux compteurs sont incrémentés, et ils ne disent pas la même chose :</p>
     * <ul>
     *   <li>les <b>volumes</b> ({@code input_tokens}, {@code output_tokens}) — ce que le fournisseur
     *       a traité, cache compris, exactement comme avant F-63. Le rapport d'usage (F-16), la
     *       consommation par client et la console d'administration (F-61) en vivent ;</li>
     *   <li>les <b>tokens facturés</b> ({@code billed_tokens}) — ce que le quota oppose. Un token de
     *       sortie coûte cinq fois un token d'entrée chez le fournisseur, une lecture de cache un
     *       dixième : les traiter à l'identique faisait dépendre la marge du style d'usage du
     *       client, c'est-à-dire de rien qu'on maîtrise.</li>
     * </ul>
     *
     * <p>Le changement est un changement de <b>calcul</b>, pas de <b>tarif</b> : aucun prix, aucun
     * quota ne bouge — seuls les tarifs de conversion, tous en configuration, disent ce que pèse
     * chaque nature.</p>
     *
     * @param userId          utilisateur authentifié (contexte de sécurité)
     * @param tokens          tokens du tour, par nature
     * @param providerCostUsd coût réel du tour rapporté par le fournisseur, en dollars, ou
     *                        {@code null} s'il ne le rapporte pas — le coût est alors calculé à
     *                        partir des tokens, aux tarifs de configuration. Quand il existe, il
     *                        fait foi : il sait des choses que les tokens ignorent (modèle servi,
     *                        recherches web, temps de bac à sable)
     * @param workspaceId     projet du tour, ou {@code null} pour un tour hors projet
     * @param hostId          poste du projet <b>au moment du tour</b>, ou {@code null}
     */
    @Transactional
    public void recordUsage(UUID userId, TurnTokens tokens, BigDecimal providerCostUsd,
            UUID workspaceId, UUID hostId) {
        if (tokens.isEmpty() && (providerCostUsd == null || providerCostUsd.signum() <= 0)) {
            return;
        }
        long input = tokens.processedInputTokens();
        long output = tokens.outputTokens();
        long billed = providerCostUsd == null
                ? billedTokensCalculator.billedTokens(tokens)
                : billedTokensCalculator.billedTokensFromCost(providerCostUsd);
        LocalDate periodStart = currentPeriodStart();
        UsageCounter counter = usageCounterRepository.findByUserIdAndPeriodStart(userId, periodStart)
                .orElseGet(() -> createCounter(userId, periodStart));
        counter.setInputTokens(counter.getInputTokens() + input);
        counter.setOutputTokens(counter.getOutputTokens() + output);
        counter.setBilledTokens(counter.getBilledTokens() + billed);
        raiseQuotaAlertIfNeeded(userId, counter);
        usageCounterRepository.save(counter);
        // Après le compteur, et jamais avant : si quelque chose doit manquer, c'est le relevé
        // d'attribution, pas la consommation opposable au quota. Le journal enregistre des VOLUMES
        // (il sert à refacturer un client), pas le décompte facturé.
        usageLedgerService.recordTurn(userId, workspaceId, hostId, input, output);
    }

    /**
     * Juge le seuil d'alerte de consommation (F-42) sur le compteur fraîchement incrémenté, avant sa
     * sauvegarde : la marque éventuelle part dans la <b>même</b> écriture que la consommation.
     *
     * <p>Encadré volontairement : une alerte manquée est un défaut d'information, une consommation
     * perdue ou un appel en échec seraient un défaut de facturation et d'expérience. L'alerte
     * informe, elle ne bloque jamais — l'ordre de gravité est explicite ici.</p>
     */
    private void raiseQuotaAlertIfNeeded(UUID userId, UsageCounter counter) {
        try {
            quotaAlertService.evaluateAfterUsage(userId, counter);
        } catch (RuntimeException alertFailure) {
            log.warn("Évaluation du seuil d'alerte de quota impossible pour l'utilisateur {} :"
                    + " la consommation est enregistrée, l'alerte est ignorée.", userId, alertFailure);
        }
    }

    /**
     * Instantané de consommation de l'utilisateur pour la période courante (pour {@code GET /usage}).
     *
     * @param userId utilisateur authentifié
     * @return consommation, quota, restant et bornes de la période
     */
    @Transactional(readOnly = true)
    public UsageSnapshot currentUsage(UUID userId) {
        Subscription subscription = subscriptionService.getOrCreateForUser(userId);
        // Même fenêtre que le pré-vol (F-66) : la jauge doit annoncer ce que le quota oppose, sans
        // quoi un essai à cheval sur un 1er du mois s'afficherait vide la veille d'être bloqué.
        QuotaWindow window = quotaWindowService.resolve(subscription);
        long quota = entitlementService.resolveEffectiveMonthlyTokenQuota(subscription)
                + currentPeriodBonus(userId) + window.carryOverBonusTokens();
        long used = currentPeriodUsage(userId) + window.carryOverBilledTokens();
        long remaining = Math.max(0, quota - used);
        long processed = currentPeriodProcessedTokens(userId) + window.carryOverProcessedTokens();
        return new UsageSnapshot(used, quota, remaining, processed, window.displayStart(),
                window.displayEnd());
    }

    /**
     * Crédite des tokens rachetés (top-up, F-21) sur la période courante de l'utilisateur : ils
     * s'ajoutent au quota d'abonnement. Appelé depuis le traitement d'un paiement de pack de tokens.
     *
     * @param userId utilisateur bénéficiaire
     * @param tokens nombre de tokens à créditer (négatif/zéro ignoré)
     */
    @Transactional
    public void creditBonusTokens(UUID userId, long tokens) {
        if (tokens <= 0) {
            return;
        }
        LocalDate periodStart = currentPeriodStart();
        UsageCounter counter = usageCounterRepository.findByUserIdAndPeriodStart(userId, periodStart)
                .orElseGet(() -> createCounter(userId, periodStart));
        counter.setBonusTokens(counter.getBonusTokens() + tokens);
        usageCounterRepository.save(counter);
    }

    /**
     * Vérifie que l'utilisateur n'a pas atteint le plafond de temps de bac à sable Managed Agents de
     * la période courante (F-28 / SF-28-12). Pré-contrôle appelé <b>avant</b> toute création de
     * session : un refus n'engage donc aucun coût runtime.
     *
     * @param userId utilisateur authentifié (contexte de sécurité)
     * @throws SandboxLimitExceededException si le cumul de la période a atteint le plafond configuré
     */
    @Transactional(readOnly = true)
    public void assertWithinSandboxLimit(UUID userId) {
        long limit = quotaProperties.maxSandboxSeconds();
        long used = currentPeriodSandboxSeconds(userId);
        if (used >= limit) {
            throw new SandboxLimitExceededException(
                    "Plafond de temps de bac à sable atteint pour la période courante.");
        }
    }

    /**
     * Ajoute du temps de bac à sable (secondes {@code active_seconds} d'une session Managed Agents)
     * au compteur de la période courante de l'utilisateur (F-28 / SF-28-12). Même patron d'upsert
     * que {@link #recordUsage} : une seule ligne par ({@code user_id}, période).
     *
     * @param userId  utilisateur authentifié
     * @param seconds secondes de bac à sable à cumuler (négatif/zéro ignoré → aucune écriture)
     */
    @Transactional
    public void recordSandboxSeconds(UUID userId, long seconds) {
        if (seconds <= 0) {
            return;
        }
        LocalDate periodStart = currentPeriodStart();
        UsageCounter counter = usageCounterRepository.findByUserIdAndPeriodStart(userId, periodStart)
                .orElseGet(() -> createCounter(userId, periodStart));
        counter.setSandboxSeconds(counter.getSandboxSeconds() + seconds);
        usageCounterRepository.save(counter);
    }

    /**
     * Temps de bac à sable cumulé de l'utilisateur sur la période courante (0 si aucune ligne).
     *
     * @param userId utilisateur authentifié
     * @return secondes de bac à sable cumulées de la période
     */
    @Transactional(readOnly = true)
    public long currentPeriodSandboxSeconds(UUID userId) {
        return usageCounterRepository.findByUserIdAndPeriodStart(userId, currentPeriodStart())
                .map(UsageCounter::getSandboxSeconds)
                .orElse(0L);
    }

    private long currentPeriodBonus(UUID userId) {
        return usageCounterRepository.findByUserIdAndPeriodStart(userId, currentPeriodStart())
                .map(UsageCounter::getBonusTokens)
                .orElse(0L);
    }

    /**
     * Consommation <b>opposable au quota</b> de la période : les tokens facturés, où chaque nature
     * pèse son coût (F-63). Ce n'est pas le volume traité — celui-là est
     * {@link #currentPeriodProcessedTokens(UUID)}.
     */
    private long currentPeriodUsage(UUID userId) {
        return usageCounterRepository.findByUserIdAndPeriodStart(userId, currentPeriodStart())
                .map(UsageCounter::getBilledTokens)
                .orElse(0L);
    }

    /** Volume de tokens <b>traités</b> sur la période (entrée + sortie), à titre d'information. */
    private long currentPeriodProcessedTokens(UUID userId) {
        return usageCounterRepository.findByUserIdAndPeriodStart(userId, currentPeriodStart())
                .map(UsageCounter::totalTokens)
                .orElse(0L);
    }

    private UsageCounter createCounter(UUID userId, LocalDate periodStart) {
        UsageCounter counter = UsageCounter.builder()
                .userId(userId)
                .periodStart(periodStart)
                .inputTokens(0L)
                .outputTokens(0L)
                .build();
        try {
            return usageCounterRepository.save(counter);
        } catch (DataIntegrityViolationException concurrentCreation) {
            // Une écriture concurrente a créé la ligne de période en premier : on la relit.
            return usageCounterRepository.findByUserIdAndPeriodStart(userId, periodStart)
                    .orElseThrow(() -> concurrentCreation);
        }
    }

    /** Premier jour du mois calendaire courant (UTC). */
    private LocalDate currentPeriodStart() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
    }
}
