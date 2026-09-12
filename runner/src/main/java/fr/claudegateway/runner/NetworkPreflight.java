package fr.claudegateway.runner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Contrôle de vol réseau (F-38 / SF-38-25) : le runner vérifie qu'il <b>atteint</b> la gateway avant
 * de tenter l'appairage.
 *
 * <p>Sans lui, l'échec survenait au milieu d'une opération métier — l'appairage — alors que la cause
 * n'a rien de métier : le poste ne sort pas sur Internet. Ce qui n'a rien à voir avec l'appairage ne
 * doit pas échouer pendant l'appairage (D4).</p>
 *
 * <p>Toute réponse <b>de la gateway</b> vaut « joignable », y compris un 404 (D1) : le contrôle
 * répond à une seule question — « ce terminal sort-il jusqu'à cette adresse ? ». Juger le code en
 * ferait un test de santé, qui bloquerait un runner parfaitement fonctionnel le jour où un endpoint
 * change.</p>
 *
 * <p><b>Le 407 est l'exception</b> (F-45 / SF-45-04) : il ne vient pas de la gateway mais du
 * <b>proxy</b>, qui n'a rien transmis. Le compter comme un succès laisserait le runner échouer trois
 * lignes plus loin, à l'appairage — exactement ce que ce contrôle existe pour éviter.</p>
 */
public final class NetworkPreflight {

    /** Au-delà, on fait attendre quelqu'un devant un terminal muet. */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** Statut du proxy qui exige une authentification : la réponse ne vient pas de la gateway. */
    private static final int PROXY_AUTH_REQUIRED = Failures.PROXY_AUTH_REQUIRED;

    /** Proxy exposé par défaut par un relais local d'authentification (`px`, `cntlm`). */
    private static final String LOCAL_RELAY = "http://127.0.0.1:3128";

    private final HttpClient httpClient;
    private final OperatingSystem os;

    public NetworkPreflight(HttpClient httpClient, OperatingSystem os) {
        this.httpClient = httpClient;
        this.os = os;
    }

    /**
     * Verdict du contrôle de vol (F-80 / SF-80-01).
     *
     * <p>Le message seul ne suffisait plus : un échec <b>TLS</b> n'appelle pas la même suite qu'un
     * DNS muet. Le premier prouve que la connexion a abouti et que le serveur a présenté un
     * certificat — donc qu'il y a quelque chose à lire et à nommer ; le second ne laisse rien à
     * regarder.</p>
     *
     * @param message message à afficher, ou {@code null} quand la gateway répond
     * @param tlsFailure vrai quand l'échec est une poignée de main TLS
     */
    public record Verdict(String message, boolean tlsFailure) {

        /** Verdict de succès : la gateway a répondu. */
        static final Verdict REACHABLE = new Verdict(null, false);

        /** Vrai quand le runner ne doit pas continuer. */
        public boolean unreachable() {
            return message != null;
        }
    }

    /**
     * Joint la gateway. Rend {@code null} si elle répond, ou le message à afficher sinon.
     *
     * <p>Conservée pour les appelants qui n'ont que faire de la <b>nature</b> de l'échec.</p>
     *
     * @param gateway URL de la gateway, telle qu'elle a été normalisée par la configuration
     */
    public String check(String gateway) {
        return verify(gateway).message();
    }

