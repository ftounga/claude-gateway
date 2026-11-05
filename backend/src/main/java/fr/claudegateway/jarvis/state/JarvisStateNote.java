package fr.claudegateway.jarvis.state;

import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.runner.RunnerStatusService;

/**
 * <b>L'état connu pour Opus en mode Jarvis, poste éteint</b> (F-247 / SF-247-11, cadrage §4.5) — sur le patron de
 * {@code JarvisOralNote} : si le poste visé est hors ligne au départ d'un tour né à la voix, le bloc « État connu »
 * (préparé à l'avance, aucune requête au registre) est joint <b>au message</b>, précédé d'une note fixe. La consigne
 * système du terminal ne change pas (préfixe identique à l'octet près). Poste connecté, présentation, registre coupé ou
 * bloc vide : message inchangé à l'octet près.
 */
@Component
public class JarvisStateNote {

    /** La version du texte figé : à incrémenter à chaque modification. */
    public static final String VERSION = "1";

    /** La note fixe qui précède le bloc. */
    public static final String PREFACE = "\n\n[Jarvis — le poste de ce sujet est hors ligne : aucun outil du poste ne "
            + "répondra. Voici ce qui a été LU sur ce poste, daté par la passerelle. Réponds à une question d'état avec "
            + "ces faits en disant de quand ils datent, jamais comme s'ils étaient actuels ; si rien n'y figure, dis-le. "
            + "Une action attendra le retour du poste.]\n";

    private final JarvisStateBlock block;
    private final RunnerStatusService status;
    private final JarvisStateProperties properties;

    public JarvisStateNote(JarvisStateBlock block, RunnerStatusService status, JarvisStateProperties properties) {
        this.block = block;
        this.status = status;
        this.properties = properties;
    }

    /** Le message, avec la note d'état si le poste du sujet est hors ligne. Jamais d'exception. */
    public String appendTo(UUID userId, Workspace workspace, String message, boolean presentation) {
        if (!properties.block() || presentation || userId == null || workspace == null
                || workspace.getHostId() == null) {
            return message;
        }
        try {
            if (status.hostStatus(userId, workspace.getHostId()).connected()) {
                return message;
            }
            JarvisStateBlock.Selected s = block.ready(userId, workspace.getHostId(), workspace.getId());
            if (s.text().isBlank()) {
                return message;
            }
            return message + PREFACE + s.text();
        } catch (RuntimeException e) {
            return message;
        }
    }
}
