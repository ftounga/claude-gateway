package fr.claudegateway.access;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionService;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;

/**
 * Cycle de vie d'un code d'accès à durée limitée (F-62 / SF-62-01) : l'émettre, le consommer, en
 * rendre compte.
 *
 * <h2>Ce que ce service ne fait pas</h2>
 *
 * <p>Il n'écrit <b>rien</b> dans {@code subscriptions} et ne connaît <b>pas</b> le fournisseur de
 * paiement — il ne l'a même pas en dépendance, ce qui rend la promesse vérifiable plutôt que
 * déclarative. Un code n'est pas un paiement : il ne doit rien changer à ce qu'un client paie. Il
 * lit l'abonnement, une seule fois, en lecture seule, pour recopier dans la trace le plan auquel le
 * compte reviendra.</p>
 *
 * <p>Il ne touche pas davantage au <b>quota de jetons</b> (décision D2 du cadrage) : le code ouvre
 * un <b>droit</b>, exactement comme l'option Forge de F-40 — « l'option ouvre l'accès, elle n'ajoute
 * pas de tokens ». Relever le quota au niveau Gold pour 24 h ferait retomber l'invité, le lendemain,
 * sous le quota de son offre déjà dépassé : un client payant se retrouverait bloqué jusqu'à la fin
 * du mois pour avoir accepté un cadeau.</p>
 *
 * <h2>Isolation</h2>
 *
 * <p>La consommation et la lecture du droit prennent le {@code userId} du contexte de sécurité,
 * jamais un paramètre client. L'émission et la liste sont gardées par
 * {@link AdminService#assertAdmin()} — la garde unique du produit (F-20), jamais une seconde
 * définition de « qui est admin ».</p>
 */
@Service
public class AccessCodeService {

    private static final Logger log = LoggerFactory.getLogger(AccessCodeService.class);

    /** Le plan dont un code ouvre le droit. Gold est l'offre qui comprend la Forge (ADR-012). */
    private static final PlanCode GRANTED_PLAN = PlanCode.GOLD;

    private final AccessCodeRepository accessCodeRepository;
    private final AccessGrantService accessGrantService;
    private final SubscriptionService subscriptionService;
    private final UserRepository userRepository;
    private final AdminService adminService;
    private final CurrentUser currentUser;
    private final AccessCodeProperties properties;
    private final Clock clock;

