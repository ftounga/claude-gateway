package fr.claudegateway.export;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import fr.claudegateway.chat.Conversation;
import fr.claudegateway.chat.ConversationService;
import fr.claudegateway.chat.Message;
import fr.claudegateway.export.dto.AnswerExportRequest;

/**
 * Orchestration des exports (F-14). Produit un {@link ExportedFile} prêt à télécharger.
 *
 * <p>Isolation multi-tenant : l'export d'une conversation passe par {@link ConversationService}
 * (filtre {@code user_id} ; une conversation d'autrui est indistincte d'une inexistante ⇒ 404).
 * L'export d'une réponse est <b>stateless</b> — il ne rend que les données fournies par l'appelant,
 * sans accès base, donc sans fuite possible entre utilisateurs.</p>
 */
@Service
public class ExportService {

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ConversationService conversationService;
    private final MarkdownExporter markdownExporter;
    private final PdfExporter pdfExporter;

    public ExportService(ConversationService conversationService,
                         MarkdownExporter markdownExporter,
                         PdfExporter pdfExporter) {
        this.conversationService = conversationService;
        this.markdownExporter = markdownExporter;
        this.pdfExporter = pdfExporter;
    }

    /**
     * Exporte une conversation possédée par l'utilisateur.
     *
     * @throws fr.claudegateway.chat.ConversationNotFoundException si absente ou appartenant à autrui
     */
    public ExportedFile exportConversation(UUID userId, UUID conversationId, ExportFormat format) {
        Conversation conversation = conversationService.getOwned(conversationId, userId);
        List<Message> messages = conversationService.messagesOf(conversationId, userId);
        byte[] content = switch (format) {
            case MARKDOWN -> markdownExporter.conversation(conversation, messages)
                    .getBytes(StandardCharsets.UTF_8);
            case PDF -> pdfExporter.conversation(conversation, messages);
        };
        String filename = "conversation-" + conversationId + "." + format.extension();
        return new ExportedFile(filename, format.contentType(), content);
    }

    /** Exporte une réponse documentée fournie par l'appelant (stateless). */
    public ExportedFile exportAnswer(AnswerExportRequest request, ExportFormat format) {
        byte[] content = switch (format) {
            case MARKDOWN -> markdownExporter.answer(request).getBytes(StandardCharsets.UTF_8);
            case PDF -> pdfExporter.answer(request);
        };
        String filename = "reponse-" + OffsetDateTime.now().format(FILE_STAMP) + "." + format.extension();
        return new ExportedFile(filename, format.contentType(), content);
    }
}
