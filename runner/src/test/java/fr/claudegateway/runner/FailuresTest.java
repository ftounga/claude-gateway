package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Description des pannes réseau (F-38 / SF-38-24).
 *
 * <p>Écrits après ce qu'a vu un client : {@code Appel d'appairage impossible (…) : null}. Un proxy
 * obligatoire, une interception TLS, un DNS muet et un délai dépassé produisaient tous le même
 * mot.</p>
 */
class FailuresTest {

    @Test
    @DisplayName("une exception sans message se décrit quand même")
    void anExceptionWithoutMessageIsStillDescribed() {
        // Le cas exact du client : IOException nue, dont getMessage() rend null.
        String described = Failures.describe(new IOException());

        assertFalse(described.isBlank(), "une description vide vaut le null d'origine");
        assertTrue(described.contains("IOException"), described);
        assertFalse(described.contains("null"), described);
    }

    @Test
    @DisplayName("le message est joint au type quand il existe")
    void theMessageJoinsTheType() {
        String described = Failures.describe(new ConnectException("Connection timed out"));

        assertTrue(described.contains("ConnectException"), described);
        assertTrue(described.contains("Connection timed out"), described);
    }

    @Test
    @DisplayName("la cause est remontée")
    void theCauseIsShown() {
        String described = Failures.describe(
                new IOException(new ConnectException("Connection refused")));

        assertTrue(described.contains("causé par"), described);
        assertTrue(described.contains("Connection refused"), described);
    }

    @Test
    @DisplayName("le message auto-généré par la JVM n'est pas répété")
    void theJvmGeneratedMessageIsNotRepeated() {
        // Quand une exception enveloppe une autre sans message propre, la JVM lui donne pour message
        // le toString() de sa cause. Sans garde, la description écrivait deux fois la même ligne :
        // « IOException: java.net.ConnectException: … , causé par : ConnectException: … ».
        String described = Failures.describe(
                new IOException(new ConnectException("Connection timed out")));

        assertEquals(1, countOccurrences(described, "Connection timed out"), described);
        assertTrue(described.startsWith("IOException, causé par"), described);
    }

    @Test
    @DisplayName("la chaîne des causes est bornée")
    void theCauseChainIsBounded() {
        Throwable deep = new IOException("racine");
        for (int i = 0; i < 10; i++) {
            deep = new IOException("niveau " + i, deep);
        }

        // D3 : trois niveaux suffisent à atteindre la cause racine dans les piles réseau de la JVM ;
        // au-delà, on ajoute du bruit à un message lu dans une console.
        assertEquals(2, countOccurrences(Failures.describe(deep), "causé par"));
    }

    @Test
    @DisplayName("une cause cyclique ne fait pas boucler la description")
    void aCyclicCauseTerminates() {
        // Rare, mais une pile réseau qui se ré-enveloppe l'a déjà produit. Une boucle infinie ici
        // figerait le runner au moment précis où il tente d'expliquer une panne.
        IOException a = new IOException("a");
        IOException b = new IOException("b", a);
        a.initCause(b);

        String described = Failures.describe(b);

        assertTrue(described.contains("b"), described);
    }

    @Test
    @DisplayName("la piste TLS ne tombe que sur un échec TLS")
    void theTlsHintOnlyFiresOnTls() {
        String tls = Failures.hint(new IOException(new SSLHandshakeException("PKIX path building")));
        assertTrue(tls.contains("truststore"), tls);
        assertTrue(tls.contains("?"), "la piste doit rester une question (D1) : " + tls);

        // Sur une autre panne, cette piste enverrait chercher un certificat inexistant.
        assertFalse(Failures.hint(new IOException("autre chose")).contains("truststore"));
    }

    @Test
    @DisplayName("la piste DNS nomme la résolution, pas le certificat")
    void theDnsHintNamesResolution() {
        String hint = Failures.hint(new UnknownHostException("portal.example.com"));

        assertTrue(hint.contains("résolu"), hint);
        assertFalse(hint.contains("truststore"), hint);
    }

