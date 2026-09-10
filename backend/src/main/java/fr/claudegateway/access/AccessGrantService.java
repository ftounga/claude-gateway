package fr.claudegateway.access;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lecture du <b>droit offert</b> d'un utilisateur (F-62 / SF-62-01). Service en lecture seule : il
 * répond à une seule question — « ce compte a-t-il, en ce moment, un accès offert ouvert ? ».
 *
 * <h2>L'expiration est une comparaison, pas un événement</h2>
 *
 * <p>Il n'existe ici <b>aucune méthode d'expiration</b>, et aucun job planifié ailleurs. Le droit se
 * ferme parce que {@code now} a dépassé {@code grantedUntil}, un point c'est tout. C'est ce qui rend
 * vraie l'exigence de F-62 : le retour au plan précédent survient <b>même si personne ne se
 * connecte</b>, même si le pod a redémarré, même si un déploiement a sauté une fenêtre de cron. Un
 * cron peut ne pas tourner ; une comparaison ne le peut pas.</p>
 *
 * <p>Et il n'y a rien à restaurer : l'abonnement n'a jamais été modifié. Le « plan précédent » n'est
 * pas un état à remettre, c'est celui qui n'a jamais cessé d'être le vrai.</p>
 *
 * <h2>Deux horizons, et pourquoi</h2>
 *
 * <ul>
 *   <li>{@link #activeGrant(UUID)} — le <b>terme exact</b>. C'est ce que l'écran du plan affiche :
 *       il ne doit pas annoncer un accès offert une minute de plus qu'il ne dure.</li>
 *   <li>{@link #isGrantedWithGrace(UUID)} — le terme <b>plus la grâce de tour</b>. C'est ce que le
 *       contrôle d'accès à la Forge consulte : ce contrôle est rejoué à chaque requête d'un tour
 *       (relances du runner, flux SSE), et fermer la porte à la seconde du terme couperait un tour
 *       engagé en plein milieu — ce que F-62 interdit explicitement. La grâce n'offre aucun jeton :
 *       le quota n'a jamais été touché par un code, et ne l'est pas davantage pendant la grâce.</li>
 * </ul>
 *
 * <p><b>Isolation.</b> Toutes les lectures prennent le {@code userId} du contexte de sécurité et
 * filtrent dessus ({@code redeemed_by_user_id}) — jamais un paramètre client.</p>
 *
 * <p><b>Dépendances volontairement minimales</b> (repository + horloge + configuration) : c'est ce
 * qui permet au paquet {@code billing} de consommer ce service sans créer de cycle de beans.</p>
 */
@Service
public class AccessGrantService {

    private final AccessCodeRepository accessCodeRepository;
    private final AccessCodeProperties properties;
    private final Clock clock;

    public AccessGrantService(AccessCodeRepository accessCodeRepository,
            AccessCodeProperties properties, Clock clock) {
        this.accessCodeRepository = accessCodeRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Droit offert en cours de cet utilisateur, au <b>terme exact</b>.
     *
     * @param userId utilisateur du contexte de sécurité (isolation)
     * @return le droit s'il est ouvert, vide sinon (jamais consommé, ou terme dépassé)
     */
    @Transactional(readOnly = true)
    public Optional<AccessGrant> activeGrant(UUID userId) {
        return liveCode(userId, 0).map(AccessGrantService::toGrant);
    }

    /**
     * Vrai si le droit de Forge doit rester ouvert, <b>grâce de tour comprise</b>.
     *
     * @param userId utilisateur du contexte de sécurité (isolation)
     * @return {@code true} tant que {@code now < grantedUntil + grâce}
     */
    @Transactional(readOnly = true)
    public boolean isGrantedWithGrace(UUID userId) {
        return liveCode(userId, properties.graceMinutes()).isPresent();
    }

    /**
     * Dernier code consommé par cet utilisateur, s'il est encore ouvert à l'horizon demandé.
     *
     * @param userId       utilisateur du contexte de sécurité
     * @param graceMinutes minutes de tolérance ajoutées au terme (0 = terme exact)
     */
    private Optional<AccessCode> liveCode(UUID userId, int graceMinutes) {
        if (userId == null) {
            return Optional.empty();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        return accessCodeRepository.findFirstByRedeemedByUserIdOrderByGrantedUntilDesc(userId)
                .filter(code -> code.getGrantedUntil() != null)
                .filter(code -> now.isBefore(code.getGrantedUntil().plusMinutes(graceMinutes)));
    }

    private static AccessGrant toGrant(AccessCode code) {
        return new AccessGrant(
                code.getGrantedPlanCode(),
                code.getGrantedUntil(),
                code.getPreviousPlanCode(),
                code.getLabel());
    }
}