    public AccessCodeService(
            AccessCodeRepository accessCodeRepository,
            AccessGrantService accessGrantService,
            SubscriptionService subscriptionService,
            UserRepository userRepository,
            AdminService adminService,
            CurrentUser currentUser,
            AccessCodeProperties properties,
            Clock clock) {
        this.accessCodeRepository = accessCodeRepository;
        this.accessGrantService = accessGrantService;
        this.subscriptionService = subscriptionService;
        this.userRepository = userRepository;
        this.adminService = adminService;
        this.currentUser = currentUser;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ Émission (ADMIN)

    /**
     * Émet un code d'accès. Réservé à l'ADMIN.
     *
     * <p>Le code en clair n'est renvoyé <b>qu'ici</b> : il n'est ni persisté, ni journalisé, ni
     * relisible ensuite. Seule son empreinte SHA-256 subsiste.</p>
     *
     * @param label         libellé permettant de reconnaître le code (obligatoire, déjà validé)
     * @param assignedEmail e-mail du destinataire pour un code nominatif, ou {@code null}/vide
     * @return le code émis, code en clair compris — à montrer une fois
     */
    @Transactional
    public IssuedAccessCode issue(String label, String assignedEmail) {
        adminService.assertAdmin();
        UUID adminId = currentUser.requireId();

        String clearCode = AccessCodeSecret.generate();
        OffsetDateTime now = OffsetDateTime.now(clock);

        AccessCode code = accessCodeRepository.save(AccessCode.builder()
                .codeHash(AccessCodeSecret.hash(clearCode))
                .label(label.trim())
                .assignedEmail(normalizeEmail(assignedEmail))
                .grantedPlanCode(GRANTED_PLAN)
                // Durée et validité sont FIGÉES ici : changer la configuration demain ne doit pas
                // altérer un code déjà remis à quelqu'un.
                .durationHours(properties.durationHours())
                .validUntil(now.plusDays(properties.validityDays()))
                .createdByUserId(adminId)
                .build());

        // On trace l'identifiant de la ligne, jamais le code : un log est lu par plus de monde qu'une base.
        log.info("Code d'accès émis (id={}, durée={} h, nominatif={})",
                code.getId(), code.getDurationHours(), code.getAssignedEmail() != null);

        return new IssuedAccessCode(clearCode, describe(code, now, null));
    }

    /**
     * Tous les codes émis, du plus récent au plus ancien, avec leur trace de consommation. Réservé à
     * l'ADMIN. Ne contient <b>jamais</b> de code en clair.
     *
     * @return la liste, éventuellement vide
     */
    @Transactional(readOnly = true)
    public List<AccessCodeView> list() {
        adminService.assertAdmin();
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<AccessCode> codes = accessCodeRepository.findAllByOrderByCreatedAtDesc();
        Map<UUID, String> emails = resolveRedeemerEmails(codes);
        return codes.stream()
                .map(code -> describe(code, now, emails.get(code.getRedeemedByUserId())))
                .toList();
    }

    // ------------------------------------------------------------------ Consommation (utilisateur)

    /**
     * Consomme un code au profit de l'utilisateur courant et ouvre son droit.
     *
     * @param userId    utilisateur du contexte de sécurité (isolation — jamais un paramètre client)
     * @param userEmail e-mail du contexte de sécurité, pour les codes nominatifs
     * @param rawCode   code saisi, sous n'importe quelle casse et avec n'importe quels espaces
     * @return le droit ouvert
     * @throws AccessCodeInvalidException         code inconnu (404)
     * @throws AccessCodeAlreadyUsedException     code déjà consommé (409)
     * @throws AccessCodeExpiredException         code périmé avant d'avoir servi (409)
     * @throws AccessCodeNotForAccountException   code nominatif visant un autre compte (403)
     * @throws AccessCodeAlreadyGrantedException  un droit est déjà en cours (409 — pas de cumul)
     */
    @Transactional
    public AccessGrant redeem(UUID userId, String userEmail, String rawCode) {
        String normalized = AccessCodeSecret.normalize(rawCode);
        if (normalized.isEmpty()) {
            throw new AccessCodeInvalidException();
        }

        AccessCode code = accessCodeRepository.findByCodeHash(AccessCodeSecret.hash(normalized))
                .orElseThrow(AccessCodeInvalidException::new);

        OffsetDateTime now = OffsetDateTime.now(clock);
        if (code.getRedeemedAt() != null) {
            throw new AccessCodeAlreadyUsedException();
        }
        if (!now.isBefore(code.getValidUntil())) {
            throw new AccessCodeExpiredException();
        }
        if (code.getAssignedEmail() != null
                && !code.getAssignedEmail().equalsIgnoreCase(normalizeEmail(userEmail))) {
            throw new AccessCodeNotForAccountException();
        }
        // Pas de cumul (hors périmètre F-62) : deux codes enchaînés produiraient une durée que
        // personne n'a décidée, et une trace où l'on ne saurait plus lequel gouverne.
        if (accessGrantService.activeGrant(userId).isPresent()) {
            throw new AccessCodeAlreadyGrantedException();
        }

        // Lecture SEULE de l'abonnement : on recopie le plan du moment dans la trace. Rien n'y est
        // écrit — c'est précisément pourquoi il n'y aura rien à restaurer au terme.
        Subscription subscription = subscriptionService.getOrCreateForUser(userId);
        OffsetDateTime grantedUntil = now.plusHours(code.getDurationHours());

        int consumed = accessCodeRepository.consume(
                code.getId(), userId, now, grantedUntil,
                subscription.getPlanCode(), subscription.getStatus());
        if (consumed == 0) {
            // La condition « pas encore consommé » vit dans le WHERE : zéro ligne signifie qu'une
            // autre requête vient de prendre le code, pas qu'une erreur technique est survenue.
            throw new AccessCodeAlreadyUsedException();
        }

        log.info("Code d'accès consommé (id={}, utilisateur={}, terme={})",
                code.getId(), userId, grantedUntil);

        return new AccessGrant(
                code.getGrantedPlanCode(), grantedUntil, subscription.getPlanCode(), code.getLabel());
    }

    /**
     * Droit offert en cours de l'utilisateur courant, s'il y en a un.
     *
     * @param userId utilisateur du contexte de sécurité (isolation)
     * @return le droit, ou vide
     */
    @Transactional(readOnly = true)
    public Optional<AccessGrant> currentGrant(UUID userId) {
        return accessGrantService.activeGrant(userId);
    }

    // ------------------------------------------------------------------ Interne

    /** Résout en un seul aller-retour les e-mails des comptes ayant consommé un code. */
    private Map<UUID, String> resolveRedeemerEmails(List<AccessCode> codes) {
        List<UUID> ids = codes.stream()
                .map(AccessCode::getRedeemedByUserId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, String> emails = new HashMap<>();
        if (ids.isEmpty()) {
            return emails;
        }
        for (User user : userRepository.findAllById(ids)) {
            emails.put(user.getId(), user.getEmail());
        }
        return emails;
    }

    /** Projette une ligne en vue d'administration, l'état étant <b>dérivé</b> des dates. */
    private AccessCodeView describe(AccessCode code, OffsetDateTime now, String redeemedByEmail) {
        return new AccessCodeView(
                code.getId(),
                code.getLabel(),
                code.getAssignedEmail(),
                code.getGrantedPlanCode(),
                code.getDurationHours(),
                code.getValidUntil(),
                stateOf(code, now),
                redeemedByEmail,
                code.getRedeemedAt(),
                code.getGrantedUntil(),
                code.getPreviousPlanCode(),
                code.getCreatedAt());
    }

    /** L'état d'un code se lit dans ses dates ; le stocker exigerait un job pour le tenir à jour. */
    private static AccessCodeState stateOf(AccessCode code, OffsetDateTime now) {
        if (code.getRedeemedAt() == null) {
            return now.isBefore(code.getValidUntil()) ? AccessCodeState.ISSUED : AccessCodeState.EXPIRED;
        }
        return now.isBefore(code.getGrantedUntil()) ? AccessCodeState.ACTIVE : AccessCodeState.ENDED;
    }

    private static String normalizeEmail(String email) {
        if (!StringUtils.hasText(email)) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Un code fraîchement émis : le clair, qui ne sera plus jamais lisible, et sa description.
     *
     * @param code code en clair — à montrer une fois, jamais à stocker
     * @param view description persistée du code
     */
    public record IssuedAccessCode(String code, AccessCodeView view) {
    }

    /**
     * Vue d'administration d'un code. Ne porte <b>jamais</b> le code en clair.
     *
     * @param id               identifiant de la ligne
     * @param label            libellé donné à l'émission
     * @param assignedEmail    destinataire d'un code nominatif, ou {@code null}
     * @param grantedPlanCode  plan dont le droit est offert
     * @param durationHours    durée du droit, figée à l'émission
     * @param validUntil       date au-delà de laquelle un code non consommé ne vaut plus rien
     * @param state            état dérivé des dates
     * @param redeemedByEmail  e-mail du compte qui l'a consommé, ou {@code null}
     * @param redeemedAt       instant de la consommation, ou {@code null}
     * @param grantedUntil     terme du droit, ou {@code null}
     * @param previousPlanCode plan auquel ce compte revient au terme, ou {@code null}
     * @param createdAt        instant de l'émission
     */
    public record AccessCodeView(
            UUID id,
            String label,
            String assignedEmail,
            PlanCode grantedPlanCode,
            int durationHours,
            OffsetDateTime validUntil,
            AccessCodeState state,
            String redeemedByEmail,
            OffsetDateTime redeemedAt,
            OffsetDateTime grantedUntil,
            PlanCode previousPlanCode,
            OffsetDateTime createdAt) {
    }
}
