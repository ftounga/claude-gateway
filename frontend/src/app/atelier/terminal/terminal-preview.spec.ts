import { AtelierTerminalBlock } from '../../core/models/atelier.models';
import {
  PREVIEW_MAX_LINES,
  PREVIEW_MAX_LINE_LENGTH,
  derivePreview,
  samePreview,
} from './terminal-preview';

/**
 * **Ce qu'un terminal dit de lui-même** (F-76 / SF-76-02).
 *
 * <p>Fonction pure, donc testable sans monter un composant ni simuler un tour : c'est ce qui permet
 * de vérifier la règle qui compte — <b>l'attente d'autorisation passe devant tout</b> — et non
 * seulement le chemin heureux.</p>
 */
describe('derivePreview', () => {

  function block(command: string, output = ''): AtelierTerminalBlock {
    return {
      tool: 'bash',
      command,
      toolUseId: null,
      threadId: null,
      output,
      hasOutput: output.length > 0,
      error: false,
      expanded: false,
    };
  }

  it('dit qu’il attend une autorisation, même au milieu d’un tour', () => {
    // LE CAS QUI COMPTE : une demande d'autorisation arrive pendant qu'un tour est « en cours ».
    // Répondre « ça travaille » à ce moment-là est exactement ce qui a coûté douze heures le
    // 2026-09-08 (F-47).
    const preview = derivePreview({
      awaitingCommand: 'rm -rf build',
      running: true,
      blocks: [block('npm test', 'PASS')],
    });

    expect(preview.activity).toBe('AWAITING_APPROVAL');
    expect(preview.activityDetail).toBe('rm -rf build');
  });

  it('nomme la commande en cours quand une commande tourne', () => {
    const preview = derivePreview({
      awaitingCommand: null,
      running: true,
      blocks: [block('npm test', 'RUNS src/app.spec.ts')],
    });

    expect(preview.activity).toBe('RUNNING');
    expect(preview.activityDetail).toBe('npm test');
  });

  it('dit « réfléchit » tant qu’aucune commande n’est engagée', () => {
    // Dire « exécute » sans rien à nommer laisserait croire qu'une commande tourne et qu'on ne
    // sait pas laquelle.
    const preview = derivePreview({ awaitingCommand: null, running: true, blocks: [] });

    expect(preview.activity).toBe('THINKING');
    expect(preview.activityDetail).toBeNull();
  });

  it('retombe sur « inactif » quand rien ne tourne', () => {
    const preview = derivePreview({
      awaitingCommand: null,
      running: false,
      blocks: [block('git status', 'nothing to commit')],
    });

    expect(preview.activity).toBe('IDLE');
    expect(preview.previewLines).toEqual(['$ git status', 'nothing to commit']);
  });

  it('garde les DERNIÈRES lignes, jamais les premières', () => {
    // Un aperçu dit OÙ L'ON EN EST : garder le début d'un `npm test` afficherait éternellement
    // « Running tests… ».
    const output = Array.from({ length: 30 }, (_, i) => `ligne ${i + 1}`).join('\n');

    const preview = derivePreview({
      awaitingCommand: null,
      running: false,
      blocks: [block('npm test', output)],
    });

    expect(preview.previewLines.length).toBe(PREVIEW_MAX_LINES);
    expect(preview.previewLines[PREVIEW_MAX_LINES - 1]).toBe('ligne 30');
  });

  it('ne transporte pas ce qu’il faudrait jeter', () => {
    const preview = derivePreview({
      awaitingCommand: null,
      running: false,
      blocks: [block('echo', 'x'.repeat(4000))],
    });

    expect(preview.previewLines.at(-1)?.length).toBe(PREVIEW_MAX_LINE_LENGTH);
  });

  it('écarte les lignes vides plutôt que d’en remplir la tuile', () => {
    const preview = derivePreview({
      awaitingCommand: null,
      running: false,
      blocks: [block('ls', '\n\n  \nfichier.txt')],
    });

    expect(preview.previewLines).toEqual(['$ ls', 'fichier.txt']);
  });
});

describe('samePreview', () => {

  const base = { activity: 'RUNNING' as const, activityDetail: 'npm test', previewLines: ['a'] };

  it('reconnaît deux relevés identiques — c’est ce qui évite de réémettre pour rien', () => {
    expect(samePreview(base, { ...base, previewLines: ['a'] })).toBe(true);
  });

  it('distingue une activité, un détail ou une ligne qui change', () => {
    expect(samePreview(base, { ...base, activity: 'IDLE' })).toBe(false);
    expect(samePreview(base, { ...base, activityDetail: 'npm run build' })).toBe(false);
    expect(samePreview(base, { ...base, previewLines: ['b'] })).toBe(false);
    expect(samePreview(base, { ...base, previewLines: ['a', 'b'] })).toBe(false);
  });

  it('traite le néant comme une valeur à part entière', () => {
    expect(samePreview(null, null)).toBe(true);
    expect(samePreview(base, null)).toBe(false);
  });
});
