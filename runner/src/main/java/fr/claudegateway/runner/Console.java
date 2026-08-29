package fr.claudegateway.runner;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Affichage en clair de l'activité du runner (F-38 / SF-38-03, décision D5 : observable et
 * arrêtable). Écrit sur la sortie standard, horodaté, sans dépendance de logging externe — l'opérateur
 * voit exactement ce que fait le runner.
 */
public final class Console {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

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
        System.out.println("[" + LocalTime.now().format(TIME) + "] " + level + "  " + message);
    }
}
