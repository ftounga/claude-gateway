package fr.claudegateway.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Le seuil : la version de runner que cette gateway distribue (F-81 / SF-81-03).
 *
 * <p>Une constante écrite dans le code aurait été juste le jour de sa rédaction, puis fausse. La
 * gateway sait déjà ce qu'elle propose au téléchargement ; c'est la seule référence qui se met à
 * jour toute seule, au rythme de l'image.</p>
 */
class ServedRunnerVersionTest {

    @TempDir
    Path dossier;

    @Test
    @DisplayName("lit la version au manifeste du jar qu'elle sert")
    void litLaVersionDuJarServi() throws Exception {
        Path jar = jarAvecVersion("1.4.2");

        assertThat(new ServedRunnerVersion(jar.toString(), "").version()).isEqualTo("1.4.2");
    }

    @Test
    @DisplayName("un seuil forcé par configuration l'emporte sur le jar")
    void leSeuilForceLemporte() throws Exception {
        Path jar = jarAvecVersion("1.4.2");

        assertThat(new ServedRunnerVersion(jar.toString(), "2.0.0").version()).isEqualTo("2.0.0");
    }

    @Test
    @DisplayName("pas de jar, pas de seuil — et donc aucune ligne « en retard »")
    void pasDeJarPasDeSeuil() {
        assertThat(new ServedRunnerVersion("", "").version())
                .as("aucun seuil vaut mieux qu'un seuil faux : sans référence, aucun runner ne sera "
                        + "jamais déclaré en retard")
                .isNull();
        assertThat(new ServedRunnerVersion(dossier.resolve("absent.jar").toString(), "").version())
                .isNull();
    }

    @Test
    @DisplayName("un jar sans version au manifeste ne produit pas de seuil")
    void unJarSansVersionNeProduitPasDeSeuil() throws Exception {
        Path jar = dossier.resolve("sans-version.jar");
        Manifest manifeste = new Manifest();
        manifeste.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (OutputStream flux = Files.newOutputStream(jar);
                JarOutputStream sortie = new JarOutputStream(flux, manifeste)) {
            // Un manifeste, et rien d'autre : c'est l'état du fat-jar du runner avant SF-81-03.
        }

        assertThat(new ServedRunnerVersion(jar.toString(), "").version()).isNull();
    }

    @Test
    @DisplayName("un fichier qui n'est pas un jar est un défaut d'empaquetage, pas une panne")
    void unFichierIllisibleNestPasUnePanne() throws Exception {
        Path faux = dossier.resolve("pas-un-jar.jar");
        Files.writeString(faux, "ceci n'est pas une archive");

        assertThat(new ServedRunnerVersion(faux.toString(), "").version()).isNull();
    }

    private Path jarAvecVersion(String version) throws Exception {
        Path jar = dossier.resolve("claude-runner.jar");
        Manifest manifeste = new Manifest();
        manifeste.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifeste.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
        try (OutputStream flux = Files.newOutputStream(jar);
                JarOutputStream sortie = new JarOutputStream(flux, manifeste)) {
            // Le manifeste suffit : c'est tout ce que la gateway lit du binaire qu'elle sert.
        }
        return jar;
    }
}
