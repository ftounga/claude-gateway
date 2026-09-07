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
 * <p><b>Toute</b> réponse HTTP vaut « joignable », y compris un 404 (D1) : le contrôle répond à une
 * seule question — « ce terminal sort-il jusqu'à cette adresse ? ». Juger le code en ferait un test
 * de santé, qui bloquerait un runner parfaitement fonctionnel le jour où un endpoint change.</p>
 */
public final class NetworkPreflight {

    /** Au-delà, on fait attendre quelqu'un devant un terminal muet. */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final OperatingSystem os;

    public NetworkPreflight(HttpClient httpClient, OperatingSystem os) {
        this.httpClient = httpClient;
        this.os = os;
    }

    /**
     * Joint la gateway. Rend {@code null} si elle répond, ou le message à afficher sinon.
     *
     * @param gateway URL de la gateway, telle qu'elle a été normalisée par la configuration
     */
    public String check(String gateway) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(gateway + "/runner/download/formats"))
                .timeout(TIMEOUT)
                .GET()
                .build();
        try {
            // La réponse n'est pas lue : son existence suffit. On ne consomme pas un corps dont on
            // n'a que faire, sur un lien peut-être lent.
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return null;
        } catch (IOException unreachable) {
            return message(gateway, unreachable);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null; // Interruption : ce n'est pas un verdict réseau, on laisse la suite décider.
        }
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
