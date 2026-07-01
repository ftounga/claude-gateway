package fr.claudegateway.ocr.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextRequest;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextResponse;
import software.amazon.awssdk.services.textract.model.Document;
import software.amazon.awssdk.services.textract.model.DocumentLocation;
import software.amazon.awssdk.services.textract.model.GetDocumentTextDetectionRequest;
import software.amazon.awssdk.services.textract.model.GetDocumentTextDetectionResponse;
import software.amazon.awssdk.services.textract.model.JobStatus;
import software.amazon.awssdk.services.textract.model.S3Object;
import software.amazon.awssdk.services.textract.model.StartDocumentTextDetectionRequest;
import software.amazon.awssdk.services.textract.model.StartDocumentTextDetectionResponse;
import software.amazon.awssdk.services.textract.model.TextractException;

/**
 * Implémentation {@link OcrProvider} pour <b>AWS Textract</b> (F-05). Unique point du code couplé au
 * SDK AWS : le domaine ne dépend que de l'interface {@link OcrProvider} (Provider Independence).
 *
 * <p>Régimes :</p>
 * <ul>
 *   <li><b>Synchrone</b> (images) : {@code DetectDocumentText} avec les octets envoyés directement.</li>
 *   <li><b>Asynchrone</b> (PDF/TIFF) : dépôt de l'objet dans S3 puis {@code StartDocumentTextDetection}
 *       (Textract exige une source S3 pour l'asynchrone) ; complétion via {@code GetDocumentTextDetection}.</li>
 * </ul>
 *
 * <p>Aucune clé/secret n'est présent dans le code : l'authentification AWS passe par la chaîne de
 * credentials par défaut (IRSA en cluster). Aucun secret ni contenu de document n'est journalisé ;
 * les échecs amont sont convertis en messages métier neutres.</p>
 *
 * <p>Activée uniquement lorsque {@code app.ocr.provider=textract} ; sinon {@link StubOcrProvider}.</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.ocr", name = "provider", havingValue = "textract")
public class TextractOcrProvider implements OcrProvider {

    private static final Logger log = LoggerFactory.getLogger(TextractOcrProvider.class);

    private final TextractProperties properties;
    private final TextractClient textractClient;
    private final S3Client s3Client;
    private final ObjectMapper objectMapper;

    public TextractOcrProvider(TextractProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        Region region = Region.of(properties.region());
        // Credentials via chaîne par défaut (IRSA/env) — jamais de secret en dur.
        this.textractClient = TextractClient.builder().region(region).build();
        this.s3Client = S3Client.builder().region(region).build();
    }

    @Override
    public OcrExtraction extractSync(OcrDocument document) {
        try {
            DetectDocumentTextResponse response = textractClient.detectDocumentText(
                    DetectDocumentTextRequest.builder()
                            .document(Document.builder()
                                    .bytes(SdkBytes.fromByteArray(document.content()))
                                    .build())
                            .build());
            List<String> lines = linesOf(response.blocks());
            String text = String.join("\n", lines);
            return new OcrExtraction(text, rawJson(lines));
        } catch (TextractException ex) {
            log.warn("Textract DetectDocumentText en échec (type={})", document.mediaType());
            throw new OcrProviderException("Échec de l'extraction OCR.", ex);
        }
    }

    @Override
    public String startAsync(OcrDocument document) {
        if (!properties.isAsyncConfigured()) {
            throw new OcrProviderUnavailableException("OCR asynchrone non configuré (bucket S3 absent).");
        }
        String key = properties.s3Prefix() + UUID.randomUUID();
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.s3Bucket())
                            .key(key)
                            .contentType(document.mediaType())
                            .build(),
                    RequestBody.fromBytes(document.content()));

            StartDocumentTextDetectionResponse response = textractClient.startDocumentTextDetection(
                    StartDocumentTextDetectionRequest.builder()
                            .documentLocation(DocumentLocation.builder()
                                    .s3Object(S3Object.builder()
                                            .bucket(properties.s3Bucket())
                                            .name(key)
                                            .build())
                                    .build())
                            .build());
            return response.jobId();
        } catch (TextractException | software.amazon.awssdk.services.s3.model.S3Exception ex) {
            log.warn("Soumission Textract asynchrone en échec (type={})", document.mediaType());
            throw new OcrProviderException("Échec de la soumission OCR.", ex);
        }
    }

    @Override
    public OcrJobResult pollAsync(String jobId) {
        try {
            List<String> lines = new ArrayList<>();
            String nextToken = null;
            JobStatus status;
            do {
                GetDocumentTextDetectionResponse response = textractClient.getDocumentTextDetection(
                        GetDocumentTextDetectionRequest.builder()
                                .jobId(jobId)
                                .nextToken(nextToken)
                                .build());
                status = response.jobStatus();
                if (status == JobStatus.IN_PROGRESS) {
                    return OcrJobResult.inProgress();
                }
                lines.addAll(linesOf(response.blocks()));
                nextToken = response.nextToken();
            } while (nextToken != null && !nextToken.isBlank());

            if (status == JobStatus.SUCCEEDED) {
                return OcrJobResult.succeeded(String.join("\n", lines), rawJson(lines));
            }
            // FAILED, PARTIAL_SUCCESS ou tout autre état terminal non-succès.
            return OcrJobResult.failed();
        } catch (TextractException ex) {
            log.warn("Interrogation Textract asynchrone en échec");
            throw new OcrProviderException("Échec de l'interrogation OCR.", ex);
        }
    }

    private static List<String> linesOf(List<Block> blocks) {
        if (blocks == null) {
            return List.of();
        }
        return blocks.stream()
                .filter(b -> b.blockType() == BlockType.LINE && b.text() != null)
                .map(Block::text)
                .collect(Collectors.toList());
    }

    private String rawJson(List<String> lines) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of("provider", "textract", "lines", lines));
        } catch (JsonProcessingException ex) {
            // Sérialisation défensive : ne jamais faire échouer l'extraction pour le brut.
            return "{\"provider\":\"textract\",\"lines\":[]}";
        }
    }
}
