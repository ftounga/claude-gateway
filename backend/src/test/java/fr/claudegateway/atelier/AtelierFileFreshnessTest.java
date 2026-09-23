package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires du suivi de fraîcheur des fichiers (F-121 / SF-121-19) : garde dure gardée par la
 * preuve d'existence, rappel doux de repli (SF-119-05), note de changement à la relecture, coupe-circuit.
 */
class AtelierFileFreshnessTest {

    private static final boolean EXISTS = true;
    private static final boolean NEW = false;
    private static final boolean FULL = true;
    private static final boolean PAGINATED = true;

    // -------------------------------------------------- garde dure (existence prouvée)

    @Test
    void refusesABlindEditOfAProvenExistingFile() {
        AtelierFileFreshness freshness = new AtelierFileFreshness(true);

        Optional<String> refusal = freshness.refuseWrite("edit_file", "src/a.ts", EXISTS);

        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("src/a.ts").contains("read_file");
    }

    @Test
    void refusesABlindOverwriteOfAProvenExistingFile() {
        AtelierFileFreshness freshness = new AtelierFileFreshness(true);

        Optional<String> refusal = freshness.refuseWrite("write_file", "src/a.ts", EXISTS);

        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("écraser").contains("src/a.ts");
    }

    @Test
    void doesNotRefuseAnEditOnceTheFileHasBeenRead() {
        AtelierFileFreshness freshness = new AtelierFileFreshness(true);
        freshness.noteRead("src/a.ts", "const a = 1;", !PAGINATED);

        assertThat(freshness.refuseWrite("edit_file", "src/a.ts", EXISTS)).isEmpty();
    }

    @Test
    void doesNotRefuseAnEditOnAFileWrittenEarlierInTheThread() {
        AtelierFileFreshness freshness = new AtelierFileFreshness(true);
        freshness.noteWrite("write_file", "src/a.ts", "const a = 1;", FULL);

        assertThat(freshness.refuseWrite("edit_file", "src/a.ts", EXISTS)).isEmpty();
    }

    // -------------------------------------------------- repli sûr (existence non prouvée)

    @Test
    void doesNotRefuseAnEditWhenExistenceIsNotProven() {
        AtelierFileFreshness freshness = new AtelierFileFreshness(true);

        // Repli sûr : sans preuve d'existence (index muet, fichier peut-être neuf), pas de refus dur.
        assertThat(freshness.refuseWrite("edit_file", "src/a.ts", NEW)).isEmpty();
    }

    @Test
    void doesNotRefuseAWriteOfANewFile() {
        AtelierFileFreshness freshness = new AtelierFileFreshness(true);

        assertThat(freshness.refuseWrite("write_file", "src/new.ts", NEW)).isEmpty();
    }

    @Test
    void aBlindEditNotRefusedStillGetsTheSoftReminder() {
        AtelierFileFreshness freshness = new AtelierFileFreshness(true);

        // edit_file d'un fichier jamais lu, non refusé (existence non prouvée) : rappel doux SF-119-05.
        Optional<String> reminder = freshness.noteWrite("edit_file", "src/a.ts", null, !FULL);

        assertThat(reminder).isPresent();
        assertThat(reminder.get()).contains("src/a.ts").contains("sans l'avoir lu");
    }

    @Test
    void aReadThenEditGetsNoReminder() {
        AtelierFileFreshness freshness = new AtelierFileFreshness(true);
        freshness.noteRead("src/a.ts", "const a = 1;", !PAGINATED);

        assertThat(freshness.noteWrite("edit_file", "src/a.ts", null, !FULL)).isEmpty();
    }

    @Test
    void aBlindWriteGetsNoReminder() {
        AtelierFileFreshness freshness = new AtelierFileFreshness(true);

        // Un write_file (création) n'est pas une édition à l'aveugle : aucun rappel.
        assertThat(freshness.noteWrite("write_file", "src/new.ts", "hop", FULL)).isEmpty();
    }

    // -------------------------------------------------- coupe-circuit

    @Test
    void theCircuitBreakerSilencesEverything() {
        AtelierFileFreshness freshness = new AtelierFileFreshness(false);

        assertThat(freshness.refuseWrite("edit_file", "src/a.ts", EXISTS)).isEmpty();
        assertThat(freshness.refuseWrite("write_file", "src/a.ts", EXISTS)).isEmpty();
        assertThat(freshness.noteWrite("edit_file", "src/a.ts", null, !FULL)).isEmpty();
    }

    // -------------------------------------------------- note de changement à la relecture

    @Test
    void aReReadWithChangedContentEmitsAChangeNote() {
        AtelierFileFreshness freshness = new AtelierFileFreshness(true);
        assertThat(freshness.noteRead("src/a.ts", "version 1", !PAGINATED)).isEmpty(); // 1re lecture

        Optional<String> note = freshness.noteRead("src/a.ts", "version 2", !PAGINATED);

        assertThat(note).isPresent();
        assertThat(note.get()).contains("src/a.ts").contains("a changé");
    }

    @Test
    void aReReadWithIdenticalContentEmitsNoNote() {
        AtelierFileFreshness freshness = new AtelierFileFreshness(true);
        freshness.noteRead("src/a.ts", "même contenu", !PAGINATED);

        assertThat(freshness.noteRead("src/a.ts", "même contenu", !PAGINATED)).isEmpty();
    }

    @Test
    void theFirstReadNeverEmitsAChangeNote() {
        AtelierFileFreshness freshness = new AtelierFileFreshness(true);

        assertThat(freshness.noteRead("src/a.ts", "quoi que ce soit", !PAGINATED)).isEmpty();
    }

    @Test
    void aPaginatedReReadDoesNotEmitAChangeNoteButMarksKnown() {
        AtelierFileFreshness freshness = new AtelierFileFreshness(true);
        freshness.noteRead("src/a.ts", "version 1", !PAGINATED);

        // La tranche paginée n'est pas comparable au fichier entier : pas de note…
        assertThat(freshness.noteRead("src/a.ts", "tranche différente", PAGINATED)).isEmpty();
        // … mais le chemin reste connu, donc éditable sans refus.
        assertThat(freshness.refuseWrite("edit_file", "src/a.ts", EXISTS)).isEmpty();
    }

    // -------------------------------------------------- amorçage historique

    @Test
    void aSeededKnownPathIsNeitherRefusedNorReminded() {
        AtelierFileFreshness freshness = new AtelierFileFreshness(true);
        freshness.seedKnown("src/a.ts"); // lu dans un tour précédent du fil

        assertThat(freshness.isKnown("src/a.ts")).isTrue();
        assertThat(freshness.refuseWrite("edit_file", "src/a.ts", EXISTS)).isEmpty();
        assertThat(freshness.noteWrite("edit_file", "src/a.ts", null, !FULL)).isEmpty();
    }

    @Test
    void aBlankOrNullPathIsInert() {
        AtelierFileFreshness freshness = new AtelierFileFreshness(true);

        assertThat(freshness.refuseWrite("edit_file", "  ", EXISTS)).isEmpty();
        assertThat(freshness.refuseWrite("edit_file", null, EXISTS)).isEmpty();
        assertThat(freshness.noteRead(null, "x", !PAGINATED)).isEmpty();
        assertThat(freshness.noteWrite("edit_file", null, null, !FULL)).isEmpty();
    }
}
