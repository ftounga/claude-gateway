package fr.claudegateway.atelier.checkpoint;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Le registre des points de contrôle de la boucle maison (F-50 / SF-50-01) : il ordonne les
 * contrôles, les interroge, isole leurs défaillances et borne ce qu'ils renvoient.
 *
 * <p><b>Pourquoi un registre plutôt qu'une liste dans la boucle.</b> {@code AtelierChatService} fait
 * déjà plus de mille cinq cents lignes. Y déverser l'ordonnancement, la capture d'exception et le
 * bornage du texte l'aurait allongée d'autant sans rien rendre testable seul. Ici, tout cela se
 * vérifie sans fournisseur, sans projet et sans tour.</p>
 *
 * <p><b>Trois règles, et elles se lisent dans le code ci-dessous.</b></p>
 * <ol>
 *   <li><b>Le premier blocage l'emporte</b> (décision D3). Agréger les blocages donnerait au modèle
 *       une liste de corrections dont il traiterait la première ; le reste serait perdu. Un blocage
 *       vaut une correction, la suivante reviendra au prochain passage.</li>
 *   <li><b>Un contrôle qui lève est ignoré</b> (décision D2). L'inverse — bloquer parce que le
 *       contrôle est cassé — condamnerait le projet d'un utilisateur sur un bogue de gouvernance,
 *       sans qu'il ait le moindre moyen de s'en sortir : rien n'est débrayable en F-50.</li>
 *   <li><b>Sans contrôle enregistré, rien ne change.</b> C'est l'état livré par F-50 : le mécanisme
 *       existe, il ne fait rien. F-51 décidera ce qui s'y branche.</li>
 * </ol>
 *
 * <p>Le journal ne dit <b>jamais</b> ce qui a été jugé : ni chemin, ni contenu, ni action corrective.
 * C'est la règle de l'audit runner (SF-38-08), et elle vaut ici — un contrôle porte, par
 * construction, sur ce que l'utilisateur écrit.</p>
 */
@Component
public class AtelierCheckpointRunner {

    private static final Logger log = LoggerFactory.getLogger(AtelierCheckpointRunner.class);

    /**
     * Message rendu au modèle quand une écriture est bloquée. Le préfixe dit ce qui s'est passé, la
     * suite dit quoi faire : c'est un {@code tool_result} en erreur, exactement comme un refus de la
     * porte de confirmation (SF-38-08).
     */
    private static final String WRITE_BLOCKED_PREFIX = "Écriture contrôlée : ";

    /** Repli quand un contrôle bloque sans dire quoi corriger. Un blocage muet reste un blocage. */
    private static final String WRITE_BLOCKED_FALLBACK = "reprends ce fichier avant de continuer.";

    /**
     * Message déposé côté utilisateur quand la fin d'un tour est refusée (F-50 / SF-50-02). Ce n'est
     * pas un {@code tool_result} : le tour bloqué est, par définition, celui qui n'a demandé aucun
     * outil, et un résultat orphelin serait refusé par le fournisseur.
     */
    private static final String END_OF_TURN_BLOCKED_PREFIX = "Fin de tour contrôlée : ";

    /** Repli de fin de tour : le modèle doit savoir qu'il n'a pas fini, même sans consigne précise. */
    private static final String END_OF_TURN_BLOCKED_FALLBACK =
            "reprends le travail avant de conclure.";

    /**
     * Message rendu au modèle quand une commande est refusée avant d'être émise (F-52 / SF-52-01).
     * C'est un {@code tool_result} en erreur, comme un refus de la porte de confirmation — à ceci
     * près que rien n'a été demandé à l'utilisateur : le refus est celui d'une règle qu'il a
     * lui-même activée.
     */
    private static final String COMMAND_BLOCKED_PREFIX = "Commande contrôlée : ";

    /** Repli quand un contrôle refuse une commande sans dire quoi corriger. */
    private static final String COMMAND_BLOCKED_FALLBACK =
            "reprends cette commande avant de la relancer.";

    /**
     * Les contrôles, dans l'ordre de Spring ({@code @Order} / {@link org.springframework.core.Ordered}).
     * Vide tant que F-51 n'en enregistre aucun.
     */
    private final List<AtelierCheckpoint> checkpoints;

