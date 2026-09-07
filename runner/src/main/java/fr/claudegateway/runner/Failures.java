package fr.claudegateway.runner;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.IdentityHashMap;
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

    private Failures() {
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
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SSLException) {
                return "Certificat non reconnu par Java — un proxy interceptant le TLS ? "
                        + "Le truststore d'entreprise se déclare par "
                        + "-Djavax.net.ssl.trustStore=<fichier>.";
            }
            if (current instanceof UnknownHostException) {
                return "Nom d'hôte non résolu — DNS, ou proxy d'entreprise obligatoire ?";
            }
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
