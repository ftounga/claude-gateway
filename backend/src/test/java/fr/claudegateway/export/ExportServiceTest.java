package fr.claudegateway.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.chat.Conversation;
import fr.claudegateway.chat.ConversationService;
import fr.claudegateway.chat.Message;
import fr.claudegateway.chat.MessageRole;
import fr.claudegateway.export.dto.AnswerCitation;
import fr.claudegateway.export.dto.AnswerExportRequest;

/**
 * Tests unitaires du moteur d'export (F-14) : rendu Markdown/PDF d'une conversation et d'une réponse
 * citée, choix du format, et rejet d'un format inconnu.
 */
@ExtendWith(MockitoExtension.class)
class ExportServiceTest {

    @Mock
    private ConversationService conversationService;

    @Spy
    private MarkdownExporter markdownExporter;

    @Spy
    private PdfExporter pdfExporter;

    @InjectMocks
    private ExportService exportService;

    private static final UUID USER = UUID.randomUUID();
    private static final UUID CONV = UUID.randomUUID();

    private Conversation conversation() {
        return Conversation.builder().userId(USER).title("Analyse contrat").model("claude-opus-4-8").build();
    }

    private List<Message> messages() {
        return List.of(
                Message.builder().conversationId(CONV).userId(USER)
                        .role(MessageRole.USER).content("Quelle est la durée ?").build(),
                Message.builder().conversationId(CONV).userId(USER)
                        .role(MessageRole.ASSISTANT).content("La durée est de 5 ans.").model("claude-opus-4-8").build());
    }

    @Test
    void conversationMarkdownContainsTitleRolesAndContents() {
        when(conversationService.getOwned(CONV, USER)).thenReturn(conversation());
        when(conversationService.messagesOf(CONV, USER)).thenReturn(messages());

        ExportedFile file = exportService.exportConversation(USER, CONV, ExportFormat.MARKDOWN);

        assertThat(file.contentType()).isEqualTo("text/markdown;charset=UTF-8");
        assertThat(file.filename()).isEqualTo("conversation-" + CONV + ".md");
        String md = new String(file.content(), StandardCharsets.UTF_8);
        assertThat(md).contains("Analyse contrat")
                .contains("Vous").contains("Assistant")
                .contains("Quelle est la durée ?").contains("La durée est de 5 ans.");
    }

    @Test
    void conversationPdfIsAValidPdfDocument() {
        when(conversationService.getOwned(CONV, USER)).thenReturn(conversation());
        when(conversationService.messagesOf(CONV, USER)).thenReturn(messages());

        ExportedFile file = exportService.exportConversation(USER, CONV, ExportFormat.PDF);

        assertThat(file.contentType()).isEqualTo("application/pdf");
        assertThat(file.filename()).isEqualTo("conversation-" + CONV + ".pdf");
        assertThat(file.content()).isNotEmpty();
        assertThat(new String(file.content(), 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void answerMarkdownContainsQuestionAnswerAndCitation() {
        AnswerExportRequest request = new AnswerExportRequest(
                "Quelle durée ?", "5 ans.", "claude-opus-4-8", true,
                List.of(new AnswerCitation(UUID.randomUUID(), "contrat.pdf", 3, 0, "Clause de durée.")));

        ExportedFile file = exportService.exportAnswer(request, ExportFormat.MARKDOWN);

        assertThat(file.filename()).startsWith("reponse-").endsWith(".md");
        String md = new String(file.content(), StandardCharsets.UTF_8);
        assertThat(md).contains("Quelle durée ?").contains("5 ans.")
                .contains("[contrat.pdf:3:0]").contains("Clause de durée.");
    }

    @Test
    void answerPdfIsAValidPdfDocument() {
        AnswerExportRequest request = new AnswerExportRequest(
                "Question ?", "Réponse.", null, false, List.of());

        ExportedFile file = exportService.exportAnswer(request, ExportFormat.PDF);

        assertThat(file.filename()).endsWith(".pdf");
        assertThat(new String(file.content(), 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void unknownFormatIsRejected() {
        assertThatThrownBy(() -> ExportFormat.fromParam("docx"))
                .isInstanceOf(UnsupportedExportFormatException.class);
    }

    @Test
    void blankFormatDefaultsToMarkdown() {
        assertThat(ExportFormat.fromParam(null)).isEqualTo(ExportFormat.MARKDOWN);
        assertThat(ExportFormat.fromParam("  ")).isEqualTo(ExportFormat.MARKDOWN);
        assertThat(ExportFormat.fromParam("PDF")).isEqualTo(ExportFormat.PDF);
    }
}
