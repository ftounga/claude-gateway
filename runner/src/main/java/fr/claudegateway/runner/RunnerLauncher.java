package fr.claudegateway.runner;

import java.lang.reflect.Method;

/**
 * Point d'entrée du jar (F-38 / SF-38-22).
 *
 * <p><b>Cette classe est compilée pour Java 8</b>, et elle est la seule. Tout le reste du runner
 * vise Java 21. La raison tient en une phrase : sur une JVM trop ancienne, aucune classe compilée
 * en 21 ne se charge — la vérification de version ne peut donc pas vivre dans le code applicatif,
 * qui n'a aucune chance d'être exécuté.</p>
 *
 * <p>Ce que voyait un utilisateur avant elle, au premier lancement chez un client :</p>
 *
 * <pre>
 * Error: A JNI error has occurred, please check your installation and try again
 * Exception in thread "main" java.lang.UnsupportedClassVersionError:
 *   fr/claudegateway/runner/RunnerMain has been compiled by a more recent version of the Java
 *   Runtime (class file version 65.0), this version of the Java Runtime only recognizes class
 *   file versions up to 52.0
 * </pre>
 *
 * <p>Trente lignes pour dire « il vous faut Java 21 » — et aucun des deux nombres affichés n'est
 * une version de Java que l'utilisateur reconnaîtra.</p>
 *
 * <p>N'utiliser ici <b>aucune</b> API postérieure à Java 8, et n'appeler {@code RunnerMain} que par
 * réflexion : une référence directe ferait charger la classe au démarrage, donc échouer avant le
 * message.</p>
 */
public final class RunnerLauncher {

    /** Version minimale de la machine virtuelle. Alignée sur la cible de compilation du runner. */
    private static final int REQUIRED_JAVA = 21;

    private RunnerLauncher() {
    }

    public static void main(String[] args) throws Exception {
        int found = currentMajor(System.getProperty("java.specification.version"));
        // -1 : version illisible. Le doute profite au démarrage (D2) — une JVM exotique mais valide
        // ne doit pas être bloquée par notre lecture d'une propriété système.
        if (found != -1 && found < REQUIRED_JAVA) {
            System.err.println(message(found));
            System.exit(2);
            return;
        }
        // Réflexion : une référence directe à RunnerMain la ferait charger avant ce point, et la
        // JVM échouerait sur UnsupportedClassVersionError — exactement ce que l'on cherche à éviter.
        Class<?> main = Class.forName("fr.claudegateway.runner.RunnerMain");
        Method entry = main.getMethod("main", String[].class);
        entry.invoke(null, (Object) args);
    }

    /**
     * Version majeure de la JVM, ou {@code -1} si la propriété est absente ou illisible.
     *
     * <p>Deux formes historiques : {@code "1.8"} jusqu'à Java 8, {@code "21"} ensuite.</p>
     */
    static int currentMajor(String specVersion) {
        if (specVersion == null) {
            return -1;
        }
        String raw = specVersion.trim();
        if (raw.startsWith("1.")) {
            raw = raw.substring(2);
        }
        int dot = raw.indexOf('.');
        if (dot >= 0) {
            raw = raw.substring(0, dot);
        }
        try {
            int major = Integer.parseInt(raw);
            return major > 0 ? major : -1;
        } catch (NumberFormatException illisible) {
            return -1;
        }
    }

    /** Le message que l'utilisateur lit à la place de la trace. Il nomme la version, pas le format. */
    static String message(int found) {
        return "Ce runner demande Java " + REQUIRED_JAVA + " ou plus recent. "
                + "Cette machine execute Java " + found + "."
                + System.lineSeparator() + System.lineSeparator()
                + "  1. Verifiez la version installee :  java -version" + System.lineSeparator()
                + "  2. Installez un JDK " + REQUIRED_JAVA
                + " : https://adoptium.net/temurin/releases/?version=" + REQUIRED_JAVA
                + System.lineSeparator()
                + "     Aucun droit administrateur n'est necessaire : une archive decompressee suffit."
                + System.lineSeparator()
                + "  3. Relancez en designant ce JDK :" + System.lineSeparator()
                + "     \"/chemin/vers/jdk-" + REQUIRED_JAVA + "/bin/java\" -jar claude-runner.jar "
                + "--gateway ... --workspace ... --code ...";
    }
}
