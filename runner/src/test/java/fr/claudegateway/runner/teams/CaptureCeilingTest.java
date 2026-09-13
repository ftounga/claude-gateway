package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>L'arrêt de sécurité</b> (F-91 / SF-91-02).
 *
 * <p>Il traite la moitié que le témoin ne traite pas : la capture oubliée <b>quand l'écran n'est
 * plus visible</b> — session verrouillée, poste laissé allumé le soir. Le minuteur est injecté
 * pour que le plafond s'éprouve sans attendre trois heures.</p>
 */
@DisplayName("F-91 / SF-91-02 — l'arrêt de sécurité")
class CaptureCeilingTest {

    @Test
    @DisplayName("armé, il attend exactement le plafond — et ne fait rien avant")
    void armsForTheCeiling() {
        List<Duration> delays = new ArrayList<>();
        AtomicReference<Runnable> task = new AtomicReference<>();
        CaptureCeiling ceiling = new CaptureCeiling(Duration.ofMinutes(90), (delay, action) -> {
            delays.add(delay);
            task.set(action);
            return () -> { };
        });
        List<String> stopped = new ArrayList<>();

        ceiling.arm(() -> stopped.add("stop"));

        assertEquals(List.of(Duration.ofMinutes(90)), delays);
        assertTrue(stopped.isEmpty(), "rien ne doit se produire avant le plafond");

        task.get().run();
        assertEquals(List.of("stop"), stopped);
    }

    @Test
    @DisplayName("désarmé, le minuteur est annulé : le cas normal est qu'on arrête avant")
    void disarmCancels() {
        List<String> cancelled = new ArrayList<>();
        CaptureCeiling ceiling = new CaptureCeiling(Duration.ofMinutes(5),
                (delay, action) -> () -> cancelled.add("annulé"));

        ceiling.arm(() -> { });
        ceiling.disarm();

        assertEquals(List.of("annulé"), cancelled);
    }

    @Test
    @DisplayName("un armement remplace le précédent : une capture à la fois, un minuteur à la fois")
    void rearmReplaces() {
        List<String> cancelled = new ArrayList<>();
        CaptureCeiling ceiling = new CaptureCeiling(Duration.ofMinutes(5),
                (delay, action) -> () -> cancelled.add("annulé"));

        ceiling.arm(() -> { });
        ceiling.arm(() -> { });

        assertEquals(1, cancelled.size());
    }

    @Test
    @DisplayName("un minuteur qui refuse d'être annulé n'empêche pas d'arrêter la capture")
    void unstoppableTimerDoesNotBreakStop() {
        CaptureCeiling ceiling = new CaptureCeiling(Duration.ofMinutes(5), (delay, action) -> () -> {
            throw new IllegalStateException("ce minuteur ne s'annule pas");
        });

        ceiling.arm(() -> { });
        ceiling.disarm();
    }

    @Test
    @DisplayName("le plafond est dit en toutes lettres, avec sa raison")
    void sentenceSaysWhy() {
        String sentence = new CaptureCeiling(Duration.ofHours(3), (delay, action) -> () -> { })
                .sentence();

        assertTrue(sentence.contains("03:00:00"), sentence);
        assertTrue(sentence.contains("réunion suivante"), sentence);
        assertFalse(sentence.isBlank());
    }
}
