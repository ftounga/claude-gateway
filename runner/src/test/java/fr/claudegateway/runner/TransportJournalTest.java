package fr.claudegateway.runner;

import static fr.claudegateway.runner.TransportJournal.Transport.POLLING;
import static fr.claudegateway.runner.TransportJournal.Transport.WEBSOCKET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * F-82 / SF-82-03 — le repli de transport <b>se nomme</b>.
 *
 * <p><b>Ce que l'instruction préalable a établi</b>, et qui commande ces tests : le repli de
 * SF-38-09 n'est <b>pas</b> cassé. La politique compte les échecs, {@code RunnerConnection} lit son
 * verdict à chaque tour, {@code runSession} monte la {@code PollingConnection}, et les trois
 * endpoints existent côté gateway. Ce qui manquait, c'est le <b>récit</b> — et un code de sortie
 * qui distingue « tout s'est bien passé » de « aucun transport n'a tenu ».</p>
 *
 * <p>Aucun test ici ne touche à {@link TransportFallbackPolicy} : sa suite passe sans modification,
 * et c'est la preuve que la bascule n'a pas bougé.</p>
 */
class TransportJournalTest {

    private static final String WS = "wss://portal.example.com/api/runner/ws?token=***";
    private static final String POLL = "https://portal.example.com/api/runner/poll";

    @Test
    void un_transport_etabli_du_premier_coup_ne_dit_rien() {
        TransportJournal journal = new TransportJournal();
        journal.attempted(WEBSOCKET, WS);
        journal.established(WEBSOCKET);

        // On n'ajoute pas du bruit au chemin qui marche pour éclairer celui qui ne marche pas.
        assertEquals(List.of(), journal.summaryLines());
        assertEquals(0, journal.exitCode(false));
    }

    @Test
    void le_repli_nomme_les_deux_transports_et_le_motif_de_l_echec() {
        // LE CAS DU CADRAGE : le WebSocket est refusé, le long-polling prend le relais.
        TransportJournal journal = new TransportJournal();
        journal.attempted(WEBSOCKET, WS);
        journal.failed(WEBSOCKET, "Connexion refusée");
        journal.failed(WEBSOCKET, "Connexion refusée");
        journal.attempted(POLLING, POLL);
        journal.established(POLLING);

        String all = String.join("\n", journal.summaryLines());

        // Les DEUX transports sont nommés — pas seulement celui qui a fini par marcher.
        assertTrue(all.contains("WebSocket"), all);
        assertTrue(all.contains("long-polling HTTP"), all);
        // Le motif de l'échec est nommé.
        assertTrue(all.contains("Connexion refusée"), all);
        assertTrue(all.contains("2 échec(s)"), all);
        // Et la cible de chacun.
        assertTrue(all.contains(WS), all);
        assertTrue(all.contains(POLL), all);
    }

    @Test
    void le_transport_retenu_est_nomme() {
        TransportJournal journal = new TransportJournal();
        journal.attempted(WEBSOCKET, WS);
        journal.failed(WEBSOCKET, "Connexion refusée");
        journal.attempted(POLLING, POLL);
        journal.established(POLLING);

        List<String> lines = journal.summaryLines();

        assertEquals("Transport retenu : long-polling HTTP.", lines.get(lines.size() - 1));
    }

    @Test
    void un_echec_total_nomme_les_deux_transports_et_le_dit() {
        // « Ni WebSocket ni long-polling n'ont abouti et le runner s'est arrêté sans rien dire
        // d'exploitable » — le constat du 2026-09-12. Désormais, il dit.
        TransportJournal journal = new TransportJournal();
        journal.attempted(WEBSOCKET, WS);
        journal.failed(WEBSOCKET, "Connexion refusée");
        journal.failed(WEBSOCKET, "Connexion refusée");
        journal.attempted(POLLING, POLL);
        journal.failed(POLLING, "Liaison fermée par la gateway (409)");

        List<String> lines = journal.summaryLines();
        String all = String.join("\n", lines);

        assertTrue(all.contains("Connexion refusée"), all);
        assertTrue(all.contains("Liaison fermée par la gateway (409)"), all);
        assertEquals("Aucun transport n'a tenu : ni WebSocket, ni long-polling HTTP.",
                lines.get(lines.size() - 1));
    }

    @Test
    void un_echec_total_n_est_pas_un_arret_normal() {
        TransportJournal journal = new TransportJournal();
        journal.attempted(WEBSOCKET, WS);
        journal.failed(WEBSOCKET, "Connexion refusée");
        journal.attempted(POLLING, POLL);
        journal.failed(POLLING, "Liaison fermée par la gateway (409)");

        assertFalse(journal.anyEstablished());
        // Sortait en 0 — « arrêt normal » — alors qu'aucun transport n'avait porté la session.
        assertEquals(TransportJournal.EXIT_NO_TRANSPORT, journal.exitCode(false));
    }

    @Test
    void un_arret_demande_avant_tout_transport_reste_un_arret_normal() {
        // Ctrl-C pendant les tentatives : l'utilisateur a demandé l'arrêt, ce n'est pas une panne.
        TransportJournal journal = new TransportJournal();
        journal.attempted(WEBSOCKET, WS);
        journal.failed(WEBSOCKET, "Connexion refusée");

        assertEquals(0, journal.exitCode(true));
    }

