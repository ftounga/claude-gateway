import { Component, computed, inject } from '@angular/core';

import { ProviderSpendService } from '../../core/services/provider-spend.service';
import { ProviderGauge } from '../../admin/cost/admin-cost.models';
import { formatEuros } from '../euros';

/**
 * **Les deux pots, là où l'on travaille** (F-279 / SF-279-02) — dans la barre du terminal, à côté du budget de la
 * semaine : ce que le mois a coûté chez Anthropic et chez AWS Bedrock, et le canal qui sert en ce moment.
 *
 * <p>Rien n'est calculé ici : les montants arrivent en euros de la passerelle, recalés sur la facture pour les jours
 * clos et estimés pour le reste. Seule la part de chaque pot dans la barre bicolore est une proportion d'affichage.</p>
 *
 * <p><b>Rien ne s'affiche</b> sans droit de lecture (administrateur seul) ou si l'appel échoue.</p>
 */
@Component({
  selector: 'app-provider-gauges',
  template: `
    @if (spend(); as s) {
      <span class="pots" role="status" [title]="title()">
        @for (gauge of s.gauges; track gauge.provider) {
          <span class="pots__pot" [class.pots__pot--active]="gauge.provider === s.channel.current">
            @if (gauge.provider === s.channel.current) {
              <span class="pots__dot" [class.pots__dot--bedrock]="gauge.provider === 'BEDROCK'" aria-hidden="true"></span>
            }
            <span class="pots__name">{{ shortName(gauge) }}</span>
            <span class="pots__amount num">{{ euros(gauge.spentEur) }}</span>
            @if (gauge.provider === 'ANTHROPIC' && note(); as n) {
              <span class="pots__note">{{ n }}</span>
            }
          </span>
        }
        <span class="pots__split" aria-hidden="true">
          <span class="pots__part pots__part--anthropic" [style.width.%]="anthropicShare()"></span>
          <span class="pots__part pots__part--bedrock" [style.width.%]="100 - anthropicShare()"></span>
        </span>
      </span>
    }
  `,
  styles: `
    .pots {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      font-size: 12px;
      color: var(--cg-text-secondary);
      white-space: nowrap;
    }

    .pots__pot {
      display: inline-flex;
      align-items: center;
      gap: 4px;
    }

    .pots__pot--active .pots__name {
      color: var(--cg-text-primary);
      font-weight: 600;
    }

    .pots__amount {
      font-variant-numeric: tabular-nums;
    }

    /* Le point du canal en service : la couleur de son pot dans la barre. */
    .pots__dot {
      display: inline-block;
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: var(--cg-navy);
    }

    .pots__dot--bedrock {
      background: var(--cg-orange);
    }

    .pots__note {
      color: var(--cg-gold-ink);
    }

    .pots__split {
      display: inline-flex;
      width: 48px;
      height: 6px;
      border-radius: 3px;
      overflow: hidden;
      background: var(--cg-surface-2);
    }

    .pots__part {
      display: block;
      height: 100%;
    }

    .pots__part--anthropic {
      background: var(--cg-navy);
    }

    .pots__part--bedrock {
      background: var(--cg-orange);
    }

    /* La barre du terminal est étroite sur mobile : la carte de l'écran Coût réel reste. */
    @media (max-width: 767px) {
      .pots {
        display: none;
      }
    }
  `,
})
export class ProviderGaugesComponent {
  private readonly pots = inject(ProviderSpendService);

  readonly spend = this.pots.spend;

  /** La part d'Anthropic dans le total du mois, pour la barre bicolore (0 si rien n'est dépensé). */
  readonly anthropicShare = computed(() => {
    const s = this.spend();
    if (!s || s.totalEur <= 0) {
      return 0;
    }
    const anthropic = s.gauges.find((g) => g.provider === 'ANTHROPIC')?.spentEur ?? 0;
    return Math.max(0, Math.min(100, Math.round((anthropic / s.totalEur) * 100)));
  });

  /** La mention courte à côté d'Anthropic quand Bedrock prend le relais. */
  readonly note = computed(() => {
    const channel = this.spend()?.channel;
    if (!channel?.relay) {
      return null;
    }
    return channel.creditExhausted ? 'crédit épuisé' : 'relais';
  });

  readonly title = computed(() => {
    const s = this.spend();
    if (!s) {
      return '';
    }
    const lines = s.gauges.map((g) => `${g.label} : ${formatEuros(g.spentEur)} (${billingOf(g)} ; ${stateOf(g)})`);
    const current = s.channel.current === 'BEDROCK' ? 'AWS Bedrock' : 'Anthropic';
    const relay = s.channel.relay
      ? s.channel.creditExhausted ? ' — crédit Anthropic épuisé' : ' — relais d’Anthropic'
      : '';
    return `Ce mois, hors TVA — ${lines.join(' · ')} · En service : ${current}${relay}`;
  });

  shortName(gauge: ProviderGauge): string {
    return gauge.provider === 'BEDROCK' ? 'Bedrock' : 'Anthropic';
  }

  euros(amount: number): string {
    return formatEuros(amount);
  }
}

function billingOf(gauge: ProviderGauge): string {
  return gauge.billing === 'PREPAID' ? 'prépayé' : 'facturé en fin de mois';
}

function stateOf(gauge: ProviderGauge): string {
  if (gauge.status === 'RECALE' && gauge.billedThrough) {
    const [, month, day] = gauge.billedThrough.split('-');
    return `recalé sur la facture jusqu’au ${day}/${month}, estimé ensuite`;
  }
  return 'estimé par la passerelle';
}
