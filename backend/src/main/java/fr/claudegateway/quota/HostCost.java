package fr.claudegateway.quota;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Ce que chaque client a <b>réellement coûté</b> sur une fenêtre (F-133 / SF-133-03).
 *
 * <p>À ne pas confondre avec {@code UsageByClient} (F-61), qui répond à une autre question : ce que
 * le client a <b>consommé</b>, en volumes, avec un coût <b>estimé</b> aux tarifs d'affichage. Ici,
 * les montants sont ceux des tours eux-mêmes.</p>
 *
 * @param from     premier jour observé (inclus)
 * @param to       dernier jour observé (inclus)
 * @param costUsd  dépense totale de la fenêtre, en dollars
 * @param clients  clients, du plus coûteux au moins ; « hors client » toujours en dernier
 */
public record HostCost(LocalDate from, LocalDate to, BigDecimal costUsd, List<Client> clients) {

    /**
     * Un client — c'est-à-dire un <b>poste</b>.
     *
     * @param hostId   identifiant du poste, ou {@code null} pour le seau « hors client »
     * @param hostName nom du poste, ou {@code null} s'il a été supprimé depuis (la dépense reste)
     */
    public record Client(UUID hostId, String hostName, BigDecimal costUsd, long totalTokens) {
    }
}
