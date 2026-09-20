package fr.claudegateway.teams.meeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.docx.DocxFixtures;
import fr.claudegateway.docx.DocxProperties;
import fr.claudegateway.docx.DocxTextExtractor;
import fr.claudegateway.docx.InvalidDocxException;
import fr.claudegateway.radar.RadarScope;

/**
 * La transcription externe (client) d'une réunion (F-128 / SF-128-20a) : collage, dépôt de fichier
 * (.txt/.vtt/.docx), remplacement, retrait, isolation. L'extracteur .docx est le vrai (F-86), sans
 * bibliothèque : on peut fabriquer un vrai .docx en test.
 */
@ExtendWith(MockitoExtension.class)
class ExternalTranscriptServiceTest {

    @Mock private MeetingRepository repository;

    private ExternalTranscriptService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID meetingId = UUID.randomUUID();
    private final RadarScope scope = new RadarScope(userId, hostId);

    @BeforeEach
    void setUp() {
        DocxTextExtractor extractor = new DocxTextExtractor(new DocxProperties(null, null, null));
        service = new ExternalTranscriptService(repository, extractor);
    }

    private Meeting meeting() {
        return Meeting.builder().id(meetingId).userId(userId).hostId(hostId).state(MeetingState.STOPPED)
                .meetingUrl("https://x").consentAcknowledged(true).retentionDays(30).title("Comité")
                .startedAt(java.time.OffsetDateTime.now()).build();
    }

