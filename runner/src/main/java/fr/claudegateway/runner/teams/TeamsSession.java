package fr.claudegateway.runner.teams;

import java.util.function.Consumer;

/**
 * La liaison Teams du runner, <b>ouverte une fois et gardée</b> (F-87 / SF-87-03).
 *
 * <p>Se rattacher coûte deux requêtes et une socket ; le refaire à chaque appel d'outil serait
 * gâché, et surtout perdrait l'observation du réseau entre deux appels — or ce qui est observé
 * <b>pendant</b> que la page travaille est exactement ce que le volet lit. La session garde donc la
 * liaison, et la rouvre d'elle-même si elle a été perdue (fenêtre fermée, poste mis en veille).</p>
 *
 * <p>C'est aussi le point d'entrée que F-88 utilisera pour ses outils de lecture : une seule liaison
 * pour tout le volet.</p>
 */
public final class TeamsSession implements AutoCloseable {

    private final int port;
    private final TeamsAdapter adapter;
    private final TeamsFirstUseNotice notice;
    private final Consumer<String> say;
    private final Attacher attacher;

    private BrowserLink link;

    public TeamsSession(int port, TeamsAdapter adapter, Consumer<String> say) {
        this(port, adapter, say, BrowserLink::attach);
    }

    TeamsSession(int port, TeamsAdapter adapter, Consumer<String> say, Attacher attacher) {
        this.port = port;
        this.adapter = adapter;
        this.say = say;
        this.attacher = attacher;
        this.notice = new TeamsFirstUseNotice();
    }

    /**
     * La liaison, ouverte si besoin.
     *
     * @throws BrowserLinkException avec le remède complet quand elle ne peut pas l'être
     */
    public synchronized BrowserLink link() {
        if (link != null && link.isOpen()) {
            return link;
        }
        // D3 : ce dont la liaison a besoin est dit au PREMIER usage, avant de s'y mettre.
        notice.announceOnce(say);
        link = attacher.attach(port, adapter, say);
        return link;
    }

    /** L'annonce de premier usage, pour la rendre aussi dans le résultat de l'outil. */
    public TeamsFirstUseNotice notice() {
        return notice;
    }

    public int port() {
        return port;
    }

    public TeamsAdapter adapter() {
        return adapter;
    }

    @Override
    public synchronized void close() {
        if (link != null) {
            link.close();
            link = null;
        }
    }

    /** Le rattachement, injecté pour que la session s'éprouve sans navigateur. */
    @FunctionalInterface
    interface Attacher {
        BrowserLink attach(int port, TeamsAdapter adapter, Consumer<String> say);
    }
}
