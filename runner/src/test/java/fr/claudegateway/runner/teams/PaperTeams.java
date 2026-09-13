package fr.claudegateway.runner.teams;

import java.util.function.Consumer;

/**
 * Un Teams de papier (F-88 / SF-88-01) : un {@link FakeCdpConnection} déjà relié, monté une fois
 * pour tous les tests du catalogue.
 *
 * <p>Il existe pour la même raison que le navigateur de papier de F-87 : <b>nous n'avons aucun
 * compte Teams de test</b>, et il fallait pourtant pouvoir éprouver le recollement, le plafond et
 * les manques. Ce qu'il prouve et ce qu'il ne prouve pas est écrit dans
 * {@code src/test/resources/teams/PROVENANCE.md}.</p>
 */
final class PaperTeams {

    static final String THREAD = "19:fabrique@thread.v2";
    static final String OTHER_THREAD = "19:fabrique-tete-a-tete@unq.gbl.spaces";
    static final String MESSAGES_URL = "https://teams.microsoft.com/api/chatsvc/emea/v1/users/ME/"
            + "conversations/" + THREAD + "/messages";

    final FakeCdpConnection browser = new FakeCdpConnection();
    final TeamsAdapter adapter = TeamsAdapters.current();
    final BrowserLink link;

    PaperTeams() {
        this(null);
    }

    PaperTeams(Consumer<String> say) {
        this.link = BrowserLink.attach(9222, adapter,
                url -> url.endsWith("/json/version")
                        ? "{\"Browser\":\"Chrome/140.0.0.0\"}"
                        : "[{\"id\":\"1\",\"type\":\"page\","
                                + "\"url\":\"https://teams.microsoft.com/v2/\","
                                + "\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/d/1\"}]",
                wsUrl -> browser, say);
        // Une page finit par atteindre le début du fil : c'est ce qui distingue « tout lu » de
        // « la page refuse de remonter », et le récolteur doit savoir vivre avec les deux.
        browser.stopsWhenNothingLeft();
    }

    /** Une réponse déjà reçue par la page, avant tout geste. */
    PaperTeams already(String requestId, String url, String sample) {
        browser.emitResponse(requestId, url, TeamsSamples.read(sample).toString());
        return this;
    }

    /** Une réponse que la page livrera au prochain défilement. */
    PaperTeams onScroll(String requestId, String url, String sample) {
        browser.deliverOnScroll(requestId, url, TeamsSamples.read(sample).toString());
        return this;
    }

    TeamsLedger ledger() {
        return new TeamsLedger(adapter);
    }

    TeamsHarvester harvester(TeamsLedger ledger) {
        return new TeamsHarvester(link, ledger, new PageGestures(link, millis -> { }));
    }

    /**
     * Le montage avec les outils fichiers (F-108 / SF-108-03) : dossier du volet et dossiers
     * synchronisés donnés par le test.
     */
    TeamsTools toolsWithFiles(java.nio.file.Path hostRoot, SyncedLibraries synced) {
        return tools().withFiles(new TeamsWorkFolder(hostRoot), synced, message -> { });
    }

    /** Le montage complet des outils, avec une liaison qui aboutit sur ce Teams de papier. */
    TeamsTools tools() {
        return new TeamsTools(new TeamsSession(9222, adapter, message -> { },
                (port, adapterArg, say) -> link), millis -> { });
    }
}
