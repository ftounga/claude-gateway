package fr.claudegateway.runner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.JarEntry;
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
 * <p><b>La version réelle (F-111 / SF-111-01).</b> Le jar porte {@code runner-build.properties} :
 * numéro, date de construction, commit, niveau de contrat et Java minimal. L'identifiant rendu est
 * alors {@code 1.0.0-202609131412-f30b4c0} ; un jar plus ancien retombe sur son manifeste.</p>
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

    /** Ressource écrite par la construction du runner (F-111 / SF-111-01). */
    static final String BUILD_RESOURCE = "runner-build.properties";

    /** Java minimal quand le jar ne le dit pas : la cible de compilation du runner depuis F-38. */
    static final int DEFAULT_MIN_JAVA = 21;

    /** Version de référence, ou {@code null} quand aucune n'est connaissable. */
    private final String version;
    /** Niveau de contrat du runner servi, ou {@code null} si le jar ne le dit pas. */
    private final Integer contract;
    /** Java minimal du runner servi. */
    private final int minJava;

    public ServedRunnerVersion(@Value("${app.runner.jar-path:}") String jarPath,
            @Value("${app.runner.min-version:}") String forcee) {
        String explicite = blankToNull(forcee);
        Lu lu = lireLeJar(blankToNull(jarPath));
        this.version = explicite != null ? explicite : lu.version();
        this.contract = lu.contract();
        this.minJava = lu.minJava() == null ? DEFAULT_MIN_JAVA : lu.minJava();
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

    /** Le niveau de contrat du runner servi (F-111), ou {@code null}. */
    public Integer contract() {
        return contract;
    }

    /** La version de Java minimale qu'exige le runner servi (F-111). */
    public int minJava() {
        return minJava;
    }

    private record Lu(String version, Integer contract, Integer minJava) {
        static final Lu RIEN = new Lu(null, null, null);
    }

    private static Lu lireLeJar(String jarPath) {
        if (jarPath == null || !Files.isReadable(Path.of(jarPath))) {
            return Lu.RIEN;
        }
        try (JarFile jar = new JarFile(jarPath)) {
            Lu construction = lireLaConstruction(jar);
            if (construction != null) {
                return construction;
            }
            Manifest manifeste = jar.getManifest();
            if (manifeste == null) {
                return Lu.RIEN;
            }
            return new Lu(blankToNull(manifeste.getMainAttributes().getValue("Implementation-Version")),
                    null, null);
        } catch (IOException | RuntimeException e) {
            // Un jar illisible est un défaut d'empaquetage, pas une panne : le téléchargement le
            // dira à sa façon (404). Ici, on se contente de ne pas avoir de seuil.
            log.debug("Version du runner distribué illisible ({}) : aucun seuil.", jarPath);
            return Lu.RIEN;
        }
    }

    /** L'identifiant F-111 lu dans {@code runner-build.properties}, ou {@code null} s'il manque. */
    private static Lu lireLaConstruction(JarFile jar) throws IOException {
        JarEntry entry = jar.getJarEntry(BUILD_RESOURCE);
        if (entry == null) {
            return null;
        }
        Properties properties = new Properties();
        try (InputStream in = jar.getInputStream(entry)) {
            properties.load(in);
        }
        String numero = blankToNull(properties.getProperty("version"));
        if (numero == null || !numero.matches("\\d+(\\.\\d+){0,2}")) {
            return null;
        }
        String date = blankToNull(properties.getProperty("stamp"));
        String commit = blankToNull(properties.getProperty("commit"));
        StringBuilder id = new StringBuilder(numero);
        if (date != null && date.matches("\\d{12}")) {
            id.append('-').append(date);
            if (commit != null && commit.matches("[A-Za-z0-9]{1,40}")) {
                id.append('-').append(commit);
            }
        }
        return new Lu(id.toString(), entier(properties.getProperty("contract")),
                entier(properties.getProperty("java")));
    }

    private static Integer entier(String valeur) {
        try {
            return valeur == null ? null : Integer.valueOf(valeur.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String valeur) {
        return valeur == null || valeur.isBlank() ? null : valeur.trim();
    }
}
