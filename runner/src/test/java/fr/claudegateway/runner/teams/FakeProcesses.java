package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * <b>Un `ffmpeg` de papier</b> (F-90 / SF-90-01), sur le modèle de {@link PaperTeams}.
 *
 * <p>Il existe pour une raison écrite dans la mini-spec et qu'on ne maquille pas : <b>le CI n'a pas
 * {@code ffmpeg}</b>, et nous n'avons <b>aucun compte Teams de test</b>. Ce qui est donc éprouvé ici
 * est ce que le produit <b>décide</b> — lire un journal {@code showinfo}, dédoublonner, plafonner,
 * refuser — et non ce qu'{@code ffmpeg} fait. La confrontation au réel reste le travail de la sonde
 * de santé (F-87 / SF-87-03) le jour du premier branchement.</p>
 */
final class FakeProcesses implements ProcessRunner {

    /** Ce qui a été lancé, dans l'ordre — pour vérifier la ligne de commande sans shell. */
    final List<List<String>> calls = new ArrayList<>();

    private Function<List<String>, ProcessResult> behaviour =
            command -> new ProcessResult(0, List.of(), List.of(), false);
    private IOException failure;

    FakeProcesses answering(Function<List<String>, ProcessResult> behaviour) {
        this.behaviour = behaviour;
        return this;
    }

    FakeProcesses succeedingWith(List<String> stderr) {
        return answering(command -> new ProcessResult(0, List.of(), stderr, false));
    }

    FakeProcesses failingWith(List<String> stderr) {
        return answering(command -> new ProcessResult(1, List.of(), stderr, false));
    }

    FakeProcesses unlaunchable(IOException failure) {
        this.failure = failure;
        return this;
    }

    @Override
    public ProcessResult run(List<String> command, Path workingDir, long timeoutMs)
            throws IOException {
        calls.add(List.copyOf(command));
        if (failure != null) {
            throw failure;
        }
        return behaviour.apply(command);
    }

    /** La dernière commande lancée, ou une liste vide. */
    List<String> lastCall() {
        return calls.isEmpty() ? List.of() : calls.get(calls.size() - 1);
    }

    /** Une bannière de version qui identifie l'outil, comme le ferait le vrai binaire. */
    static ProcessResult version(String name) {
        return new ProcessResult(0,
                List.of(name + " version 7.1 Copyright (c) 2000-2024 the FFmpeg developers"),
                List.of(), false);
    }

    /**
     * Une ligne de journal {@code showinfo} telle qu'{@code ffmpeg} l'écrit.
     *
     * <p><b>Provenance, écrite et non maquillée</b> : cette ligne est <b>fabriquée à la main</b>
     * d'après le format documenté du filtre {@code showinfo}. Elle n'a pas été capturée sur une
     * vraie réunion Teams — nous n'en avons aucune.</p>
     */
    static String showinfo(int index, double ptsTime) {
        return "[Parsed_showinfo_1 @ 0x5601aa] n:" + index + " pts:" + (long) (ptsTime * 1000)
                + " pts_time:" + ptsTime + " duration:1 duration_time:0.04 fmt:yuvj420p sar:1/1 "
                + "s:1280x720 i:P iskey:1 type:I checksum:9A0B1C2D";
    }
}
