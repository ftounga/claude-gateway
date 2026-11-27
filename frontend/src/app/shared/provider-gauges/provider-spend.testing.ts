import { ProviderSpend } from '../../admin/cost/admin-cost.models';

/** Les deux pots du mois (F-279 / SF-279-02) — fixture partagée avec l'écran Coût réel. */
export function providerSpend(overrides: Partial<ProviderSpend['channel']> = {}): ProviderSpend {
  return {
    month: '2026-11',
    gauges: [
      {
        provider: 'ANTHROPIC', label: 'Anthropic', billing: 'PREPAID', spentEur: 1060.12, billedEur: 1000,
        estimatedEur: 60.12, billedThrough: '2026-11-25', calculatedEur: 1050, gapEur: 8.5, status: 'RECALE',
        statusNote: 'Recalé sur la facture Anthropic jusqu\'au 25/11, estimé ensuite.', lastFetchedAt: null,
      },
      {
        provider: 'BEDROCK', label: 'AWS Bedrock', billing: 'POSTPAID', spentEur: 66.4, billedEur: 0,
        estimatedEur: 66.4, billedThrough: null, calculatedEur: 66.4, gapEur: null, status: 'ESTIME',
        statusNote: 'Recalage indisponible : droit ce:GetCostAndUsage manquant. Estimation de la passerelle.',
        lastFetchedAt: null,
      },
    ],
    totalEur: 1126.52,
    usdToEur: 0.92,
    channel: {
      mode: 'AUTO', current: 'BEDROCK', circuitOpen: true, cause: 'CREDIT', relay: true, creditExhausted: true,
      lastServed: 'BEDROCK', lastServedAt: null, ...overrides,
    },
    caveats: ['Hors TVA : la carte est débitée toutes taxes comprises.'],
    settleDays: 2,
  };
}
