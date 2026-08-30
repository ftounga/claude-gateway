package fr.claudegateway.runner.relay;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration du <b>relais interne</b> entre pods backend (F-38 / SF-38-12).
 *
 * <p>Le relais est <b>désactivé tant que {@link #getSecret()} est vide</b> : aucun connecteur
 * supplémentaire, aucun contrôleur interne, aucun appel sortant. C'est le cas en dev, en tests, et en
 * production tant que le secret n'est pas configuré — le comportement est alors exactement celui
 * d'avant SF-38-12 ({@code runner_not_on_this_node}). Il n'existe <b>aucun</b> repli non
 * authentifié.</p>
 */
@ConfigurationProperties(prefix = "app.runner.relay")
public class RunnerRelayProperties {

    /**
     * Port du <b>second connecteur TCP</b> qui sert les routes {@code /internal/**}. Il est
     * volontairement absent du Service public : l'ingress route {@code /api} vers le port 8080, et
     * le context-path {@code /api} fait que toute route Spring vivrait sous {@code /api/internal/...}
     * — donc joignable depuis Internet si elle était publiée sur 8080.
     */
    private int port = 8081;

    /** Secret partagé entre pods. Vide ⇒ relais entièrement désactivé. Jamais journalisé. */
    private String secret = "";

    /** Adresse de ce pod (downward API {@code status.podIP}). Vide hors cluster. */
    private String selfAddress = "";

    /** Service headless résolvant les autres pods backend (diffusion, SF-38-13). */
    private String peersHost = "claude-gateway-backend-internal";

    /** Délai d'établissement de connexion vers un pair, en millisecondes. */
    private long connectTimeoutMs = 2_000L;

    /**
     * Délai de lecture d'une réponse de pair, en millisecondes. Strictement supérieur au plus grand
     * {@code timeoutMs + grâce} possible ({@code bash} : 120 000 + 5 000) pour que le pod
     * propriétaire gagne toujours la course et écrive son {@code result}.
     */
    private long readTimeoutMs = 135_000L;

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret == null ? "" : secret;
    }

    public String getSelfAddress() {
        return selfAddress;
    }

    public void setSelfAddress(String selfAddress) {
        this.selfAddress = selfAddress == null ? "" : selfAddress;
    }

    public String getPeersHost() {
        return peersHost;
    }

    public void setPeersHost(String peersHost) {
        this.peersHost = peersHost;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(long readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    /** Vrai si le relais est configuré (secret présent). */
    public boolean isEnabled() {
        return !secret.isBlank();
    }

    /**
     * Adresse de base du connecteur interne de <b>ce</b> pod ({@code http://{POD_IP}:{port}}), ou
     * {@code ""} hors cluster. Sert la garde anti-auto-appel du routeur : on ne se relaie jamais à
     * soi-même.
     */
    public String selfBaseUrl() {
        return selfAddress.isBlank() ? "" : "http://" + selfAddress.trim() + ":" + port;
    }
}
