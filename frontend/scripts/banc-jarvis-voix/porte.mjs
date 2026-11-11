// F-256 — la PORTE du banc à vraie voix : les objectifs chiffrés qu'une version de Jarvis doit tenir avant toute mise
// en ligne (release-checklist, section Jarvis). Réglables par l'environnement pour mesurer sans bloquer.
export const OBJECTIFS = {
  heardRateMin: Number(process.env.PORTE_ENTENDU_MIN ?? 95),
  firstSoundMedianMaxMs: Number(process.env.PORTE_PREMIER_SON_MAX_MS ?? 2000),
  repeatedMax: 0,
  voiciWithoutShowMax: 0,
  fallbacksMax: 0,
};

export function gate(report, objectifs = OBJECTIFS) {
  const t = report.totals;
  const failures = [];
  if (t.heardRate === null || t.heardRate < objectifs.heardRateMin) {
    failures.push(`entendu ${t.heardRate ?? '—'} % < ${objectifs.heardRateMin} %`);
  }
  if (t.firstSoundMedianMs === null || t.firstSoundMedianMs > objectifs.firstSoundMedianMaxMs) {
    failures.push(`premier son médian ${t.firstSoundMedianMs ?? '—'} ms > ${objectifs.firstSoundMedianMaxMs} ms`);
  }
  if (t.repeated.length > objectifs.repeatedMax) {
    failures.push(`${t.repeated.length} réponse(s) répétée(s)`);
  }
  if (t.voiciWithoutShow.length > objectifs.voiciWithoutShowMax) {
    failures.push(`${t.voiciWithoutShow.length} « Voici » sans image`);
  }
  // Cadrage F-256 §4 : TOUT repli compte. Le banc lit la raison de fermeture du fournisseur (`queue_overflow`…) : un repli
  // « expliqué » par le banc reste une phrase en danger ; seul un repli dont la cause est hors de Jarvis serait toléré,
  // et aucun ne l'est à ce jour (07/11 : 0 repli en prod).
  if (t.fallbacks > objectifs.fallbacksMax) {
    failures.push(`${t.fallbacks} repli(s) de transcription ${JSON.stringify(t.fallbackKinds)}`);
  }
  return { pass: failures.length === 0, failures, objectifs };
}
