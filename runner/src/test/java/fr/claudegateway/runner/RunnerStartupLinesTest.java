package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * F-38 / SF-38-26 — ce que la console de démarrage dit de l'exécution de commandes, et le drapeau
 * qu'elle nomme.
 *
 * <p>Le défaut corrigé : la console attribuait l'état à {@code --allow-bash}, sans effet depuis
 * SF-38-19. Dans la branche restreinte, elle donnait donc une consigne de réparation qui ne répare
 * pas — relancer avec {@code --allow-bash} en gardant {@code --no-bash} ne change rien.</p>
 */
class RunnerStartupLinesTest {

    @Test
    void the_allowed_mode_is_said_in_one_line_without_naming_a_flag_that_does_nothing() {
        List<String> lines = RunnerMain.commandModeLines(true);

        assertEquals(1, lines.size(), "l'état autorisé tient en une ligne");
        assertFalse(lines.get(0).contains("--allow-bash"),
                "le drapeau sans effet depuis SF-38-19 n'est plus cité : " + lines.get(0));
        assertTrue(lines.get(0).contains("autorisées"), lines.get(0));
        // La vraie garde est nommée, elle : chaque commande passe par la porte de confirmation.
        assertTrue(lines.get(0).contains("autorisation"), lines.get(0));
    }

    @Test
    void the_restricted_mode_names_the_flag_that_acts_and_how_to_undo_it() {
        List<String> lines = RunnerMain.commandModeLines(false);
        String all = String.join("\n", lines);

        assertTrue(all.contains("refusées"), all);
        assertTrue(all.contains("--no-bash"), "le drapeau qui agit est nommé : " + all);
        assertFalse(all.contains("--allow-bash"),
                "aucune consigne de réparation qui ne répare pas : " + all);
        // Le message perdu en supprimant la ligne dupliquée — « comment revenir en arrière » — est
        // repris ici, avec le bon drapeau.
        assertTrue(all.contains("Relancez sans --no-bash"), all);
    }
}
