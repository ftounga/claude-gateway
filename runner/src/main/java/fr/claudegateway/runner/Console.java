package fr.claudegateway.runner;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * Affichage en clair de l'activité du runner (F-38 / SF-38-03, décision D5 : observable et
 * arrêtable). Écrit sur la sortie standard, horodaté, sans dépendance de logging externe — l'opérateur
 * voit exactement ce que fait le runner.
 *
 * <p>Depuis SF-38-26, toute ligne passe par {@link ConsoleEncoding} : c'est le <b>point de passage
 * obligé</b> de la sortie, donc le seul endroit où adapter la typographie française au jeu de
 * caractères du terminal (D3). Réécrire les messages en ASCII à la source aurait réglé le symptôme
 * là où on regardait, et laissé le prochain message écrit en typographie rouvrir le défaut.</p>
 */
public final class Console {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Consumer<String> sink;
    private final ConsoleEncoding encoding;

    public Console() {
        this(System.out::println, ConsoleEncoding.forSystem(System::getProperty));
    }

    /**
     * Console détournée vers une autre sortie — utilisée par les tests, qui doivent pouvoir relire
     * ce que le runner a réellement affiché.
     */
    Console(Consumer<String> sink, ConsoleEncoding encoding) {
        this.sink = sink;
        this.encoding = encoding;
    }

    public void info(String message) {
        print("INFO", message);
    }

    public void warn(String message) {
        print("WARN", message);
    }

    public void error(String message) {
        print("ERREUR", message);
    }

    private void print(String level, String message) {
        sink.accept(encoding.render(
                "[" + LocalTime.now().format(TIME) + "] " + level + "  " + message));
    }
}
