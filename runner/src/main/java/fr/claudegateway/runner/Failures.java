package fr.claudegateway.runner;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLException;

/**
 * Description lisible d'une panne réseau (F-38 / SF-38-24).
 *
 * <p>Écrite après ce qu'a vu un client au troisième obstacle de son installation :</p>
 *
 * <pre>
 * [19:32:52] ERREUR  Appel d'appairage impossible (https://…/runner/pair) : null
 * </pre>
 *
 * <p>{@code e.getMessage()} valait {@code null} — certaines {@code IOException} n'en portent pas —
 * et rien n'affichait le <b>type</b> ni la <b>cause</b>. Un proxy d'entreprise obligatoire, une
 * interception TLS, un DNS muet et un délai dépassé produisaient tous le même mot : {@code null}.
 * Quatre situations, quatre gestes différents, aucune façon de les distinguer.</p>
 *
 * <p>Cette classe <b>décrit</b>, elle ne diagnostique pas (D1) : la piste éventuelle est formulée
 * comme une question. Affirmer « votre proxy bloque » alors que le câble est débranché ferait
 * perdre plus de temps que {@code null}.</p>
 */
public final class Failures {

    /** Au-delà, on répète du bruit dans un message que quelqu'un doit lire dans une console. */
    private static final int MAX_CAUSES = 3;

    /** Statut HTTP « authentification proxy requise » (F-45 / SF-45-04). */
    public static final int PROXY_AUTH_REQUIRED = 407;

    private Failures() {
    }

    /**
     * Vrai quand une exception porte la <b>signature d'un 407</b> (F-45 / SF-45-04).
     *
     * <p>Sur une cible en HTTPS, un proxy qui exige une authentification refuse le tunnel
     * {@code CONNECT} : la JVM lève alors une {@code IOException} — {@code "Tunnel failed, got: 407"}
     * — et il n'existe <b>jamais</b> de {@code HttpResponse} à inspecter. C'est la seule forme sous
     * laquelle le cas rencontré chez le client se présente.</p>
     *
     * <p>Reconnaissance volontairement <b>resserrée</b> : {@code 407} seul apparaîtrait dans un
     * numéro de port ou une taille. On exige qu'il voisine avec « proxy » ou « tunnel », ou bien la
     * mention explicite d'une authentification proxy.</p>
     */
    public static boolean isProxyAuthRequired(Throwable error) {
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable current = error;
                current != null && seen.put(current, Boolean.TRUE) == null;
                current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String text = message.toLowerCase(Locale.ROOT);
            if (text.contains("proxy authentication")) {
                return true;
            }
            if (text.contains("407") && (text.contains("proxy") || text.contains("tunnel"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Vrai quand l'échec est une <b>poignée de main TLS</b> (F-80 / SF-80-01).
     *
     * <p>Ce cas se distingue de tous les autres : il prouve que la connexion a <b>abouti</b> et que
     * le serveur a présenté un certificat. Il y a donc une chaîne à lire et un émetteur à nommer, là
     * où un DNS muet ou un port fermé ne laissent rien à regarder.</p>
     */
    public static boolean isTlsHandshake(Throwable error) {
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable current = error;
                current != null && seen.put(current, Boolean.TRUE) == null;
                current = current.getCause()) {
            if (current instanceof SSLException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Description <b>jamais vide</b> : type, message s'il existe, et chaîne des causes.
     *
     * @param error exception à décrire ; {@code null} rend une mention explicite
     */
    public static String describe(Throwable error) {
        if (error == null) {
            return "cause inconnue";
        }
        StringBuilder text = new StringBuilder();
        // IdentityHashMap plutôt qu'un Set : deux exceptions peuvent être « égales » sans être la
        // même instance, et c'est bien le cycle d'instances que l'on cherche à couper.
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < MAX_CAUSES && seen.put(current, Boolean.TRUE) == null) {
            if (depth > 0) {
                text.append(", causé par : ");
            }
            text.append(current.getClass().getSimpleName());
            String message = current.getMessage();
            // Quand une exception enveloppe une autre sans message propre, la JVM lui donne pour
            // message le `toString()` de sa cause. L'afficher reviendrait à écrire deux fois la même
            // ligne — « IOException: java.net.ConnectException: … , causé par : ConnectException: … ».
            Throwable cause = current.getCause();
            boolean generated = cause != null && message != null && message.equals(cause.toString());
            if (message != null && !message.isBlank() && !generated) {
                text.append(": ").append(message.trim());
            }
            current = cause;
            depth++;
        }
        return text.toString();
    }

    /**
     * Piste de résolution pour les pannes dont le type appelle un geste précis, ou chaîne vide.
     *
     * <p>Toujours une <b>question</b>, jamais une conclusion (D1).</p>
     */
    public static String hint(Throwable error) {
        // Le 407 d'abord : il ne decrit pas une panne de transport mais un refus explicite, et son
        // remede n'a rien a voir avec les autres (F-45 / SF-45-04).
        if (isProxyAuthRequired(error)) {
            return "Authentification proxy exigee (407) — le runner ne porte ni NTLM ni Kerberos "
                    + "(la JVM n'a pas de support SSPI). Exclusion du domaine cote DSI, ou relais "
                    + "local (px, cntlm) declare dans HTTPS_PROXY.";
        }
        // Deux passes, du PLUS SPÉCIFIQUE au plus général. La pile réseau de la JVM enveloppe une
        // non-résolution dans une ConnectException : chercher en une seule passe rendrait la piste
        // « sortie bloquée » avant d'avoir vu le vrai motif, et enverrait chercher un proxy quand
        // c'est le DNS qui est muet.
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SSLException) {
                // F-80 / SF-80-01 : la prescription « -Djavax.net.ssl.trustStore=<fichier> » a
                // disparu. Elle était exacte et inutilisable — le fichier n'existe pas, et personne
                // ne sait qu'il faut extraire une racine du magasin système, la convertir et la
                // ranger dans une copie de cacerts (D2 du cadrage F-80). Ce qui la remplace est
                // affiché juste en dessous par la sonde : l'émetteur, NOMMÉ.
                return "Le certificat présenté n'est signé par aucune autorité connue de Java — "
                        + "un proxy qui déchiffre le trafic ?";
            }
            // UnresolvedAddressException : la forme que prend la non-résolution dans la pile NIO
            // du client HTTP de la JVM. C'est elle, et non UnknownHostException, qui est remontée
            // quand un poste d'entreprise ne résout pas les noms publics — le cas rencontré.
            if (current instanceof UnknownHostException
                    || current instanceof UnresolvedAddressException) {
                return "Nom d'hôte non résolu — DNS, ou proxy d'entreprise obligatoire ?";
            }
        }
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ConnectException || current instanceof SocketTimeoutException) {
                return "Sortie réseau bloquée ? Le runner lit HTTPS_PROXY, HTTP_PROXY et NO_PROXY ; "
                        + "un proxy configuré ailleurs (fichier PAC, réglage Windows) lui est "
                        + "invisible.";
            }
        }
        return "";
    }

    /** Description suivie de sa piste, quand il y en a une. Pratique pour un message d'une ligne. */
    public static String describeWithHint(Throwable error) {
        String hint = hint(error);
        return hint.isEmpty() ? describe(error)
                : describe(error) + System.lineSeparator() + "         " + hint;
    }
}
