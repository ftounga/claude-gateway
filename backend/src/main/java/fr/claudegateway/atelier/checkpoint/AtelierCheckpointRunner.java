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
        String correction = verdict == null ? null : verdict.correction();
        return WRITE_BLOCKED_PREFIX
                + (correction == null || correction.isBlank() ? WRITE_BLOCKED_FALLBACK : correction);
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
