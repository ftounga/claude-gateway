package fr.claudegateway.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.atelier.ProjectFileDeposit;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.teams.meeting.stt.TranscriptionProvider;
import fr.claudegateway.teams.meeting.stt.TranscriptionProvider.TimedWord;
import fr.claudegateway.teams.meeting.stt.TranscriptionProvider.WordTranscript;
import fr.claudegateway.teams.meeting.stt.TranscriptionProviderException;
import fr.claudegateway.videos.AudioBlock;
import fr.claudegateway.videos.VideoToolExecutor;

/** L'outil {@code voix_off} (F-215 / SF-215-01) : fournisseur, poste et transcription simulés. */
class SpeechToolExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SpeechProvider provider = mock(SpeechProvider.class);
    private final TranscriptionProvider transcription = mock(TranscriptionProvider.class);
    private final ProjectFileDeposit deposit = mock(ProjectFileDeposit.class);
    private final SpeechPreferenceRepository repository = mock(SpeechPreferenceRepository.class);
    private final SpeechUsage usage = mock(SpeechUsage.class);
    private final VideoToolExecutor videos = mock(VideoToolExecutor.class);
    private final SpeechProperties properties = OpenAiSpeechProviderTest.props("https://api.openai.com/v1", "sk", true);
    private final UUID userId = UUID.randomUUID();
    private Workspace workspace;
    private SpeechToolExecutor executor;

    @BeforeEach
    void setUp() {
        workspace = Workspace.builder().id(UUID.randomUUID()).userId(userId).name("cours").hostId(UUID.randomUUID())
                .projectPath("").executionTarget(WorkspaceExecutionTarget.RUNNER).build();
        executor = new SpeechToolExecutor(provider, transcription, deposit,
                new SpeechPreferenceService(repository, properties), usage, properties, videos, mapper);
        when(provider.synthesize(anyString(), anyString(), anyString()))
                .thenReturn(new SpeechProvider.SpeechAudio(new byte[] {1, 2, 3}, "audio/mpeg"));
        when(deposit.deposit(any(), any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenAnswer(call -> call.getArgument(3));
        when(repository.findById(userId)).thenReturn(Optional.empty());
    }

    private ObjectNode input(String text) {
        return mapper.createObjectNode().put("texte", text);
    }

    @Test
    @DisplayName("Nominal : voix D2 (onyx + consignes de formateur), mp3 + .mots.json déposés, mots rendus, usage inscrit")
    void narratesWithDefaultsAndWords() throws Exception {
        when(transcription.transcribeWords(any(), eq("audio/mpeg"), any())).thenReturn(new WordTranscript(
                List.of(new TimedWord("Le", 0.0, 0.3), new TimedWord("VPC", 0.35, 0.9)), 1.2, "fr"));

        SpeechToolExecutor.Outcome outcome = executor.execute(userId, workspace, "c1",
                input("Le VPC").put("fichier", "cours/audio/chapitre 1.wav"));

        assertThat(outcome.error()).as(outcome.content()).isFalse();
        verify(provider).synthesize("Le VPC", "onyx", SpeechVoices.DEFAULT_INSTRUCTIONS);
        verify(deposit).deposit(eq(userId), eq(workspace), eq("c1"), eq("cours/audio/chapitre-1.mp3"), any(),
                eq("audio/mpeg"), eq("voix_off"));
        ArgumentCaptor<byte[]> json = ArgumentCaptor.forClass(byte[].class);
        verify(deposit).deposit(eq(userId), eq(workspace), eq("c1.mots"), eq("cours/audio/chapitre-1.mots.json"),
                json.capture(), eq("application/json"), eq("voix_off"));
        JsonNode words = mapper.readTree(new String(json.getValue(), StandardCharsets.UTF_8));
        assertThat(words.path("voix").asText()).isEqualTo("onyx");
        assertThat(words.path("mots")).hasSize(2);
        assertThat(words.path("mots").get(1).path("debut").asDouble()).isEqualTo(0.35);
        assertThat(outcome.content()).contains("0.35–0.90 VPC").contains("1.20 s");
        verify(usage).record(userId, workspace.getId(), workspace.getHostId(), 6, 1.2);
    }

    @Test
    @DisplayName("Préférence du compte : utilisée quand l'appel ne précise pas ; l'appel l'emporte sinon")
    void preferenceThenCallOverride() {
        when(repository.findById(userId)).thenReturn(Optional.of(
                SpeechPreference.builder().userId(userId).voice("nova").instructions("plus dynamique").build()));
        when(transcription.transcribeWords(any(), anyString(), any())).thenThrow(new UnsupportedOperationException());

        executor.execute(userId, workspace, "c1", input("Bonjour"));
        verify(provider).synthesize("Bonjour", "nova", "plus dynamique");

        executor.execute(userId, workspace, "c2", input("Salut").put("voix", "ASH").put("consignes", "grave"));
        verify(provider).synthesize("Salut", "ash", "grave");
    }

    @Test
    @DisplayName("Transcription en échec : mp3 déposé, résultat NON bloquant, pas de .mots.json")
    void transcriptionFailureIsNotBlocking() {
        when(transcription.transcribeWords(any(), anyString(), any()))
                .thenThrow(new TranscriptionProviderException("down"));

        SpeechToolExecutor.Outcome outcome = executor.execute(userId, workspace, "c1", input("Bonjour"));

        assertThat(outcome.error()).isFalse();
        assertThat(outcome.content()).contains("Horodatage des mots en échec");
        verify(deposit, times(1)).deposit(any(), any(), anyString(), anyString(), any(), anyString(), anyString());
        verify(usage).record(userId, workspace.getId(), workspace.getHostId(), 7, 0d);
    }

    @Test
    @DisplayName("Erreurs : texte absent, trop long, voix inconnue, consignes trop longues — rien appelé")
    void validation() {
        assertThat(executor.execute(userId, workspace, "c", mapper.createObjectNode()).error()).isTrue();
        assertThat(executor.execute(userId, workspace, "c", input("x".repeat(4001))).content()).contains("trop long");
        assertThat(executor.execute(userId, workspace, "c", input("x").put("voix", "robot")).content())
                .contains("inconnue");
        assertThat(executor.execute(userId, workspace, "c", input("x").put("consignes", "y".repeat(1001))).error())
                .isTrue();
        verify(provider, never()).synthesize(anyString(), anyString(), anyString());
        verify(usage, never()).record(any(), any(), any(), anyLong(), anyDouble());
    }

    @Test
    @DisplayName("Fournisseur en échec : erreur d'outil, rien déposé, rien compté")
    void providerFailure() {
        when(provider.synthesize(anyString(), anyString(), anyString())).thenThrow(new SpeechProviderException("x"));

        SpeechToolExecutor.Outcome outcome = executor.execute(userId, workspace, "c1", input("Bonjour"));

        assertThat(outcome.error()).isTrue();
        verify(deposit, never()).deposit(any(), any(), anyString(), anyString(), any(), anyString(), anyString());
        verify(usage, never()).record(any(), any(), any(), anyLong(), anyDouble());
    }

    @Test
    @DisplayName("Projet hébergé ou voix éteinte : refus")
    void hostedOrDisabled() {
        Workspace hosted = Workspace.builder().id(UUID.randomUUID()).userId(userId).name("h").projectPath("")
                .executionTarget(WorkspaceExecutionTarget.SANDBOX).build();
        assertThat(executor.execute(userId, hosted, "c", input("x")).error()).isTrue();

        SpeechProperties off = OpenAiSpeechProviderTest.props("https://api.openai.com/v1", "sk", false);
        SpeechToolExecutor disabled = new SpeechToolExecutor(provider, transcription, deposit,
                new SpeechPreferenceService(repository, off), usage, off, videos, mapper);
        assertThat(disabled.execute(userId, workspace, "c", input("x")).content()).contains("non configurée");
    }

    @Test
    @DisplayName("Auditions : 4 voix par défaut déposées puis publiées par audio_publier ; bloc audio rendu")
    void auditionsArePublished() {
        AudioBlock block = new AudioBlock("Auditions de voix", List.of());
        when(videos.publishAudio(eq(userId), eq(workspace), eq("c1.auditions"), any()))
                .thenReturn(new VideoToolExecutor.AudioOutcome("ok", false, block));

        SpeechToolExecutor.Outcome outcome = executor.execute(userId, workspace, "c1",
                input("Bienvenue dans ce cours.").put("auditions", true));

        assertThat(outcome.error()).as(outcome.content()).isFalse();
        assertThat(outcome.auditions()).isSameAs(block);
        for (String voice : SpeechVoices.DEFAULT_AUDITIONS) {
            verify(provider).synthesize("Bienvenue dans ce cours.", voice, SpeechVoices.DEFAULT_INSTRUCTIONS);
        }
        ArgumentCaptor<JsonNode> published = ArgumentCaptor.forClass(JsonNode.class);
        verify(videos).publishAudio(eq(userId), eq(workspace), eq("c1.auditions"), published.capture());
        assertThat(published.getValue().path("pistes")).hasSize(4);
        assertThat(published.getValue().path("pistes").get(0).path("path").asText()).isEqualTo("voix/auditions/onyx.mp3");
        assertThat(published.getValue().path("pistes").get(0).path("libelle").asText()).startsWith("Voix 1 — Onyx");
        verify(usage).record(userId, workspace.getId(), workspace.getHostId(), 4L * 24, 0d);
        verify(transcription, never()).transcribeWords(any(), anyString(), any());
    }

    @Test
    @DisplayName("Auditions : phrase trop longue ou voix_auditions invalides refusées")
    void auditionsValidation() {
        assertThat(executor.execute(userId, workspace, "c", input("x".repeat(301)).put("auditions", true)).error())
                .isTrue();
        ObjectNode one = input("x").put("auditions", true);
        one.putArray("voix_auditions").add("onyx");
        assertThat(executor.execute(userId, workspace, "c", one).error()).isTrue();
        ObjectNode unknown = input("x").put("auditions", true);
        unknown.putArray("voix_auditions").add("onyx").add("robot");
        assertThat(executor.execute(userId, workspace, "c", unknown).error()).isTrue();
        verify(provider, never()).synthesize(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Nom de fichier nettoyé : pas de remontée, pas de racine, .mp3 imposé, repli sûr")
    void filePathIsSanitized() {
        assertThat(SpeechToolExecutor.filePath("../../etc/passwd")).isEqualTo("etc/passwd.mp3");
        assertThat(SpeechToolExecutor.filePath("/abs/voix.mp3")).isEqualTo("abs/voix.mp3");
        assertThat(SpeechToolExecutor.filePath("C:\\x\\narration.wav")).isEqualTo("C/x/narration.mp3");
        assertThat(SpeechToolExecutor.filePath("")).matches("voix/voix-off-[0-9a-f]{8}\\.mp3");
        assertThat(SpeechToolExecutor.filePath("..")).matches("voix/voix-off-[0-9a-f]{8}\\.mp3");
        assertThat(SpeechToolExecutor.wordsPath("a/b.mp3")).isEqualTo("a/b.mots.json");
    }
}
