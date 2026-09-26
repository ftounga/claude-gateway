package fr.claudegateway.runner.ping;

import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.relay.RunnerCallRouter;

/**
 * <b>Le ping conditionnel qui exécute vraiment</b> (F-161 / SF-161-04).
 *
 * <p><b>Le trou qu'il ferme.</b> La porte de SF-161-01 lit deux savoirs : le <b>battement</b> et les
 * <b>capacités déclarées</b>. Ni l'un ni l'autre ne prouve que le runner <i>exécutera</i> : un
 * battement dit qu'un processus émet, une déclaration dit ce qu'il savait faire <i>à la
 * connexion</i>. Un pool de workers bloqué, un dossier de projet disparu, un canal remplacé —
 * et le tour est payé entier pour apprendre ce que la gateway pouvait constater gratuitement. C'est
 * le seul cas que ni l'entrée (01) ni l'arrêt net (02) ne couvrent.</p>
 *
 * <p><b>Conditionnel, et c'est la condition du cadrage.</b> Le §6 de F-161 écarte explicitement le
 * ping <i>systématique</i> — « un aller-retour à chaque tour ajoute de la latence à tous » — et ne
 * garde en réserve que sa forme conditionnelle : « seulement si le dernier appel réussi date de plus
 * de N secondes ». C'est exactement ce qui est implémenté ici : tant que
 * {@link RunnerExecutionProof} porte une preuve d'exécution plus fraîche que
 * {@code app.runner.ping.after}, <b>aucune trame ne part</b>. Une session active ne paie donc la
 * latence du ping qu'à son <b>premier</b> tour.</p>
 *
 * <p><b>Pourquoi {@code read_file} sur un chemin qui n'existe pas.</b> Il relève de la capacité
 * {@code files}, que <b>tout</b> runner déclare : le ping protège donc le parc <b>déjà installé</b>,
 * sans qu'un poste ait à se mettre à jour. Il traverse toute la chaîne — réception de la trame,
 * résolution du projet, soumission au pool de workers, trame terminale — et coûte un {@code stat}
 * qui échoue. L'aléa du nom garantit l'absence du fichier : rien n'est lu, rien n'est écrit, et la
 * réponse attendue est un {@code not_found} immédiat. Un parcours d'arborescence
 * ({@code list_files}, {@code grep}, {@code glob}) aurait été un coût, pas une sonde.</p>
 *
 * <p><b>Le doute ne ferme rien.</b> Seuls le silence et l'absence de canal ferment la porte. Une
 * réponse illisible, un outil refusé, une erreur inattendue du routeur laissent passer le tour —
 * même doctrine que la porte, qui ne bloque jamais sur une ignorance.</p>
 */
@Component
public class RunnerPing {

    private static final Logger log = LoggerFactory.getLogger(RunnerPing.class);

    /** Outil de la sonde : le moins cher des outils fichiers, et déclaré par tous les runners. */
    static final String PROBE_TOOL = "read_file";

    /** Préfixe du chemin sondé ; le suffixe aléatoire garantit qu'aucun fichier ne sera lu. */
    static final String PROBE_PATH_PREFIX = ".cg-ping-";

    private final RunnerCallRouter router;
    private final RunnerExecutionProof proof;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Duration after;
    private final long timeoutMs;

    public RunnerPing(RunnerCallRouter router, RunnerExecutionProof proof, ObjectMapper objectMapper,
            @Value("${app.runner.ping.enabled:true}") boolean enabled,
            @Value("${app.runner.ping.after:PT2M}") Duration after,
            @Value("${app.runner.ping.timeout-ms:1200}") long timeoutMs) {
        this.router = router;
        this.proof = proof;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.after = after == null ? Duration.ofMinutes(2) : after;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 1200L;
    }

    /**
     * Le poste porte-t-il vraiment ce tour ?
     *
     * @param target   poste et projet du tour, construits <b>après</b> {@code requireOwned} —
     *                 jamais depuis un identifiant venu du client
     * @param hostName nom lisible du poste, pour que le refus soit concret
     * @return {@link RunnerPingVerdict#mute} seulement sur un silence ou un canal disparu
     */
    public RunnerPingVerdict probe(RunnerTarget target, String hostName) {
        if (!enabled || target == null || target.hostId() == null) {
            return RunnerPingVerdict.passes();
        }
        UUID hostId = target.hostId();
        if (proof.provedWithin(hostId, after)) {
            // Ce poste vient d'exécuter : rien à prouver, et surtout aucune latence à faire payer.
            return RunnerPingVerdict.passes();
        }

        RunnerCallResult result;
        try {
            result = router.call(target, probeCallId(), PROBE_TOOL, probeInput(), timeoutMs);
        } catch (RuntimeException ex) {
            // Le doute ne ferme rien : une sonde qui échoue par notre faute ne dit rien du poste.
            log.debug("Ping du poste impossible (poste={}) : le tour passe", hostId);
            return RunnerPingVerdict.passes();
        }

        if (RunnerExecutionProof.provesExecution(result)) {
            proof.noteProved(hostId);
            return RunnerPingVerdict.passes();
        }
        if (isMute(result)) {
            log.info("Ping sans réponse (poste={}, code={}) : le tour est refusé avant tout jeton",
                    hostId, result.errorCode());
            return RunnerPingVerdict.mute(muteReason(hostName, result.errorCode()));
        }
        return RunnerPingVerdict.passes();
    }

    /** Silence avéré ou canal disparu — les deux seuls constats qui autorisent un refus. */
    private static boolean isMute(RunnerCallResult result) {
        String code = result == null ? null : result.errorCode();
        return RunnerErrorCodes.RUNNER_TIMEOUT.equals(code)
                || RunnerErrorCodes.RUNNER_UNAVAILABLE.equals(code)
                || RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE.equals(code);
    }

    /** Le message dit <b>quoi faire</b> et que <b>rien n'a été dépensé</b> — comme les deux autres refus. */
    private static String muteReason(String hostName, String code) {
        String name = hostName == null || hostName.isBlank() ? "ce poste" : hostName;
        if (RunnerErrorCodes.RUNNER_TIMEOUT.equals(code)) {
            return "Le runner du poste « " + name + " » donne signe de vie mais n'exécute plus rien "
                    + "(sonde sans réponse). Relance-le, puis renvoie ta demande — rien n'a été "
                    + "dépensé.";
        }
        return "Le runner du poste « " + name + " » n'est plus joignable (sa liaison a disparu). "
                + "Relance-le, puis renvoie ta demande — rien n'a été dépensé.";
    }

    /** Identifiant d'appel propre à la sonde : jamais celui d'un {@code tool_use} du fournisseur. */
    private static String probeCallId() {
        return "ping-" + UUID.randomUUID();
    }

    /**
     * Le chemin sondé, relatif et <b>aléatoire</b> : il ne peut pas exister, donc le runner répond
     * {@code not_found} sans rien lire. C'est la réponse, pas son contenu, qui est la preuve.
     */
    private ObjectNode probeInput() {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("path", PROBE_PATH_PREFIX + Long.toHexString(UUID.randomUUID().getMostSignificantBits()));
        return input;
    }
}
