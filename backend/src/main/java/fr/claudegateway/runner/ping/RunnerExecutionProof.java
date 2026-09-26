package fr.claudegateway.runner.ping;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;

/**
 * <b>La preuve qu'un poste exécute</b> (F-161 / SF-161-04) — ce que le battement ne dit pas.
 *
 * <p>Un battement prouve qu'un processus <i>émet</i> ; il ne prouve pas qu'il <i>exécute</i>. La
 * seule chose qui le prouve est une <b>réponse du runner à un appel d'outil</b> : la trame a été
 * reçue, le projet résolu, un worker l'a traitée et a rendu sa trame terminale. Cette classe retient
 * <b>quand</b> cela s'est produit pour la dernière fois, poste par poste, afin que le ping de
 * SF-161-04 reste <b>conditionnel</b> — c'est la condition posée par le cadrage §6 : « seulement si
 * le dernier appel réussi date de plus de N secondes ».</p>
 *
 * <p><b>En mémoire, et volontairement</b> : « ce poste a exécuté il y a douze secondes » est un fait
 * volatil, pas une donnée du produit. Le persister imposerait une écriture en base à <b>chaque</b>
 * appel d'outil pour économiser un aller-retour toutes les deux minutes. Le prix d'un pod qui vient
 * de démarrer est <b>un</b> ping de plus, pas une erreur.</p>
 *
 * <p><b>Aucune donnée personnelle</b> : un identifiant de poste et un instant. Le poste appartient
 * déjà à un compte unique, et rien ici n'est lu pour décider d'un accès — l'isolation reste portée
 * par {@code requireOwned} en amont.</p>
 */
@Component
public class RunnerExecutionProof {

    /**
     * Au-delà, la carte est purgée de ses entrées périmées. Une borne, pas un réglage : elle ne
     * protège que d'une fuite lente sur un parc qui tournerait des mois sans redémarrage.
     */
    private static final int PURGE_ABOVE = 1_000;

    /** Durée au-delà de laquelle une entrée ne sert plus à personne : toute fenêtre utile est plus courte. */
    private static final Duration FORGET_AFTER = Duration.ofHours(1);

    private final Map<UUID, Long> provedAtMillis = new ConcurrentHashMap<>();

    /**
     * Cette issue prouve-t-elle que le <b>runner</b> a exécuté ?
     *
     * <p>Oui dès que la réponse vient de la machine — <b>y compris une erreur d'outil</b>
     * ({@code not_found}, {@code io_error}, {@code too_large}…) : ce qui est prouvé n'est pas que
     * l'outil a réussi, c'est que la chaîne d'exécution a fonctionné de bout en bout.</p>
     *
     * <p>Non pour les codes que la <b>gateway</b> produit elle-même (contrat §4, seconde moitié) :
     * ils décrivent ce que la gateway constate — personne au bout du fil, mauvais nœud, silence,
     * réponse illisible — et aucun d'eux n'implique qu'une trame ait été traitée.</p>
     */
    public static boolean provesExecution(RunnerCallResult result) {
        if (result == null) {
            return false;
        }
        if (result.ok()) {
            return true;
        }
        return !isBackendProduced(result.errorCode());
    }

    /** Vrai pour un code que la gateway émet sans que le runner ait rien traité. */
    public static boolean isBackendProduced(String errorCode) {
        if (errorCode == null) {
            return true; // une issue en échec sans code : on ne lui accorde rien
        }
        return switch (errorCode) {
            case RunnerErrorCodes.RUNNER_UNAVAILABLE,
                 RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE,
                 RunnerErrorCodes.RUNNER_TIMEOUT,
                 RunnerErrorCodes.RUNNER_PROTOCOL_ERROR,
                 RunnerErrorCodes.INVALID_INPUT,
                 // `unsupported_tool` est ambigu — la gateway le produit quand la capacité n'est pas
                 // déclarée, le runner quand il ne connaît pas l'outil. Dans le doute, il ne prouve
                 // rien : au pire un ping de plus, jamais un poste bloqué déclaré sain.
                 RunnerErrorCodes.UNSUPPORTED_TOOL -> true;
            default -> false;
        };
    }

    /** Retient que ce poste vient de répondre. Sans effet si l'issue ne prouve rien. */
    public void note(UUID hostId, RunnerCallResult result) {
        if (hostId != null && provesExecution(result)) {
            noteProved(hostId);
        }
    }

    /** Retient que ce poste vient de prouver son exécution, à l'instant. */
    public void noteProved(UUID hostId) {
        if (hostId == null) {
            return;
        }
        provedAtMillis.put(hostId, System.currentTimeMillis());
        purgeIfCrowded();
    }

    /** Ce poste a-t-il prouvé son exécution depuis moins de {@code window} ? Jamais vu ⇒ non. */
    public boolean provedWithin(UUID hostId, Duration window) {
        if (hostId == null || window == null) {
            return false;
        }
        Long provedAt = provedAtMillis.get(hostId);
        return provedAt != null
                && System.currentTimeMillis() - provedAt < Math.max(0L, window.toMillis());
    }

    /** Oublie ce poste — utilisé par les tests, et sans effet sur un poste inconnu. */
    public void forget(UUID hostId) {
        if (hostId != null) {
            provedAtMillis.remove(hostId);
        }
    }

    private void purgeIfCrowded() {
        if (provedAtMillis.size() <= PURGE_ABOVE) {
            return;
        }
        long floor = System.currentTimeMillis() - FORGET_AFTER.toMillis();
        Iterator<Map.Entry<UUID, Long>> entries = provedAtMillis.entrySet().iterator();
        while (entries.hasNext()) {
            if (entries.next().getValue() < floor) {
                entries.remove();
            }
        }
    }
}
