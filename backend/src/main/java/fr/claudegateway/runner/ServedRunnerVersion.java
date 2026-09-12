package fr.claudegateway.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * La version de runner que <b>cette gateway distribue</b> (F-81 / SF-81-03) — c'est-à-dire le seuil
 * au-delà duquel un runner connecté est « en retard ».
 *
 * <p><b>Pourquoi ce seuil-là et pas une constante.</b> Une constante écrite dans le code serait juste
 * le jour de sa rédaction, puis fausse — et personne ne penserait à la bouger. La gateway, elle, sait
 * déjà ce qu'elle propose au téléchargement : elle empaquette le jar du runner dans son image et le
 * sert par {@code GET /runner/download}. « En retard » veut donc dire « plus ancien que ce que je
 * propose moi-même », ce qui est exactement la question posée et ne demande aucune maintenance : le
 * seuil suit l'image.</p>
 *
 * <p><b>Pas de jar, pas de seuil.</b> Quand le binaire n'est pas empaqueté (profils de développement,
 * tests) ou que son manifeste ne porte pas de version, il n'y a pas de référence — et donc
 * <b>aucune</b> ligne de journal, plutôt qu'une ligne fausse. {@code app.runner.min-version} permet
 * de forcer un seuil explicite si l'exploitation en a besoin.</p>
 *
 * <p>Lu <b>une fois</b>, au démarrage : le fichier ne change pas sous un pod qui tourne.</p>
 */
@Component
public class ServedRunnerVersion {

    private static final Logger log = LoggerFactory.getLogger(ServedRunnerVersion.class);

    /** Version de référence, ou {@code null} quand aucune n'est connaissable. */
    private final String version;

    public ServedRunnerVersion(@Value("${app.runner.jar-path:}") String jarPath,
            @Value("${app.runner.min-version:}") String forcee) {
        String explicite = blankToNull(forcee);
        this.version = explicite != null ? explicite : versionDuJar(blankToNull(jarPath));
        if (version == null) {
            log.debug("Aucune version de runner de référence : aucun retard ne sera signalé.");
        } else {
            log.info("Version de runner distribuée par cette gateway : {}", version);
        }
    }

    /** La version de référence, ou {@code null} si aucune n'est connue. */
    public String version() {
        return version;
    }

    private static String versionDuJar(String jarPath) {
        if (jarPath == null || !Files.isReadable(Path.of(jarPath))) {
            return null;
        }
        try (JarFile jar = new JarFile(jarPath)) {
            Manifest manifeste = jar.getManifest();
            if (manifeste == null) {
                return null;
            }
            return blankToNull(manifeste.getMainAttributes().getValue("Implementation-Version"));
        } catch (IOException | RuntimeException e) {
            // Un jar illisible est un défaut d'empaquetage, pas une panne : le téléchargement le
            // dira à sa façon (404). Ici, on se contente de ne pas avoir de seuil.
            log.debug("Version du runner distribué illisible ({}) : aucun seuil.", jarPath);
            return null;
        }
    }

    private static String blankToNull(String valeur) {
        return valeur == null || valeur.isBlank() ? null : valeur.trim();
    }
}
