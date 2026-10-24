package fr.claudegateway.videos;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;

/**
 * <b>La lecture d'une vidéo par ticket</b> (F-213 / SF-213-01) : la seule route sans JWT, parce qu'un
 * {@code <video src>} ne porte pas d'en-tête. Le ticket ({@link VideoViewTicketService}) désigne une vidéo,
 * une version et un compte ; la vidéo est encore résolue par {@code (id, user_id)} — un ticket valide d'un
 * compte n'ouvre rien d'un autre. Tout échec est un 404 indiscernable.
 */
@RestController
@RequestMapping(VideoMediaController.PATH)
public class VideoMediaController {

    /** Le chemin public (ajouté à {@code SecurityConfig}, GET seulement). */
    public static final String PATH = "/video-media";

    private final VideoViewTicketService tickets;
    private final VideoMediaServer mediaServer;

    public VideoMediaController(VideoViewTicketService tickets, VideoMediaServer mediaServer) {
        this.tickets = tickets;
        this.mediaServer = mediaServer;
    }

    /** Le média, avec {@code Range} ; en pièce jointe si {@code download=1}. */
    @GetMapping("/{ticket}")
    public void media(@PathVariable String ticket,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
            @RequestParam(value = "download", required = false) boolean download,
            HttpServletResponse response) throws IOException {
        VideoViewTicketService.Ticket read = tickets.read(ticket).orElseThrow(VideoNotFoundException::new);
        mediaServer.serveMedia(read.userId(), read.videoId(), read.version(), range, download, response);
    }

    /** La couverture de la version du ticket. */
    @GetMapping("/{ticket}/poster")
    public void poster(@PathVariable String ticket, HttpServletResponse response) throws IOException {
        VideoViewTicketService.Ticket read = tickets.read(ticket).orElseThrow(VideoNotFoundException::new);
        mediaServer.servePoster(read.userId(), read.videoId(), read.version(), response);
    }
}
