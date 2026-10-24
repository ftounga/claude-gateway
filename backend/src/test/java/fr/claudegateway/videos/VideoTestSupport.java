package fr.claudegateway.videos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** Outils partagés des tests vidéo (F-213). */
final class VideoTestSupport {

    private VideoTestSupport() {
    }

    static User user(UserRepository users, String email, UserRole role) {
        return users.findByEmail(email).orElseGet(() -> users.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(role).build()));
    }

    static Workspace runnerWorkspace(WorkspaceRepository workspaces, UUID userId) {
        return workspaces.save(Workspace.builder().userId(userId).name("cours-" + UUID.randomUUID())
                .hostId(UUID.randomUUID()).projectPath("")
                .executionTarget(WorkspaceExecutionTarget.RUNNER).build());
    }

    /** Un « mp4 » de {@code size} octets : signature ftyp puis un motif. */
    static byte[] mp4(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i * 31 + 7);
        }
        System.arraycopy(VideoMediaTypesTest.MP4, 0, bytes, 0, Math.min(size, VideoMediaTypesTest.MP4.length));
        return bytes;
    }

    /** Un « mp3 » de {@code size} octets. */
    static byte[] mp3(int size) {
        byte[] bytes = new byte[size];
        System.arraycopy(VideoMediaTypesTest.MP3, 0, bytes, 0, Math.min(size, VideoMediaTypesTest.MP3.length));
        return bytes;
    }

    /** La tranche que le runner rendrait pour ce fichier à cet offset. */
    static RunnerCallResult chunk(byte[] file, long offset, int length) {
        int from = (int) Math.min(offset, file.length);
        int to = (int) Math.min(file.length, from + (long) length);
        byte[] part = Arrays.copyOfRange(file, from, to);
        return new RunnerCallResult(true, Base64.getEncoder().encodeToString(part), to < file.length, null, 2L,
                (long) file.length, null, null, "", false);
    }

    /** Publie directement (sans runner) : préparer, envoyer, inscrire. */
    static VideoService.PublishedVideo publish(VideoService service, VideoStore store, VideoPlace place,
            String title, byte[] content, List<VideoChapter> chapters, String series, UUID replace) {
        VideoMediaTypes.MediaType type = VideoMediaTypes.of("x.mp4", VideoKind.VIDEO).orElseThrow();
        VideoService.Prepared prepared = service.prepare(new VideoService.PublishRequest(place, type, replace, title,
                null, chapters, series, null, 42, null), content.length, 0);
        var upload = store.openUpload(prepared.mediaKey(), type.mime());
        upload.write(content);
        upload.complete();
        return service.commit(prepared, content.length, null, null);
    }

    static byte[] readAll(VideoStore store, String key) throws IOException {
        long size = store.size(key).orElseThrow();
        try (InputStream in = store.openRange(key, 0, size - 1).orElseThrow()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toByteArray();
        }
    }
}
