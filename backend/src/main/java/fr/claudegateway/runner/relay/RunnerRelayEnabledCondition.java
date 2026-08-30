package fr.claudegateway.runner.relay;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Le relais interne n'existe que si {@code app.runner.relay.secret} est <b>non vide</b>
 * (F-38 / SF-38-12).
 *
 * <p>Un {@code @ConditionalOnProperty} ne sait pas exprimer « non vide » : d'où cette condition
 * explicite. Elle gouverne tout le relais — connecteur supplémentaire, filtre, chaîne de sécurité
 * dédiée, contrôleur interne et client sortant. Secret absent ⇒ rien n'est publié : pas de surface
 * d'attaque et pas de repli non authentifié.</p>
 */
public class RunnerRelayEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String secret = context.getEnvironment().getProperty("app.runner.relay.secret", "");
        return secret != null && !secret.isBlank();
    }
}
