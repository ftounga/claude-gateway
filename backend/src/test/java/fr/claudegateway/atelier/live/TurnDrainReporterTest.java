package fr.claudegateway.atelier.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.slf4j.LoggerFactory;

/**
 * Ce que l'arrêt du pod annonce (F-84 / SF-84-08).
 *
 * <p>Le drainage sans journal serait un drainage invisible : le jour où un client dit « il s'est
 * arrêté tout seul », c'est cette ligne qu'on ira chercher. On vérifie donc qu'elle existe, qu'elle
 * porte le bon compte, et qu'elle ne porte <b>rien d'autre</b> — pas d'identifiant d'utilisateur,
 * pas d'identifiant de projet.</p>
 */
class TurnDrainReporterTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private final LiveTurnRegistry registry = new LiveTurnRegistry(new ObjectMapper());
    private final TurnDrainReporter reporter = new TurnDrainReporter(registry, 300);

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(TurnDrainReporter.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(appender);
        appender.stop();
    }

    private ContextClosedEvent closedEvent() {
        ConfigurableApplicationContext context = new GenericApplicationContext();
        return new ContextClosedEvent(context);
    }

    @Test
    void sansTourVivantLarretLeDitEtNeSalarmePas() {
        assertThatCode(() -> reporter.onContextClosed(closedEvent())).doesNotThrowAnyException();

        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(events.get(0).getFormattedMessage()).contains("aucun tour vivant");
    }

    @Test
    void avecDesToursVivantsLarretAnnonceLeurNombreEtLeDelai() {
        registry.open(ALICE, UUID.randomUUID());
        registry.open(BOB, UUID.randomUUID());

        reporter.onContextClosed(closedEvent());

        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel())
                .as("c'est le seul moment où un tour peut encore être perdu")
                .isEqualTo(Level.WARN);
        assertThat(events.get(0).getFormattedMessage()).contains("2 tour(s)").contains("300 s");
    }

    @Test
    void leJournalNeNommeNiUtilisateurNiProjet() {
        UUID projet = UUID.randomUUID();
        registry.open(ALICE, projet);

        reporter.onContextClosed(closedEvent());

        String ligne = appender.list.get(0).getFormattedMessage();
        assertThat(ligne)
                .as("un compte, jamais une identité : rien de nominatif dans un journal d'arrêt")
                .doesNotContain(ALICE.toString())
                .doesNotContain(projet.toString());
    }

    @Test
    void unTourDejaFermeNeComptePas() {
        LiveTurn turn = registry.open(ALICE, UUID.randomUUID());
        registry.close(turn);

        reporter.onContextClosed(closedEvent());

        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.INFO);
    }
}