    @Test
    void une_session_ou_rien_n_a_ete_tente_sort_en_zero() {
        // Chemins qui n'atteignent jamais un transport (--check, config invalide) : rien ne change.
        assertEquals(0, new TransportJournal().exitCode(false));
        assertEquals(List.of(), new TransportJournal().summaryLines());
    }

    @Test
    void un_transport_qui_a_echoue_puis_tenu_dit_les_deux_moities() {
        TransportJournal journal = new TransportJournal();
        journal.attempted(WEBSOCKET, WS);
        journal.failed(WEBSOCKET, "socket coupée après 800 ms");
        journal.established(WEBSOCKET);

        String all = String.join("\n", journal.summaryLines());

        assertTrue(all.contains("1 échec(s)"), all);
        assertTrue(all.contains("puis établi"), all);
        assertEquals("Transport retenu : WebSocket.",
                journal.summaryLines().get(journal.summaryLines().size() - 1));
        assertEquals(0, journal.exitCode(false));
    }

    @Test
    void le_journal_n_ecrit_jamais_le_jeton() {
        // La cible arrive déjà expurgée (safeUri côté WebSocket ; l'URL de poll ne porte pas le
        // jeton, qui voyage en en-tête). Le journal ne doit rien reconstituer.
        TransportJournal journal = new TransportJournal();
        journal.attempted(WEBSOCKET, WS);
        journal.failed(WEBSOCKET, "Connexion refusée");
        journal.attempted(POLLING, POLL);
        journal.failed(POLLING, "Réponse HTTP 502 sur le long-poll");

        String all = String.join("\n", journal.summaryLines());

        assertTrue(all.contains("token=***"), all);
        assertFalse(all.matches("(?s).*token=(?!\\*\\*\\*)[A-Za-z0-9_-]+.*"),
                "aucun jeton en clair : " + all);
    }

    @Test
    void un_motif_tres_long_est_tronque() {
        TransportJournal journal = new TransportJournal();
        journal.attempted(WEBSOCKET, WS);
        journal.failed(WEBSOCKET, "x".repeat(500));

        String all = String.join("\n", journal.summaryLines());

        assertTrue(all.contains("…"), "le motif est tronqué");
        assertFalse(all.contains("x".repeat(TransportJournal.MAX_REASON + 1)),
                "une trace de pile complète n'est pas un motif");
    }

    @Test
    void un_transport_impose_dit_pourquoi_aucun_repli_n_est_tente() {
        // --transport websocket : shouldFallBack() rend false PAR CONSTRUCTION (SF-38-09). Le choix
        // est délibéré ; ce qui manquait, c'est de dire que c'est CE drapeau qui l'a décidé.
        TransportJournal journal = new TransportJournal();
        journal.noFallback("--transport websocket impose la socket (retirez le drapeau pour "
                + "autoriser le repli long-polling HTTP).");
        journal.attempted(WEBSOCKET, WS);
        journal.failed(WEBSOCKET, "Connexion refusée");

        String all = String.join("\n", journal.summaryLines());

        assertTrue(all.contains("Aucun repli tenté"), all);
        assertTrue(all.contains("--transport websocket"), all);
        // Et comment revenir en arrière.
        assertTrue(all.contains("retirez le drapeau"), all);
    }

    @Test
    void le_code_de_sortie_reserve_est_six_et_n_entre_en_collision_avec_aucun_autre() {
        // 0 normal, 1 inattendu, 2 usage, 3 appairage, 4 jeton, 5 contrôle de vol.
        assertEquals(6, TransportJournal.EXIT_NO_TRANSPORT);
    }

    // ------------------------------------------------ ce que RunnerMain en fait

    @Test
    void le_verdict_ecrit_le_recapitulatif_et_rend_le_code_de_sortie() {
        List<String> printed = new java.util.ArrayList<>();
        Console console = new Console(printed::add,
                ConsoleEncoding.forCharset(java.nio.charset.StandardCharsets.UTF_8));
        TransportJournal journal = new TransportJournal();
        journal.attempted(WEBSOCKET, WS);
        journal.failed(WEBSOCKET, "Connexion refusée");
        journal.attempted(POLLING, POLL);
        journal.failed(POLLING, "Liaison fermée par la gateway (409)");

        int code = RunnerMain.transportVerdict(console, journal,
                new java.util.concurrent.atomic.AtomicBoolean(false));

        assertEquals(TransportJournal.EXIT_NO_TRANSPORT, code);
        String all = String.join("\n", printed);
        assertTrue(all.contains("WebSocket"), all);
        assertTrue(all.contains("long-polling HTTP"), all);
        assertTrue(all.contains("Aucun transport n'a tenu"), all);
    }

    @Test
    void le_verdict_reste_muet_et_rend_zero_quand_le_transport_a_tenu() {
        List<String> printed = new java.util.ArrayList<>();
        Console console = new Console(printed::add,
                ConsoleEncoding.forCharset(java.nio.charset.StandardCharsets.UTF_8));
        TransportJournal journal = new TransportJournal();
        journal.attempted(WEBSOCKET, WS);
        journal.established(WEBSOCKET);

        int code = RunnerMain.transportVerdict(console, journal,
                new java.util.concurrent.atomic.AtomicBoolean(true));

        assertEquals(0, code);
        assertEquals(List.of(), printed, "un arrêt qui se passe bien n'ajoute rien : " + printed);
    }
}
