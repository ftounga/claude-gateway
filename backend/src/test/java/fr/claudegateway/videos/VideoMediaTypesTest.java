package fr.claudegateway.videos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Les types admis, leur signature, et les chapitres (F-213 / SF-213-01). */
class VideoMediaTypesTest {

    static final byte[] MP4 = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
    static final byte[] WEBM = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 1, 2};
    static final byte[] MP3 = {'I', 'D', '3', 4, 0, 0};
    static final byte[] WAV = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'};

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Extensions : vidéo pour video_publier, son pour audio_publier, rien d'autre")
    void extensions() {
        assertThat(VideoMediaTypes.of("out/cours.MP4", VideoKind.VIDEO)).get()
                .extracting(VideoMediaTypes.MediaType::mime).isEqualTo("video/mp4");
        assertThat(VideoMediaTypes.of("a.webm", VideoKind.VIDEO)).isPresent();
        assertThat(VideoMediaTypes.of("a.mp3", VideoKind.VIDEO)).isEmpty();
        assertThat(VideoMediaTypes.of("voix.m4a", VideoKind.AUDIO)).get()
                .extracting(VideoMediaTypes.MediaType::fileName).isEqualTo("audio.m4a");
        assertThat(VideoMediaTypes.of("a.mp4", VideoKind.AUDIO)).isEmpty();
        assertThat(VideoMediaTypes.of("a.mov", VideoKind.VIDEO)).isEmpty();
        assertThat(VideoMediaTypes.of(null, VideoKind.VIDEO)).isEmpty();
    }

    @Test
    @DisplayName("Signatures : un fichier renommé n'est pas un média")
    void signatures() {
        VideoMediaTypes.MediaType mp4 = VideoMediaTypes.of("a.mp4", VideoKind.VIDEO).orElseThrow();
        assertThat(VideoMediaTypes.matches(mp4, MP4)).isTrue();
        assertThat(VideoMediaTypes.matches(mp4, "<html>hello".getBytes())).isFalse();
        assertThat(VideoMediaTypes.matches(VideoMediaTypes.of("a.webm", VideoKind.VIDEO).orElseThrow(), WEBM)).isTrue();
        assertThat(VideoMediaTypes.matches(VideoMediaTypes.of("a.mp3", VideoKind.AUDIO).orElseThrow(), MP3)).isTrue();
        assertThat(VideoMediaTypes.matches(VideoMediaTypes.of("a.mp3", VideoKind.AUDIO).orElseThrow(),
                new byte[] {(byte) 0xFF, (byte) 0xFB, 0x10})).isTrue();
        assertThat(VideoMediaTypes.matches(VideoMediaTypes.of("a.wav", VideoKind.AUDIO).orElseThrow(), WAV)).isTrue();
        assertThat(VideoMediaTypes.matches(VideoMediaTypes.of("a.wav", VideoKind.AUDIO).orElseThrow(), MP3)).isFalse();
        assertThat(VideoMediaTypes.matchesPoster("image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G'})).isTrue();
        assertThat(VideoMediaTypes.matchesPoster("image/jpeg", new byte[] {'G', 'I', 'F'})).isFalse();
    }

    @Test
    @DisplayName("Chapitres : lus, croissants, bornés — et relus du JSON")
    void chapters() throws Exception {
        List<VideoChapter> chapters = VideoChapter.parse(mapper.readTree(
                "[{\"t\":0,\"titre\":\"Intro\"},{\"t\":12.7,\"titre\":\"  Le  cluster \"}]"));
        assertThat(chapters).containsExactly(new VideoChapter(0, "Intro"), new VideoChapter(12, "Le cluster"));
        assertThat(VideoChapter.fromJson(VideoChapter.toJson(chapters))).isEqualTo(chapters);
        assertThat(VideoChapter.parse(null)).isEmpty();
        assertThat(VideoChapter.toJson(List.of())).isNull();

        assertThatThrownBy(() -> VideoChapter.parse(mapper.readTree(
                "[{\"t\":10,\"titre\":\"B\"},{\"t\":5,\"titre\":\"A\"}]")))
                .isInstanceOf(VideoRejectedException.class).hasMessageContaining("croissant");
        assertThatThrownBy(() -> VideoChapter.parse(mapper.readTree("[{\"t\":-1,\"titre\":\"A\"}]")))
                .isInstanceOf(VideoRejectedException.class);
        assertThatThrownBy(() -> VideoChapter.parse(mapper.readTree("[{\"t\":1}]")))
                .isInstanceOf(VideoRejectedException.class);
        assertThatThrownBy(() -> VideoChapter.parse(mapper.readTree("{\"t\":1}")))
                .isInstanceOf(VideoRejectedException.class);
    }
}
