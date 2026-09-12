package fr.claudegateway.contract;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Le mapper <b>réel</b> de chaque côté du contrat (F-81 / SF-81-01).
 *
 * <p>Tout l'intérêt du module tient dans ce mot. Les quatre tests ajoutés par SF-48-04 jouaient une
 * chaîne JSON écrite à la main : ils décrivaient ce que l'auteur du test <i>croyait</i> que la
 * gateway émettait. Si les deux côtés dérivent à nouveau, ils continuent de passer. Ici, l'émetteur
 * sérialise son propre objet avec le mapper qu'il emploie en service, et le récepteur le lit avec le
 * sien.</p>
 *
 * <p><b>Côté gateway</b>, ce mapper n'est pas un {@code new ObjectMapper()} : c'est le bean construit
 * par {@link JacksonAutoConfiguration}, celui-là même que Spring injecte dans les contrôleurs. La
 * différence n'est pas théorique — c'est lui qui décide qu'une date part en ISO-8601 plutôt qu'en
 * nombre de secondes, et le {@code expiresAt} de l'appairage en dépend directement. Le construire à
 * la main reviendrait à réécrire l'hypothèse qu'on veut vérifier.</p>
 *
 * <p><b>Côté runner</b>, le mapper de production n'est pas exposé : il est privé à
 * {@code PairingClient} et à {@code ToolDispatcher}. On ne le reconstruit donc pas — les tests
 * appellent ces classes, et c'est leur propre mapper qui travaille. Le seul mapper reconstruit ici
 * est celui de lecture des <b>trames</b>, dont {@code FrameRouter} et {@code ToolDispatcher} usent à
 * l'identique : {@code new ObjectMapper()}, en configuration par défaut — <b>stricte</b>, ce qui est
 * précisément ce qui a fait tomber l'appairage le 2026-09-10.</p>
 */
final class ContractMappers {

    /** Construit une fois : démarrer l'auto-configuration Jackson par test coûterait pour rien. */
    private static final ObjectMapper GATEWAY = buildGatewayMapper();

    private ContractMappers() {
    }

    /**
     * Le mapper de la <b>gateway</b> : le bean Spring Boot, pas une copie approchante.
     *
     * <p>Aucune base, aucun serveur web, aucun composant applicatif — seule
     * {@link JacksonAutoConfiguration} est chargée. Le backend ne publie aucun {@code @Bean
     * ObjectMapper} ni aucune propriété {@code spring.jackson.*} : ce contexte minimal produit donc
     * exactement le mapper de production.</p>
     */
    static ObjectMapper gateway() {
        return GATEWAY;
    }

    /**
     * Le mapper de <b>lecture de trames</b> du runner : {@code new ObjectMapper()}, tel que
     * {@code FrameRouter} et {@code ToolDispatcher} le construisent.
     *
     * <p>Configuration par défaut, donc <b>stricte</b> sur les champs inconnus quand elle lit vers
     * une classe. Les trames se lisent en {@code JsonNode}, où la question ne se pose pas ; elle se
     * pose pour les DTO, et c'est l'objet de SF-81-02.</p>
     */
    static ObjectMapper runnerFrames() {
        return new ObjectMapper();
    }

    private static ObjectMapper buildGatewayMapper() {
        ObjectMapper[] captured = new ObjectMapper[1];
        new ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations
                        .of(JacksonAutoConfiguration.class))
                .run(context -> captured[0] = context.getBean(ObjectMapper.class));
        if (captured[0] == null) {
            throw new IllegalStateException(
                    "Le mapper Jackson de la gateway n'a pas pu être construit.");
        }
        return captured[0];
    }
}