    private void stubFound() {
        when(repository.findByIdAndUserIdAndHostId(meetingId, userId, hostId))
                .thenReturn(Optional.of(meeting()));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("attachText : texte stocké tel quel, format TEXT, source par défaut, addedAt renseigné")
    void attachText() {
        stubFound();

        service.attachText(scope, meetingId, "  Alice : bonjour\nBob : salut  ", null);

        ArgumentCaptor<Meeting> saved = ArgumentCaptor.forClass(Meeting.class);
        verify(repository).save(saved.capture());
        Meeting m = saved.getValue();
        assertThat(m.getExternalTranscript()).isEqualTo("Alice : bonjour\nBob : salut");
        assertThat(m.getExternalTranscriptFormat()).isEqualTo(ExternalTranscriptFormat.TEXT);
        assertThat(m.getExternalTranscriptSource()).isEqualTo(Meeting.DEFAULT_EXTERNAL_TRANSCRIPT_SOURCE);
        assertThat(m.getExternalTranscriptAddedAt()).isNotNull();
    }

    @Test
    @DisplayName("attachText : libellé de source retenu quand fourni")
    void attachTextWithSource() {
        stubFound();
        service.attachText(scope, meetingId, "texte", "Transcription Teams (client)");
        ArgumentCaptor<Meeting> saved = ArgumentCaptor.forClass(Meeting.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getExternalTranscriptSource()).isEqualTo("Transcription Teams (client)");
    }

    @Test
    @DisplayName("attachText : texte vide -> validation, rien écrit")
    void attachTextEmpty() {
        when(repository.findByIdAndUserIdAndHostId(meetingId, userId, hostId))
                .thenReturn(Optional.of(meeting()));

        assertThatThrownBy(() -> service.attachText(scope, meetingId, "   ", null))
                .isInstanceOf(MeetingValidationException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("attachFile .txt : texte tel quel, format TEXT")
    void attachFileTxt() {
        stubFound();
        service.attachFile(scope, meetingId, "notes.txt", "Alice : bonjour".getBytes(StandardCharsets.UTF_8));
        ArgumentCaptor<Meeting> saved = ArgumentCaptor.forClass(Meeting.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getExternalTranscript()).isEqualTo("Alice : bonjour");
        assertThat(saved.getValue().getExternalTranscriptFormat()).isEqualTo(ExternalTranscriptFormat.TEXT);
    }

    @Test
    @DisplayName("attachFile .vtt : texte tel quel, format VTT")
    void attachFileVtt() {
        stubFound();
        String vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\n<v Alice>Bonjour";
        service.attachFile(scope, meetingId, "teams.vtt", vtt.getBytes(StandardCharsets.UTF_8));
        ArgumentCaptor<Meeting> saved = ArgumentCaptor.forClass(Meeting.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getExternalTranscript()).contains("WEBVTT").contains("Alice");
        assertThat(saved.getValue().getExternalTranscriptFormat()).isEqualTo(ExternalTranscriptFormat.VTT);
    }

    @Test
    @DisplayName("attachFile .docx : texte extrait (F-86), format DOCX")
    void attachFileDocx() {
        stubFound();
        byte[] docx = DocxFixtures.docx(DocxFixtures.paragraph("Alice : la migration est validée."));
        service.attachFile(scope, meetingId, "transcription.docx", docx);
        ArgumentCaptor<Meeting> saved = ArgumentCaptor.forClass(Meeting.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getExternalTranscript()).contains("Alice : la migration est validée.");
        assertThat(saved.getValue().getExternalTranscriptFormat()).isEqualTo(ExternalTranscriptFormat.DOCX);
    }

    @Test
    @DisplayName("attachFile .docx corrompu (zip renommé) -> InvalidDocxException (422), rien écrit")
    void attachFileDocxCorrupt() {
        when(repository.findByIdAndUserIdAndHostId(meetingId, userId, hostId))
                .thenReturn(Optional.of(meeting()));
        // Une archive valide SANS word/document.xml, renommée .docx : looksLikeDocx = false -> lue en texte.
        // Pour éprouver le 422, on force un docx détecté (contient word/document.xml) mais au XML cassé.
        byte[] badDocx = fr.claudegateway.docx.DocxFixtures.archive(
                java.util.Map.of("word/document.xml", "<w:document><w:body><w:p></broken"));

        assertThatThrownBy(() -> service.attachFile(scope, meetingId, "x.docx", badDocx))
                .isInstanceOf(InvalidDocxException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("attachFile vide -> validation, rien écrit")
    void attachFileEmpty() {
        when(repository.findByIdAndUserIdAndHostId(meetingId, userId, hostId))
                .thenReturn(Optional.of(meeting()));
        assertThatThrownBy(() -> service.attachFile(scope, meetingId, "x.txt", new byte[0]))
                .isInstanceOf(MeetingValidationException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("attach remplace la transcription externe existante (une seule, remplaçable)")
    void attachReplaces() {
        Meeting existing = meeting();
        existing.setExternalTranscript("ancienne");
        existing.setExternalTranscriptFormat(ExternalTranscriptFormat.TEXT);
        when(repository.findByIdAndUserIdAndHostId(meetingId, userId, hostId))
                .thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.attachText(scope, meetingId, "nouvelle", null);

        ArgumentCaptor<Meeting> saved = ArgumentCaptor.forClass(Meeting.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getExternalTranscript()).isEqualTo("nouvelle");
    }

    @Test
    @DisplayName("clear : les 4 colonnes vidées")
    void clear() {
        Meeting existing = meeting();
        existing.setExternalTranscript("texte");
        existing.setExternalTranscriptSource("src");
        existing.setExternalTranscriptFormat(ExternalTranscriptFormat.TEXT);
        existing.setExternalTranscriptAddedAt(java.time.OffsetDateTime.now());
        when(repository.findByIdAndUserIdAndHostId(meetingId, userId, hostId))
                .thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.clear(scope, meetingId);

        assertThat(existing.getExternalTranscript()).isNull();
        assertThat(existing.getExternalTranscriptSource()).isNull();
        assertThat(existing.getExternalTranscriptFormat()).isNull();
        assertThat(existing.getExternalTranscriptAddedAt()).isNull();
    }

    @Test
    @DisplayName("externalTranscript : rend le texte ; vide si absent")
    void read() {
        Meeting existing = meeting();
        existing.setExternalTranscript("le texte");
        when(repository.findByIdAndUserIdAndHostId(meetingId, userId, hostId))
                .thenReturn(Optional.of(existing));
        assertThat(service.externalTranscript(scope, meetingId)).contains("le texte");

        Meeting empty = meeting();
        when(repository.findByIdAndUserIdAndHostId(meetingId, userId, hostId))
                .thenReturn(Optional.of(empty));
        assertThat(service.externalTranscript(scope, meetingId)).isEmpty();
    }

    @Test
    @DisplayName("ISOLATION : réunion d'un autre couple -> 404, rien écrit")
    void isolation() {
        when(repository.findByIdAndUserIdAndHostId(meetingId, userId, hostId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.attachText(scope, meetingId, "x", null))
                .isInstanceOf(MeetingNotFoundException.class);
        assertThatThrownBy(() -> service.attachFile(scope, meetingId, "x.txt", "y".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(MeetingNotFoundException.class);
        assertThatThrownBy(() -> service.clear(scope, meetingId))
                .isInstanceOf(MeetingNotFoundException.class);
        verify(repository, never()).save(any());
    }
}
