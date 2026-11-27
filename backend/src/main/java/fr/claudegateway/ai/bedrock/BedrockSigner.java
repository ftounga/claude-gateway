package fr.claudegateway.ai.bedrock;

import java.net.URI;
import java.util.List;
import java.util.Map;

import fr.claudegateway.ai.AIProviderUnavailableException;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;

/**
 * Signature SigV4 d'un appel Bedrock (F-277 / SF-277-01). Séparée pour que les tests vérifient l'adaptateur
 * sans identifiants AWS.
 */
@FunctionalInterface
public interface BedrockSigner {

    /**
     * Signe la requête et rend les en-têtes à poser (dont {@code Authorization} et {@code X-Amz-Date}).
     *
     * @throws AIProviderUnavailableException si aucun identifiant AWS n'est disponible
     */
    Map<String, List<String>> sign(URI uri, Map<String, List<String>> headers, byte[] body);

    /**
     * Signataire réel : chaîne d'identifiants par défaut du SDK AWS v2 (IRSA dans le pod, profil en local),
     * service {@code bedrock}. Les identifiants ne sont jamais journalisés.
     */
    static BedrockSigner aws(String region) {
        return aws(region, DefaultCredentialsProvider.create());
    }

    static BedrockSigner aws(String region, AwsCredentialsProvider credentialsProvider) {
        return aws(region, "bedrock", credentialsProvider);
    }

    /**
     * Même signataire pour un autre service AWS (F-279 / SF-279-01 : Cost Explorer, service {@code ce},
     * région {@code us-east-1}).
     */
    static BedrockSigner aws(String region, String service, AwsCredentialsProvider credentialsProvider) {
        AwsV4HttpSigner signer = AwsV4HttpSigner.create();
        return (uri, headers, body) -> {
            AwsCredentials credentials;
            try {
                credentials = credentialsProvider.resolveCredentials();
            } catch (RuntimeException ex) {
                throw new AIProviderUnavailableException("bedrock".equals(service)
                        ? "Le canal Bedrock n'est pas configuré (identifiants AWS introuvables)."
                        : "Identifiants AWS introuvables.");
            }
            SdkHttpRequest request = SdkHttpRequest.builder()
                    .method(SdkHttpMethod.POST)
                    .uri(uri)
                    .headers(headers)
                    .build();
            SignedRequest signed = signer.sign(r -> r
                    .identity(credentials)
                    .request(request)
                    .payload(ContentStreamProvider.fromByteArray(body))
                    .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, service)
                    .putProperty(AwsV4HttpSigner.REGION_NAME, region));
            return signed.request().headers();
        };
    }
}