    public AtelierCheckpointRunner(List<AtelierCheckpoint> checkpoints) {
        this.checkpoints = List.copyOf(checkpoints);
    }

    /** Registre sans aucun contrôle : le comportement historique de la boucle, à l'identique. */
    public static AtelierCheckpointRunner none() {
        return new AtelierCheckpointRunner(List.of());
    }

    /** Vrai si au moins un contrôle est branché sur ce point d'accroche. */
    public boolean hasCheckpoints(AtelierCheckpointKind kind) {
        return checkpoints.stream().anyMatch(checkpoint -> kind == kindOf(checkpoint));
    }

    /**
     * Interroge les contrôles du point d'accroche demandé et rend le premier verdict bloquant.
     *
     * @return le verdict bloquant, ou {@link AtelierCheckpointVerdict#proceed()} si rien ne bloque —
     *         jamais {@code null}
     */
    public AtelierCheckpointVerdict run(AtelierCheckpointKind kind, AtelierCheckpointContext context) {
        for (AtelierCheckpoint checkpoint : checkpoints) {
            if (kind != kindOf(checkpoint)) {
                continue;
            }
            AtelierCheckpointVerdict verdict = evaluateSafely(checkpoint, context);
            if (verdict != null && verdict.blocked()) {
                log.info("Point de contrôle bloquant (workspace={}, point={}, contrôle={})",
                        context == null ? null : context.workspaceId(), kind,
                        checkpoint.getClass().getSimpleName());
                return verdict;
            }
        }
        return AtelierCheckpointVerdict.proceed();
    }

    /**
     * Message rendu au modèle pour une écriture bloquée : le geste attendu, jamais le seul constat.
     */
    public static String writeBlockedMessage(AtelierCheckpointVerdict verdict) {
        return message(WRITE_BLOCKED_PREFIX, WRITE_BLOCKED_FALLBACK, verdict);
    }

    /**
     * Message déposé au modèle quand la fin d'un tour est refusée (F-50 / SF-50-02) : là encore, le
     * geste attendu — le modèle n'a pas fini, et il doit savoir par quoi reprendre.
     */
    public static String endOfTurnBlockedMessage(AtelierCheckpointVerdict verdict) {
        return message(END_OF_TURN_BLOCKED_PREFIX, END_OF_TURN_BLOCKED_FALLBACK, verdict);
    }

    /**
     * Message rendu au modèle quand une commande n'a pas été émise (F-52 / SF-52-01) : ce qu'il faut
     * changer pour que la commande passe, jamais le seul refus.
     */
    public static String commandBlockedMessage(AtelierCheckpointVerdict verdict) {
        return message(COMMAND_BLOCKED_PREFIX, COMMAND_BLOCKED_FALLBACK, verdict);
    }

    private static String message(String prefix, String fallback, AtelierCheckpointVerdict verdict) {
        String correction = verdict == null ? null : verdict.correction();
        return prefix + (correction == null || correction.isBlank() ? fallback : correction);
    }

    /** {@code kind()} d'un contrôle, sans jamais laisser une implémentation bancale casser le tour. */
    private static AtelierCheckpointKind kindOf(AtelierCheckpoint checkpoint) {
        try {
            return checkpoint.kind();
        } catch (RuntimeException ex) {
            log.warn("Point de contrôle ignoré : {} n'a pas su dire à quoi il s'applique ({})",
                    checkpoint.getClass().getSimpleName(), ex.getClass().getSimpleName());
            return null;
        }
    }

    private static AtelierCheckpointVerdict evaluateSafely(AtelierCheckpoint checkpoint,
            AtelierCheckpointContext context) {
        try {
            return checkpoint.evaluate(context);
        } catch (RuntimeException ex) {
            // Repli passant (D2). Le message de l'exception n'est pas journalisé : il peut porter ce
            // qui était jugé, c'est-à-dire le contenu du fichier de l'utilisateur.
            log.warn("Point de contrôle ignoré : {} a échoué ({})",
                    checkpoint.getClass().getSimpleName(), ex.getClass().getSimpleName());
            return null;
        }
    }
}
