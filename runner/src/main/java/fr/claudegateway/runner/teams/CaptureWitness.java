package fr.claudegateway.runner.teams;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

/**
 * <b>Le témoin au premier plan</b> (F-91 / SF-91-02, garde-fou n° 3).
 *
 * <h2>Ce qu'il fait, et ce qu'il ne fait pas</h2>
 *
 * <p>Il <b>ne prévient que l'utilisateur du poste</b> — et c'est son but, écrit tel quel dans le
 * cadrage : <i>éviter la capture oubliée qui tourne trois heures</i>. Il ne protège pas les
 * participants : on ne peut rien afficher dans la réunion des autres, seul Teams le peut. Le
 * confondre avec une protection des tiers serait se raconter une histoire.</p>
 *
 * <h2>Pourquoi une fenêtre du poste, et pas un bandeau dans l'application</h2>
 *
 * <p>Une page web ne peut pas rester <b>au-dessus</b> des autres fenêtres, et c'est exactement la
 * seule propriété qui compte ici : un témoin qu'un partage d'écran recouvre ne témoigne plus. Le
 * témoin vit donc là où la capture vit — sur la machine.</p>
 *
 * <h2>Pas de croix</h2>
 *
 * <p>{@link WindowConstants#DO_NOTHING_ON_CLOSE} et une fenêtre sans décor : fermer le témoin sans
 * arrêter la capture reviendrait à retirer le garde-fou en gardant le risque. <b>Le seul geste est
 * « Arrêter ».</b></p>
 *
 * <h2>Rien n'est chargé tant qu'on n'enregistre pas</h2>
 *
 * <p>Cette classe n'est instanciée qu'au démarrage d'une capture. Un runner qui n'enregistre jamais
 * — l'immense majorité — ne touche jamais au module graphique.</p>
 */
public final class CaptureWitness implements LocalCapture.Witness {

    /** Rafraîchissement de la durée. Une seconde : c'est la résolution qu'on affiche. */
    static final int TICK_MS = 1_000;
    /** Marge depuis le coin de l'écran. */
    static final int SCREEN_MARGIN = 24;

    private final Supplier<Instant> clock;
    private final Headless headless;

    private volatile JFrame frame;
    private volatile Timer ticker;

    public CaptureWitness() {
        this(Instant::now, GraphicsEnvironment::isHeadless);
    }

    CaptureWitness(Supplier<Instant> clock, Headless headless) {
        this.clock = clock == null ? Instant::now : clock;
        this.headless = headless == null ? () -> true : headless;
    }

    /**
     * Peut-on montrer un témoin sur ce poste ? Appelé <b>avant</b> de lancer quoi que ce soit : un
     * refus ne doit rien laisser derrière lui.
     */
    @Override
    public void requireAvailable() {
        if (headless.isHeadless()) {
            throw new CaptureWitnessException(
                    "Ce poste n'a pas d'environnement graphique : je ne peux pas afficher le témoin "
                            + "qui reste au premier plan pendant l'enregistrement, et sans lui rien "
                            + "n'empêcherait une capture oubliée de tourner des heures. Je ne "
                            + "capture pas.",
                    "Lancez le runner depuis la session graphique du poste (pas depuis une "
                            + "connexion à distance sans écran), puis redemandez. Un poste sans "
                            + "écran n'a de toute façon rien à capturer.");
        }
    }

    /**
     * Montre le témoin, et rend <b>ce qui n'a pas pu être fait</b>.
     *
     * <p>Le seul manque possible ici est celui-ci : un gestionnaire de fenêtres qui refuse le
     * premier plan. On montre quand même — un témoin visible vaut mieux que pas de témoin — et
     * <b>on le nomme</b>, parce qu'un garde-fou affaibli en silence est un garde-fou qu'on croit
     * avoir.</p>
     */
    @Override
    public List<TeamsGap> show(CaptureRecord record, Runnable stop) {
        requireAvailable();
        List<TeamsGap> gaps = new ArrayList<>();
        try {
            onSwing(() -> build(record, stop, gaps));
        } catch (RuntimeException e) {
            throw new CaptureWitnessException(
                    "Le témoin d'enregistrement n'a pas pu être affiché sur ce poste, et sans lui "
                            + "je ne capture pas.",
                    "Vérifiez que la session graphique est accessible depuis le runner, puis "
                            + "redemandez.", e);
        }
        return List.copyOf(gaps);
    }

