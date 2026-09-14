package fr.claudegateway.mcp;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

/**
 * Source de clés JWK du serveur d'autorisation MCP (F-112 / SF-112-02), avec <b>rotation</b>.
 *
 * <p>Une clé RSA <b>courante</b> signe les nouveaux jetons ; les clés précédentes sont conservées pour
 * valider les jetons encore en circulation (chacune a son {@code kid}). {@link #rotate()} génère une
 * nouvelle clé courante et repousse l'ancienne au rang des clés de validation. Les clés sont en
 * mémoire (régénérées au démarrage) : la persistance des clés relève de l'infrastructure et n'est pas
 * dans le périmètre de la fondation — un redémarrage invalide les jetons d'accès courts (15 min), pas
 * les rafraîchissements, qui repassent par le serveur d'autorisation.</p>
 */
@Component
public class McpJwkSource implements JWKSource<SecurityContext> {

    private static final Logger log = LoggerFactory.getLogger(McpJwkSource.class);
    private static final int MAX_RETAINED_KEYS = 3;

    /** La clé courante est en tête de liste ; les suivantes ne servent qu'à la validation. */
    private final CopyOnWriteArrayList<RSAKey> keys = new CopyOnWriteArrayList<>();

    public McpJwkSource() {
        keys.add(generateRsaKey());
    }

    /** Le {@code kid} de la clé courante, utilisé par l'encodeur pour signer. */
    public String currentKeyId() {
        return keys.get(0).getKeyID();
    }

    /**
     * Fait tourner la clé de signature : une nouvelle clé courante est générée, l'ancienne reste
     * disponible pour la validation (jusqu'à {@link #MAX_RETAINED_KEYS} clés conservées).
     */
    public synchronized void rotate() {
        keys.add(0, generateRsaKey());
        while (keys.size() > MAX_RETAINED_KEYS) {
            keys.remove(keys.size() - 1);
        }
        log.info("MCP : rotation de la clé de signature du serveur d'autorisation (kid courant {}).",
                currentKeyId());
    }

    @Override
    public List<JWK> get(JWKSelector jwkSelector, SecurityContext context) throws KeySourceException {
        return jwkSelector.select(new JWKSet(List.copyOf(keys)));
    }

    private static RSAKey generateRsaKey() {
        KeyPair keyPair = generateRsaKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(UUID.randomUUID().toString())
                .build();
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException("Impossible de générer la paire de clés RSA du serveur MCP", ex);
        }
    }
}