    /**
     * Joint la gateway et <b>qualifie</b> son échec (F-80 / SF-80-01).
     *
     * @param gateway URL de la gateway, telle qu'elle a été normalisée par la configuration
     */
    public Verdict verify(String gateway) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(gateway + "/runner/download/formats"))
                .timeout(TIMEOUT)
                .GET()
                .build();
        try {
            // Le corps n'est pas lu : son existence suffit. On ne consomme pas un contenu dont on
            // n'a que faire, sur un lien peut-être lent. Le STATUT, lui, est désormais regardé —
            // pour le seul 407, qui ne vient pas du serveur qu'on cherche à joindre (SF-45-04).
            HttpResponse<Void> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == PROXY_AUTH_REQUIRED
                    ? new Verdict(proxyAuthMessage(gateway), false)
                    : Verdict.REACHABLE;
        } catch (IOException unreachable) {
            // Sur une cible en HTTPS, le proxy refuse le tunnel CONNECT et la JVM lève une
            // IOException : il n'y a JAMAIS de réponse à inspecter. Ne traiter que le statut ne
            // couvrirait donc pas le cas réellement rencontré chez le client (D2).
            if (Failures.isProxyAuthRequired(unreachable)) {
                // Un 407 refusé par le proxy peut voyager dans une SSLException : c'est le proxy qui
                // parle, pas le serveur. Il est donc traité D'ABORD, et n'appelle aucune sonde.
                return new Verdict(proxyAuthMessage(gateway), false);
            }
            return new Verdict(message(gateway, unreachable), Failures.isTlsHandshake(unreachable));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            // Interruption : ce n'est pas un verdict réseau, on laisse la suite décider.
            return Verdict.REACHABLE;
        }
    }

    /**
     * Message du 407 : ce qui refuse, pourquoi aucune version du runner n'y changera rien, et les
     * <b>deux</b> issues — l'une côté DSI, l'autre côté poste.
     */
    private String proxyAuthMessage(String gateway) {
        String nl = System.lineSeparator();
        return "Le proxy d'entreprise refuse la connexion : 407, authentification requise ("
                + gateway + ")." + nl
                + nl
                + "Ce n'est pas la gateway qui repond, c'est le proxy : il n'a rien transmis." + nl
                + nl
                // D3 : sans cette phrase, le premier reflexe est de chercher une mise a jour, puis
                // d'ouvrir un ticket au produit. La cause est dans la JVM.
                + "Si ce proxy exige une authentification INTEGREE (NTLM ou Kerberos), le runner ne"
                + nl
                + "pourra pas la porter, quelle que soit sa version : la machine virtuelle Java n'a"
                + nl
                + "aucun support SSPI, et l'authentification Basic est desactivee sur les tunnels"
                + nl
                + "CONNECT depuis Java 8u111." + nl
                + nl
                // D4 : « faites ouvrir sans authentification » se refuse dans beaucoup
                // d'entreprises ; le relais local, lui, ne demande rien a personne.
                + "Deux issues :" + nl
                + "  1. faire exclure ce domaine de l'authentification proxy par votre DSI ;" + nl
                + "  2. lancer un relais local qui porte l'authentification integree et expose, lui,"
                + nl
                + "     un proxy SANS authentification (px, cntlm), puis le declarer ici :" + nl
                + indent(os.declareProxy(LOCAL_RELAY)) + nl
                + nl
                + "L'ecran « Connecter une machine » produit la fiche a transmettre a votre DSI.";
    }

    /** Décale un bloc de commandes, pour qu'il se distingue du texte dans une console. */
    private static String indent(String block) {
        String nl = System.lineSeparator();
        StringBuilder text = new StringBuilder();
        for (String line : block.split("\\R", -1)) {
            if (text.length() > 0) {
                text.append(nl);
            }
            text.append("       ").append(line);
        }
        return text.toString();
    }

    /** Message en trois parties : ce qui a échoué, la piste, les gestes du système courant. */
    private String message(String gateway, Throwable cause) {
        String nl = System.lineSeparator();
        StringBuilder text = new StringBuilder();
        text.append("La gateway n'est pas joignable (").append(gateway).append(") : ")
                .append(Failures.describe(cause));
        String hint = Failures.hint(cause);
        if (!hint.isEmpty()) {
            text.append(nl).append(hint);
        }
        // D3 : « mon navigateur y arrive » est la première objection, et c'est justement l'indice.
        // Le dire explicitement fait gagner l'aller-retour que ce diagnostic a coûté.
        text.append(nl).append(nl)
                .append("Votre navigateur atteint peut-etre cette adresse alors que ce terminal ne "
                        + "le peut pas :").append(nl)
                .append("c'est le cas quand un proxy d'entreprise est configure cote systeme mais "
                        + "absent de ce shell.").append(nl).append(nl)
                .append(os.proxyInstructions());
        return text.toString();
    }
}