    @Override
    public void hide() {
        onSwing(() -> {
            Timer current = ticker;
            if (current != null) {
                current.stop();
                ticker = null;
            }
            JFrame window = frame;
            if (window != null) {
                window.setVisible(false);
                window.dispose();
                frame = null;
            }
        });
    }

    // ------------------------------------------------------------------ la fenêtre

    private void build(CaptureRecord record, Runnable stop, List<TeamsGap> gaps) {
        JFrame window = new JFrame("Enregistrement en cours");
        // Aucune croix : fermer sans arrêter serait retirer le garde-fou en gardant le risque.
        window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        window.setUndecorated(true);
        window.setAlwaysOnTop(true);
        if (!window.isAlwaysOnTop()) {
            // Le système refuse le premier plan : on montre quand même, ET ON LE DIT.
            gaps.add(new TeamsGap(TeamsGapKind.NOTHING_OBSERVED, "témoin d'enregistrement",
                    "ce système ne permet pas de garder le témoin au-dessus des autres fenêtres : "
                            + "il est affiché, mais il peut être recouvert — surveillez la durée", 1));
        }

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xB3, 0x26, 0x1A), 2),
                BorderFactory.createEmptyBorder(12, 16, 12, 16)));
        panel.setBackground(Color.WHITE);

        JLabel title = new JLabel(headline(record));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setForeground(new Color(0xB3, 0x26, 0x1A));

        JLabel elapsed = new JLabel(CaptureRecord.clock(Duration.ZERO));
        elapsed.setFont(new Font(Font.MONOSPACED, Font.BOLD, 22));

        JButton stopButton = new JButton("Arrêter l'enregistrement");
        stopButton.setFocusPainted(false);
        // Le bouton passe par le MÊME chemin que l'outil d'arrêt : deux chemins finiraient par
        // diverger sur l'essentiel — écrire l'état, fermer le conteneur proprement.
        stopButton.addActionListener(event -> stop.run());

        panel.add(title);
        panel.add(elapsed);
        panel.add(stopButton);

        JPanel root = new JPanel(new BorderLayout());
        root.add(panel, BorderLayout.CENTER);
        window.setContentPane(root);
        window.pack();
        placeTopRight(window);
        window.setVisible(true);

        Timer timer = new Timer(TICK_MS,
                event -> elapsed.setText(CaptureRecord.clock(record.elapsed(clock.get()))));
        timer.start();

        this.frame = window;
        this.ticker = timer;
    }

    /** Ce que le témoin dit, en une ligne. Le texte est ici pour être éprouvable sans écran. */
    static String headline(CaptureRecord record) {
        return record.purpose() == CapturePurpose.MEETING_WITH_OTHERS
                ? "● ENREGISTREMENT — une réunion à plusieurs"
                : "● ENREGISTREMENT — mon propre écran";
    }

    /** En haut à droite : là où l'œil revient, et là où un partage d'écran recouvre le moins. */
    private static void placeTopRight(JFrame window) {
        try {
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            window.setLocation(screen.width - window.getWidth() - SCREEN_MARGIN, SCREEN_MARGIN);
        } catch (RuntimeException e) {
            window.setLocationRelativeTo(null);
        }
    }

    /** Tout ce qui touche à la fenêtre passe par le fil de l'interface, comme Swing l'exige. */
    private static void onSwing(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("affichage du témoin interrompu", e);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException("le témoin n'a pas pu être construit", e.getCause());
        }
    }

    /** L'absence d'environnement graphique, isolée pour être éprouvable. */
    @FunctionalInterface
    interface Headless {
        boolean isHeadless();
    }
}
