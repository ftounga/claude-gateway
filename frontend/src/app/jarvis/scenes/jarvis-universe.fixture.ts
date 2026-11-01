import { JarvisUniverse } from './jarvis-universe.models';

/** Un univers de test : CAGIP (Migration Forge, chantier 3 ; Doc archi) et Edenred (Runner) ; Zahi chez CAGIP. */
export function testUniverse(): JarvisUniverse {
  return {
    generatedAt: '2026-11-01T10:00:00Z',
    toSendCount: 2,
    clients: [
      {
        id: 'h-cagip', name: 'CAGIP', virtual: false, connected: true, missionStatus: 'ACTIVE', truncated: false,
        toSend: ['Zahi'],
        people: [{ name: 'Zahi Ben', variants: ['zahi'], role: 'RSSI' }],
        subjects: [
          { id: 's-term', name: 'Terminal du poste', hostTerminal: true, live: false, chantiers: [] },
          {
            id: 's-forge', name: 'Migration Forge', hostTerminal: false, live: true,
            chantiers: [{
              number: 3, title: 'Bascule DNS', phase: 'EXECUTION', phaseLabel: 'Exécution', done: 2, total: 5,
              step: { rank: 3, title: 'Basculer' }, focus: true, live: false, waitingOn: ['Zahi'],
            }],
          },
          { id: 's-doc', name: 'Doc archi', hostTerminal: false, live: false, chantiers: [] },
          { id: 's-web', name: 'web', hostTerminal: false, live: false, chantiers: [] },
          { id: 's-dup1', name: 'Ingress', hostTerminal: false, live: false, chantiers: [] },
        ],
      },
      {
        id: 'h-eden', name: 'Edenred', virtual: false, connected: false, missionStatus: 'ACTIVE', truncated: false,
        toSend: [], people: [],
        subjects: [
          { id: 's-runner', name: 'Runner', hostTerminal: false, live: false, chantiers: [] },
          { id: 's-dup2', name: 'Ingress', hostTerminal: false, live: false, chantiers: [] },
        ],
      },
    ],
  };
}
