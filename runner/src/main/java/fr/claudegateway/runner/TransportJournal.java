package fr.claudegateway.runner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ce que le runner a tenté pour se relier à la gateway, pourquoi ça a échoué, et ce qu'il a retenu
 * (F-82 / SF-82-03).
 *
 * <p><b>Ce que cette classe ne fait pas.</b> Elle ne décide rien. Le repli de transport (SF-38-09)
 * est <b>présent, câblé et atteignable</b> — l'instruction préalable de SF-82-03 l'a établi en
 * relisant {@link TransportFallbackPolicy}, {@link RunnerConnection}, {@link PollingConnection} et
 * {@code RunnerMain.runSession} : à deux échecs consécutifs de transport, la bascule se fait. Ce qui
 * manquait n'était pas la bascule, c'était le <b>récit</b> : les messages existaient mais dispersés
 * dans le défilement, jamais réunis, si bien qu'au moment où le runner s'arrêtait, personne n'avait
 * sous les yeux la phrase qui compte — quel transport, pourquoi il a échoué, lequel a été retenu.</p>
 *
 * <p>Le journal est donc <b>muet quand il n'y a rien à dire</b> : un transport qui s'établit du
 * premier coup et ne tombe jamais ne produit aucune ligne. On n'ajoute pas du bruit au chemin qui
 * marche pour éclairer celui qui ne marche pas.</p>
 *
 * <p>Aucun jeton n'y entre : les cibles sont consignées telles que l'appelant les a expurgées.</p>
 */
public final class TransportJournal {

    /**
     * Code de sortie quand <b>aucun</b> transport n'a jamais porté la session. Il n'existait pas :
     * ce cas sortait en {@code 0}, « arrêt normal », ce qui disait « tout s'est bien passé » à qui
     * lit le code de sortie. Les codes {@code 0} à {@code 5} gardent leur sens exact.
     */
    public static final int EXIT_NO_TRANSPORT = 6;

    /** Au-delà, un motif d'échec est tronqué : une trace de pile complète n'est pas un motif. */
    static final int MAX_REASON = 200;

    /** Les deux transports du contrat runner. Leur libellé est celui qu'on écrira à l'écran. */
    public enum Transport {
        WEBSOCKET("WebSocket"),
        POLLING("long-polling HTTP");

        private final String label;

        Transport(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Ce qu'on sait d'un transport : où il pointait, combien de fois il a échoué, et pourquoi. */
    private static final class Attempt {
        private final String target;
        private int failures;
        private String lastReason;
        private boolean established;

        private Attempt(String target) {
            this.target = target;
        }
    }

    // LinkedHashMap : l'ordre des tentatives EST l'information — le WebSocket d'abord, le repli
    // ensuite. Un récapitulatif qui inverserait les deux raconterait une autre histoire.
    private final Map<Transport, Attempt> attempts = new LinkedHashMap<>();
    private String noFallbackReason;

    /** Un transport est sur le point d'être essayé, vers cette cible (jeton déjà expurgé). */
    public void attempted(Transport transport, String target) {
        attempts.computeIfAbsent(transport, t -> new Attempt(target));
    }

    /** Ce transport a porté la session — ne serait-ce qu'un instant. */
    public void established(Transport transport) {
        attempts.computeIfAbsent(transport, t -> new Attempt(null)).established = true;
    }

    /** Ce transport a échoué, et voici pourquoi. */
    public void failed(Transport transport, String reason) {
        Attempt attempt = attempts.computeIfAbsent(transport, t -> new Attempt(null));
        attempt.failures++;
        attempt.lastReason = truncate(reason);
    }

    /**
     * Pourquoi aucun repli n'a été tenté, quand un drapeau l'interdit ({@code --transport
     * websocket}). Sans cette ligne, on voit des reconnexions sans fin sans savoir qu'un repli
     * existe et qu'on l'a soi-même interdit.
     */
    public void noFallback(String reason) {
        this.noFallbackReason = reason;
    }

    /** Vrai si au moins un transport a porté la session. C'est ce qui distingue un échec total. */
    public boolean anyEstablished() {
        return attempts.values().stream().anyMatch(a -> a.established);
    }

    /**
     * Le code de sortie de la session.
     *
     * @param shutdownRequested vrai si l'utilisateur a demandé l'arrêt ({@code Ctrl-C}) : un arrêt
     *                          demandé n'est <b>pas</b> un échec de transport, même si rien ne
     *                          s'était encore établi
     */
    public int exitCode(boolean shutdownRequested) {
        if (shutdownRequested || attempts.isEmpty() || anyEstablished()) {
            return 0;
        }
        return EXIT_NO_TRANSPORT;
    }

    /**
     * Le récapitulatif, tel qu'il sera écrit à l'écran — ou <b>rien</b> s'il n'y a rien à dire.
     *
     * <p>« Rien à dire » veut dire précisément : un seul transport, établi, sans le moindre échec.
     * Dès qu'un échec a eu lieu, ou qu'un second transport est entré en jeu, le récit vaut ses
     * quelques lignes.</p>
     */
    public List<String> summaryLines() {
        if (attempts.isEmpty() || (attempts.size() == 1 && isCleanSingleAttempt())) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Transport, Attempt> entry : attempts.entrySet()) {
            lines.add("Transports : " + describe(entry.getKey(), entry.getValue()));
        }
        if (noFallbackReason != null) {
            lines.add("Aucun repli tenté : " + noFallbackReason);
        }
        lines.add(conclusion());
        return List.copyOf(lines);
    }

    private boolean isCleanSingleAttempt() {
        Attempt only = attempts.values().iterator().next();
        return only.established && only.failures == 0;
    }

    /** « WebSocket (wss://…) — 2 échec(s), dernier motif : … » ou « … — établi. » */
    private String describe(Transport transport, Attempt attempt) {
        StringBuilder line = new StringBuilder(transport.label());
        if (attempt.target != null && !attempt.target.isBlank()) {
            line.append(" (").append(attempt.target).append(')');
        }
        if (attempt.failures > 0) {
            line.append(" — ").append(attempt.failures).append(" échec(s)");
            if (attempt.lastReason != null) {
                line.append(", dernier motif : ").append(attempt.lastReason);
            }
            // Un transport qui a échoué PUIS tenu mérite les deux moitiés de sa phrase.
            line.append(attempt.established ? ", puis établi." : ".");
            return line.toString();
        }
        return line.append(attempt.established ? " — établi." : " — tenté, sans résultat.").toString();
    }

    /** La phrase qui manquait au moment où le runner s'arrête. */
    private String conclusion() {
        if (!anyEstablished()) {
            String tried = String.join(", ni ",
                    attempts.keySet().stream().map(Transport::label).toList());
            return "Aucun transport n'a tenu : ni " + tried + ".";
        }
        // Le transport RETENU est le dernier qui s'est établi : le repli est unidirectionnel
        // (SF-38-09), donc l'ordre d'insertion suffit à le désigner.
        Transport retained = null;
        for (Map.Entry<Transport, Attempt> entry : attempts.entrySet()) {
            if (entry.getValue().established) {
                retained = entry.getKey();
            }
        }
        return "Transport retenu : " + retained.label() + ".";
    }

    private static String truncate(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String trimmed = reason.trim();
        return trimmed.length() <= MAX_REASON ? trimmed : trimmed.substring(0, MAX_REASON) + "…";
    }
}
