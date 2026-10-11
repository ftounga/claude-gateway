package fr.claudegateway.runner;

import java.net.http.HttpClient;

/**
 * <b>Le client réseau unique du runner</b> (F-190 / SF-190-06).
 *
 * <p>{@code RunnerMain} construit UN client qui porte le proxy d'entreprise (F-44) et le truststore
 * du poste (F-80 : le {@code cacerts} de la JDK plus le magasin du système, racine Zscaler comprise).
 * Son commentaire le disait : « un seul client, donc un seul truststore, pour TOUT le trafic du
 * runner ». Ce n'était pas vrai : le rapatriement de {@code ffmpeg} et cinq remontées vers la gateway
 * créaient leur propre client nu — sans proxy, sans la racine d'entreprise. Sur le poste EDENRED, le
 * 2026-10-11 : « PKIX path building failed », alors que la même machine joignait la gateway sans
 * peine.</p>
 *
 * <p>Ce point d'accès rend ce client à qui en a besoin. Avant qu'il soit posé (tests, outils lancés
 * hors {@code RunnerMain}), il rend un client par défaut — le comportement d'avant.</p>
 */
public final class RunnerHttp {

    private static volatile HttpClient shared;
    private static volatile HttpClient fallback;

    private RunnerHttp() {
    }

    /** Pose le client du runner. Appelé une fois, par {@code RunnerMain}, dès qu'il est construit. */
    public static void use(HttpClient client) {
        shared = client;
    }

    /** Le client du runner ; à défaut, un client simple qui suit les redirections. */
    public static HttpClient client() {
        HttpClient client = shared;
        if (client != null) {
            return client;
        }
        HttpClient simple = fallback;
        if (simple == null) {
            simple = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            fallback = simple;
        }
        return simple;
    }

    /** Vrai si le client du runner a été posé (pour les tests). */
    static boolean configured() {
        return shared != null;
    }

    /** Retire le client posé (pour les tests). */
    static void reset() {
        shared = null;
    }
}