    @Test
    @DisplayName("la non-résolution NIO est reconnue comme un problème de nom")
    void theNioFlavourOfDnsFailureIsRecognised() {
        // Le client HTTP de la JVM ne remonte pas UnknownHostException mais
        // UnresolvedAddressException : c'est cette forme-là qu'a rencontrée le poste d'entreprise
        // qui ne résolvait pas les noms publics.
        // Et surtout : enveloppée dans une ConnectException, comme le fait réellement la JVM. Sans
        // recherche du plus spécifique d'abord, la piste « sortie bloquée » l'emporterait et
        // enverrait chercher un proxy alors que c'est le DNS qui est muet.
        String hint = Failures.hint(new ConnectException(
                "no further information"));
        assertTrue(hint.contains("Sortie réseau"), hint);

        ConnectException wrapped = new ConnectException("no further information");
        wrapped.initCause(new java.nio.channels.UnresolvedAddressException());
        assertTrue(Failures.hint(wrapped).contains("résolu"), Failures.hint(wrapped));
    }

    @Test
    @DisplayName("la piste réseau rappelle les variables que le runner lit vraiment")
    void theNetworkHintNamesTheVariablesWeRead() {
        String hint = Failures.hint(new ConnectException("Connection timed out"));

        // Le runner lit HTTPS_PROXY/HTTP_PROXY/NO_PROXY et rien d'autre : un proxy configuré par
        // fichier PAC ou réglage Windows lui est invisible. Le dire évite une heure de recherche.
        assertTrue(hint.contains("HTTPS_PROXY"), hint);
        assertTrue(hint.contains("PAC"), hint);
    }

    // F-45 / SF-45-04 - la signature du 407, reconnue partout ou une exception remonte.

    @Test
    @DisplayName("un tunnel refuse en 407 est reconnu comme une authentification proxy")
    void aRefusedTunnelIsProxyAuthentication() {
        // La forme exacte que prend le cas rencontre chez le client : la JVM ne produit aucune
        // reponse a inspecter, seulement ce message.
        assertTrue(Failures.isProxyAuthRequired(new IOException("Tunnel failed, got: 407")));
        assertTrue(Failures.isProxyAuthRequired(new IOException("Proxy Authentication Required")));
        assertTrue(Failures.isProxyAuthRequired(
                new IOException("enveloppe", new IOException("tunnel failed, got: 407"))));
    }

    @Test
    @DisplayName("un 407 isole ne suffit pas : ce pourrait etre un numero de port")
    void aBare407IsNotEnough() {
        // Reconnaissance resserree : sans « proxy » ni « tunnel » a cote, 407 ne prouve rien.
        assertFalse(Failures.isProxyAuthRequired(new IOException("Connect to host:407 failed")));
        assertFalse(Failures.isProxyAuthRequired(new IOException("Connection reset")));
        assertFalse(Failures.isProxyAuthRequired(null));
    }

    @Test
    @DisplayName("la piste du 407 nomme le remede, et prime sur les autres")
    void theProxyAuthHintNamesTheWayOut() {
        String hint = Failures.hint(new IOException("Tunnel failed, got: 407"));

        assertTrue(hint.contains("NTLM"), hint);
        assertTrue(hint.contains("Kerberos"), hint);
        assertTrue(hint.contains("SSPI"), hint);
        assertTrue(hint.contains("cntlm"), hint);
    }

    @Test
    @DisplayName("les pistes existantes sont conservees : l'ajout du 407 est purement additif")
    void theExistingHintsAreUntouched() {
        assertTrue(Failures.hint(new UnknownHostException("portal.example.com")).contains("résolu"));
        assertTrue(Failures.hint(new ConnectException("Connection timed out"))
                .contains("Sortie réseau"));
        assertTrue(Failures.hint(new IOException("autre chose")).isEmpty());
    }

    @Test
    @DisplayName("une exception absente ne produit jamais de vide")
    void aNullThrowableStillSaysSomething() {
        assertFalse(Failures.describe(null).isBlank());
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
