package fr.claudegateway.atelier.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Le registre des points de contrôle (F-50 / SF-50-01), vérifié <b>seul</b> : sans fournisseur, sans
 * projet, sans tour. C'est l'intérêt d'avoir sorti l'ordonnancement de la boucle.
 */
class AtelierCheckpointRunnerTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private AtelierCheckpointContext context() {
        return AtelierCheckpointContext.afterFileWrite(userId, workspaceId, "write_file",
                "src/a.ts", "const x = 1;");
    }

    /** Contrôle scriptable : son verdict est donné, et il note qu'on l'a interrogé. */
    private static final class ScriptedCheckpoint implements AtelierCheckpoint {
        private final AtelierCheckpointKind kind;
        private final AtelierCheckpointVerdict verdict;
        private final RuntimeException failure;
        private final List<String> log;
        private final String name;

        ScriptedCheckpoint(String name, AtelierCheckpointKind kind, AtelierCheckpointVerdict verdict,
                RuntimeException failure, List<String> log) {
            this.name = name;
            this.kind = kind;
            this.verdict = verdict;
            this.failure = failure;
            this.log = log;
        }

        @Override
        public AtelierCheckpointKind kind() {
            return kind;
        }

        @Override
        public AtelierCheckpointVerdict evaluate(AtelierCheckpointContext context) {
            log.add(name);
            if (failure != null) {
                throw failure;
            }
            return verdict;
        }
    }

    private static ScriptedCheckpoint passing(String name, List<String> log) {
        return new ScriptedCheckpoint(name, AtelierCheckpointKind.AFTER_FILE_WRITE,
                AtelierCheckpointVerdict.proceed(), null, log);
    }

    private static ScriptedCheckpoint blocking(String name, String correction, List<String> log) {
        return new ScriptedCheckpoint(name, AtelierCheckpointKind.AFTER_FILE_WRITE,
                AtelierCheckpointVerdict.block(correction), null, log);
    }

    @Test
    void withoutAnyCheckpointNothingBlocks() {
        // L'état livré par F-50 : le mécanisme existe, il ne fait rien.
        AtelierCheckpointRunner runner = AtelierCheckpointRunner.none();

        assertThat(runner.hasCheckpoints(AtelierCheckpointKind.AFTER_FILE_WRITE)).isFalse();
        assertThat(runner.run(AtelierCheckpointKind.AFTER_FILE_WRITE, context()).blocked()).isFalse();
    }

    @Test
    void aBlockingCheckpointCarriesItsCorrectiveAction() {
        List<String> log = new ArrayList<>();
        AtelierCheckpointRunner runner = new AtelierCheckpointRunner(
                List.of(blocking("un", "Ajoute l'en-tête en tête de src/a.ts, puis reprends.", log)));

        AtelierCheckpointVerdict verdict =
                runner.run(AtelierCheckpointKind.AFTER_FILE_WRITE, context());

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.correction())
                .isEqualTo("Ajoute l'en-tête en tête de src/a.ts, puis reprends.");
        assertThat(runner.hasCheckpoints(AtelierCheckpointKind.AFTER_FILE_WRITE)).isTrue();
    }

    @Test
    void theFirstBlockingCheckpointShortCircuitsTheFollowingOnes() {
        // Un blocage vaut UNE correction : empiler deux consignes ferait traiter la première et
        // oublier la seconde (décision D3).
        List<String> log = new ArrayList<>();
        AtelierCheckpointRunner runner = new AtelierCheckpointRunner(List.of(
                passing("passant", log), blocking("premier", "Corrige A.", log),
                blocking("second", "Corrige B.", log)));

        AtelierCheckpointVerdict verdict =
                runner.run(AtelierCheckpointKind.AFTER_FILE_WRITE, context());

        assertThat(verdict.correction()).isEqualTo("Corrige A.");
        assertThat(log).containsExactly("passant", "premier");
    }

    @Test
    void aCheckpointOfAnotherKindIsNeverAsked() {
        List<String> log = new ArrayList<>();
        AtelierCheckpointRunner runner = new AtelierCheckpointRunner(List.of(
                new ScriptedCheckpoint("fin de tour", AtelierCheckpointKind.END_OF_TURN,
                        AtelierCheckpointVerdict.block("Corrige."), null, log)));

        assertThat(runner.hasCheckpoints(AtelierCheckpointKind.AFTER_FILE_WRITE)).isFalse();
        assertThat(runner.run(AtelierCheckpointKind.AFTER_FILE_WRITE, context()).blocked()).isFalse();
        assertThat(log).isEmpty();
    }

    @Test
    void aFailingCheckpointIsIgnoredAndTheOthersStillRun() {
        // Repli passant (décision D2) : un bogue de gouvernance ne condamne pas le projet d'un
        // utilisateur, qui n'a aucun moyen de le débrayer.
        List<String> log = new ArrayList<>();
        AtelierCheckpointRunner runner = new AtelierCheckpointRunner(List.of(
                new ScriptedCheckpoint("cassé", AtelierCheckpointKind.AFTER_FILE_WRITE, null,
                        new IllegalStateException("boum"), log),
                blocking("suivant", "Corrige B.", log)));

        AtelierCheckpointVerdict verdict =
                runner.run(AtelierCheckpointKind.AFTER_FILE_WRITE, context());

        assertThat(verdict.correction()).isEqualTo("Corrige B.");
        assertThat(log).containsExactly("cassé", "suivant");
    }

    @Test
    void aCheckpointReturningNullIsTreatedAsPassing() {
        List<String> log = new ArrayList<>();
        AtelierCheckpointRunner runner = new AtelierCheckpointRunner(List.of(
                new ScriptedCheckpoint("muet", AtelierCheckpointKind.AFTER_FILE_WRITE, null, null, log)));

        assertThat(runner.run(AtelierCheckpointKind.AFTER_FILE_WRITE, context()).blocked()).isFalse();
    }

    @Test
    void aBlockWithoutCorrectiveActionStillSaysWhatToDo() {
        // Un blocage muet reste un blocage : le modèle doit malgré tout savoir qu'il doit revenir
        // sur ce fichier.
        AtelierCheckpointVerdict verdict = AtelierCheckpointVerdict.block("   ");

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.correction()).isNull();
        assertThat(AtelierCheckpointRunner.writeBlockedMessage(verdict))
                .isEqualTo("Écriture contrôlée : reprends ce fichier avant de continuer.");
    }

    @Test
    void theCorrectiveActionIsTrimmedAndBounded() {
        assertThat(AtelierCheckpointVerdict.block("  Corrige.  ").correction()).isEqualTo("Corrige.");

        String tooLong = "x".repeat(AtelierCheckpointVerdict.MAX_CORRECTION_CHARS + 500);
        assertThat(AtelierCheckpointVerdict.block(tooLong).correction())
                .hasSize(AtelierCheckpointVerdict.MAX_CORRECTION_CHARS);
    }

    @Test
    void proceedIsNeverBlockingAndCarriesNothing() {
        assertThat(AtelierCheckpointVerdict.proceed().blocked()).isFalse();
        assertThat(AtelierCheckpointVerdict.proceed().correction()).isNull();
    }

    @Test
    void theEndOfTurnMessageCarriesTheGestureToo() {
        assertThat(AtelierCheckpointRunner.endOfTurnBlockedMessage(
                AtelierCheckpointVerdict.block("Renseigne STATE.md, puis conclus.")))
                .isEqualTo("Fin de tour contrôlée : Renseigne STATE.md, puis conclus.");
        assertThat(AtelierCheckpointRunner.endOfTurnBlockedMessage(AtelierCheckpointVerdict.block(null)))
                .isEqualTo("Fin de tour contrôlée : reprends le travail avant de conclure.");
    }

    @Test
    void theEndOfTurnContextKeepsThePathsInOrderAndBoundsThem() {
        AtelierCheckpointContext context = AtelierCheckpointContext.endOfTurn(userId, workspaceId,
                "Terminé.", List.of("a.txt", "b.txt"));

        assertThat(context.kind()).isEqualTo(AtelierCheckpointKind.END_OF_TURN);
        assertThat(context.replyText()).isEqualTo("Terminé.");
        assertThat(context.writtenPaths()).containsExactly("a.txt", "b.txt");

        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < AtelierCheckpointContext.MAX_WRITTEN_PATHS + 50; i++) {
            tooMany.add("f" + i + ".txt");
        }
        assertThat(AtelierCheckpointContext.endOfTurn(userId, workspaceId, "x", tooMany).writtenPaths())
                .hasSize(AtelierCheckpointContext.MAX_WRITTEN_PATHS);
    }

    @Test
    void aWriteContextCarriesNoEndOfTurnField() {
        AtelierCheckpointContext context = context();

        assertThat(context.replyText()).isNull();
        assertThat(context.writtenPaths()).isEmpty();
    }

    @Test
    void theBlockedMessageCarriesTheGestureAndNotOnlyTheFinding() {
        String message = AtelierCheckpointRunner.writeBlockedMessage(
                AtelierCheckpointVerdict.block("Retire la clé en clair de config.ts, puis reprends."));

        assertThat(message).isEqualTo(
                "Écriture contrôlée : Retire la clé en clair de config.ts, puis reprends.");
    }
}
