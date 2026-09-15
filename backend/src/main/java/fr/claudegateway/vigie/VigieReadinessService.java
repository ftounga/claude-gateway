package fr.claudegateway.vigie;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.runner.RunnerLiveness;
import fr.claudegateway.runner.host.RunnerHostService;
import fr.claudegateway.vigie.dto.VigieReadinessItem;
import fr.claudegateway.vigie.dto.VigieReadinessResponse;

/**
 * <b>La mise en service guidée de la Vigie</b> (F-122 / SF-122-02) : agrège les quatre vérifications et
 * décide si l'on peut « démarrer ».
 *
 * <p>Une vérification est connue de la gateway — <b>le runner est-il connecté ?</b> (le battement fait
 * foi, {@link RunnerLiveness}). Les trois autres — Chrome managé joignable, Teams connecté, test de
 * lecture — viennent de l'<b>instantané rapporté par le runner</b> ({@link VigieReadinessStore}) : la
 * gateway orchestre et agrège, le runner constate. Sans instantané frais, ces trois-là sont
 * {@code PENDING}, jamais faussement au vert — et l'on ne démarre pas.</p>
 *
 * <p><b>Isolation</b> : la possession du poste est vérifiée ({@link RunnerHostService#requireOwned})
 * avant toute lecture ; un poste d'un autre compte est indiscernable d'un poste inexistant (404).</p>
 */
@Service
public class VigieReadinessService {

    private final RunnerHostService hostService;
    private final RunnerLiveness liveness;
    private final VigieReadinessStore store;

    public VigieReadinessService(RunnerHostService hostService, RunnerLiveness liveness,
            VigieReadinessStore store) {
        this.hostService = hostService;
        this.liveness = liveness;
        this.store = store;
    }

    /** La check-list de ce poste, pour son propriétaire. */
    @Transactional(readOnly = true)
    public VigieReadinessResponse readiness(UUID userId, UUID hostId) {
        hostService.requireOwned(userId, hostId);

        boolean runnerConnected = liveness.isAlive(userId, hostId);
        VigieCheckStatus runner = runnerConnected ? VigieCheckStatus.OK : VigieCheckStatus.KO;

        // Les trois autres viennent de l'instantané frais rapporté par le runner ; sans instantané,
        // PENDING (jamais KO faussement). RUNNER_CONNECTED reste une vérification indépendante :
        // un poste peut avoir rapporté puis s'être tu, et `canStart` exige alors quand même le runner.
        Optional<VigieReadinessSnapshot> snapshot = store.get(hostId);

        VigieCheckStatus chrome = VigieCheckStatus.PENDING;
        VigieCheckStatus teams = VigieCheckStatus.PENDING;
        VigieCheckStatus read = VigieCheckStatus.PENDING;
        boolean signInRequired = false;
        if (snapshot.isPresent()) {
            VigieReadinessSnapshot s = snapshot.get();
            chrome = of(s.chromeReachable());
            teams = of(s.teamsConnected());
            read = of(s.teamsReadTest());
            signInRequired = s.teamsSignInRequired();
        }

        List<VigieReadinessItem> checks = List.of(
                item(VigieReadinessCheck.RUNNER_CONNECTED, runner,
                        runnerConnected ? "Le runner de ce poste a battu récemment."
                                : "Le runner de ce poste ne répond pas — lancez-le."),
                item(VigieReadinessCheck.CHROME_REACHABLE, chrome,
                        detailFor(chrome, "Chrome managé lancé et joignable.",
                                "Le Chrome managé n'est pas joignable.",
                                "En attente du runner pour vérifier le Chrome managé.")),
                item(VigieReadinessCheck.TEAMS_CONNECTED, teams,
                        detailFor(teams, "Session Teams ouverte.",
                                "Teams n'est pas connecté.",
                                "En attente du runner pour vérifier la session Teams.")),
                item(VigieReadinessCheck.TEAMS_READ_TEST, read,
                        detailFor(read, "Lecture Teams de bout en bout réussie.",
                                "Le test de lecture Teams a échoué.",
                                "En attente du runner pour le test de lecture Teams.")));

        boolean canStart = runner == VigieCheckStatus.OK && chrome == VigieCheckStatus.OK
                && teams == VigieCheckStatus.OK && read == VigieCheckStatus.OK;

        return new VigieReadinessResponse(checks, canStart, signInRequired);
    }

    /** Range l'instantané rapporté par le runner pour ce poste (isolation par le jeton en amont). */
    public void report(UUID hostId, VigieReadinessSnapshot snapshot) {
        store.put(hostId, snapshot);
    }

    private static VigieCheckStatus of(boolean ok) {
        return ok ? VigieCheckStatus.OK : VigieCheckStatus.KO;
    }

    private static String detailFor(VigieCheckStatus status, String ok, String ko, String pending) {
        return switch (status) {
            case OK -> ok;
            case KO -> ko;
            case PENDING -> pending;
        };
    }

    private static VigieReadinessItem item(VigieReadinessCheck check, VigieCheckStatus status,
            String detail) {
        return new VigieReadinessItem(check.name(), status.name(), detail);
    }
}
