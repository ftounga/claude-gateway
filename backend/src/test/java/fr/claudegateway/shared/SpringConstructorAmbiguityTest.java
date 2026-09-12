package fr.claudegateway.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Aucun composant ne doit laisser Spring hésiter entre deux constructeurs (F-79 / SF-79-02).
 *
 * <p><b>Ce que ce test empêche de revenir.</b> Le 2026-09-12, SF-79-01 a ajouté à
 * {@code S3WorkspaceStorage} un second constructeur — celui qui injecte un client bouchonné, sans
 * lequel la suppression par lots n'était pas testable. La classe en a donc eu <b>deux</b>. Spring ne
 * choisit seul que lorsqu'il n'y en a qu'un : avec deux, il cherche un constructeur <b>sans
 * argument</b>, n'en trouve pas, et <b>tout le contexte échoue</b> —
 * {@code NoSuchMethodException: S3WorkspaceStorage.<init>()}. Le backend ne démarrait plus, et seule
 * la bascule progressive de Kubernetes, qui a gardé l'ancien pod, a évité la panne.</p>
 *
 * <p><b>La lecture se fait sur les classes compilées, pas par le scanner de Spring.</b> C'est la
 * leçon du premier essai de ce test, qui passait alors que le défaut était là : le scanner évalue
 * les {@code @ConditionalOn…}, et {@code S3WorkspaceStorage} porte
 * {@code @ConditionalOnProperty(app.atelier.storage=s3)} — absent en test, donc jamais scanné. Un
 * garde-fou qui ne regarde que ce qui tourne en test ne garde rien de ce qui ne tourne qu'en
 * production, et c'est précisément là que ce défaut vivait.</p>
 *
 * <p>La règle : <b>un seul constructeur, ou bien un {@link Autowired} qui désigne celui de
 * production.</b></p>
 */
class SpringConstructorAmbiguityTest {

    /** Les stéréotypes qui font d'une classe un bean construit par Spring. */
    private static final Set<String> STEREOTYPES = Set.of(
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController",
            "org.springframework.context.annotation.Configuration");

    @Test
    @DisplayName("aucun composant n'a plusieurs constructeurs sans dire lequel Spring doit prendre")
    void aucunComposantNeLaisseSpringHesiter() throws IOException {
        Path classes = Path.of("target", "classes");
        assertThat(classes).as("les classes compilées doivent être là").exists();

        List<String> ambigus = new ArrayList<>();
        try (Stream<Path> fichiers = Files.walk(classes)) {
            fichiers.filter(p -> p.toString().endsWith(".class"))
                    .map(p -> classes.relativize(p).toString()
                            .replace(java.io.File.separatorChar, '.')
                            .replaceAll("\\.class$", ""))
                    .filter(name -> !name.contains("$"))
                    .forEach(name -> inspecter(name, ambigus));
        }

        assertThat(ambigus)
                .as("Ces composants ont plusieurs constructeurs sans qu'aucun ne soit désigné : "
                        + "Spring cherchera un constructeur sans argument et le contexte échouera "
                        + "AU DÉMARRAGE, en production. Annotez le constructeur de production "
                        + "avec @Autowired.")
                .isEmpty();
    }

    private static void inspecter(String className, List<String> ambigus) {
        Class<?> type;
        try {
            type = Class.forName(className, false, SpringConstructorAmbiguityTest.class
                    .getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return; // une classe qu'on ne peut pas charger ne sera pas un bean non plus
        }
        if (type.isInterface() || type.isEnum() || type.isAnnotation() || !estComposant(type)) {
            return;
        }
        Constructor<?>[] constructeurs = type.getDeclaredConstructors();
        if (constructeurs.length <= 1) {
            return;
        }
        for (Constructor<?> constructeur : constructeurs) {
            if (constructeur.isAnnotationPresent(Autowired.class)
                    || constructeur.getParameterCount() == 0) {
                return;
            }
        }
        ambigus.add(className + " (" + constructeurs.length + " constructeurs)");
    }

    /** Stéréotype porté directement, ou par une annotation qui en hérite (méta-annotation). */
    private static boolean estComposant(Class<?> type) {
        if (type.isAnnotationPresent(Component.class)) {
            return true;
        }
        Set<String> vus = new HashSet<>();
        for (Annotation annotation : type.getAnnotations()) {
            if (porteUnStereotype(annotation.annotationType(), vus)) {
                return true;
            }
        }
        return false;
    }

    private static boolean porteUnStereotype(Class<? extends Annotation> type, Set<String> vus) {
        if (!vus.add(type.getName())) {
            return false;
        }
        if (STEREOTYPES.contains(type.getName())) {
            return true;
        }
        if (!type.getName().startsWith("org.springframework")) {
            return false;
        }
        for (Annotation meta : type.getAnnotations()) {
            if (porteUnStereotype(meta.annotationType(), vus)) {
                return true;
            }
        }
        return false;
    }
}
