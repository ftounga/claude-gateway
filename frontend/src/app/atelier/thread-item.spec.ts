import { AtelierMessage, AtelierProofBlock } from '../core/models/atelier.models';
import { toThreadItem } from './thread-item';

/** F-231 / SF-231-06 — la marque « 🎙 via Jarvis » survit au rechargement. */
describe('toThreadItem — né à la voix (F-231 / SF-231-06)', () => {
  const base = { id: 'u1', role: 'USER', content: 'valide l’étape 3', createdAt: '2026-11-01T09:00:00Z' };

  it('porte la marque quand le message est né à la voix', () => {
    expect(toThreadItem({ ...base, via: 'JARVIS' } as AtelierMessage).viaJarvis).toBeTrue();
  });

  it('n’ajoute rien pour un message tapé', () => {
    expect(toThreadItem(base as AtelierMessage).viaJarvis).toBeUndefined();
    expect(toThreadItem({ ...base, via: null } as AtelierMessage).viaJarvis).toBeUndefined();
    expect(toThreadItem(base as AtelierMessage).brain).toBeUndefined();
  });

  it('F-235 / SF-235-06 — garde le cerveau : réponse rapide ou délégation à Opus', () => {
    expect(toThreadItem({ ...base, via: 'JARVIS', brain: 'FAST' } as AtelierMessage).brain).toBe('FAST');
    expect(toThreadItem({ ...base, via: 'JARVIS', brain: 'DELEGATED' } as AtelierMessage).brain).toBe('DELEGATED');
    expect(toThreadItem({ ...base, via: 'JARVIS', brain: null } as AtelierMessage).brain).toBeUndefined();
  });
});

/** F-245 / SF-245-03 — la ligne-lien du fil Jarvis garde sa cible au rechargement. */
describe('toThreadItem — ligne-lien (F-245 / SF-245-03)', () => {
  it('porte la cible d\'une ligne LINK, et seulement d\'elle', () => {
    const link = { workspaceId: 'w', chantier: 3, messageId: 'm', status: 'FAIT' as const };
    const item = toThreadItem({ id: 'l', role: 'LINK', content: '↗ Helios', createdAt: '2026-11-04T09:00:00Z', link });
    expect(item.link).toEqual(link);
    expect(toThreadItem({ id: 'u', role: 'USER', content: 'x', createdAt: '2026-11-04T09:00:00Z', link })
      .link).toBeUndefined();
  });
});

/** F-212 / SF-212-06 — la relecture d'un tour garde ses blocs riches (dont les captures de preuve). */
describe('toThreadItem — blocs riches (F-212 / SF-212-06)', () => {
  const proof: AtelierProofBlock = {
    title: 'DNS on-prem', step: '14', host: 'poste', os: 'macos', shell: 'zsh',
    captures: [{ id: 'a'.repeat(32), width: 2002, height: 1132 }],
    commands: [{ command: 'date -u', status: 'done', exitCode: 0, durationMs: 5, startedAt: null, truncated: false }],
  };

  function message(blocks: unknown[]): AtelierMessage {
    return {
      id: 'm1', role: 'ASSISTANT', content: 'ok', createdAt: '2026-10-31T11:55:00Z',
      terminal: { blocks, omittedBlocks: 0, inputTokens: 1, outputTokens: 1, activeSeconds: 1 },
    } as AtelierMessage;
  }

  it('conserve le bloc proof d’un compte rendu persisté', () => {
    const item = toThreadItem(message([
      { tool: 'capturer_preuves', command: null, toolUseId: 'toolu_1', output: 'texte', hasOutput: true, error: false, proof },
    ]));
    expect(item.terminal?.[0].proof).toEqual(proof);
    expect(item.terminal?.[0].tool).toBe('capturer_preuves');
  });

  it('conserve aussi les autres blocs riches (page, courriel, vidéo)', () => {
    const page = { pageId: 'p1', title: 'Rapport' };
    const email = { to: 'x@y.z', subject: 'Objet' };
    const video = { videoId: 'v1', title: 'Cours' };
    const item = toThreadItem(message([
      { tool: 'publier_page', command: null, toolUseId: 't1', output: '', hasOutput: false, error: false, page },
      { tool: 'envoyer_courriel', command: null, toolUseId: 't2', output: '', hasOutput: false, error: false, email },
      { tool: 'video_publier', command: null, toolUseId: 't3', output: '', hasOutput: false, error: false, video },
    ]));
    expect(item.terminal?.[0].page as unknown).toEqual(page);
    expect(item.terminal?.[1].email as unknown).toEqual(email);
    expect(item.terminal?.[2].video as unknown).toEqual(video);
  });

  it('un bloc ancien, sans champ riche, se relit comme avant', () => {
    const item = toThreadItem(message([
      { tool: 'bash', command: 'ls', toolUseId: 't', output: 'a', hasOutput: true, error: false, proof: null },
    ]));
    expect(item.terminal?.[0]).toEqual({
      tool: 'bash', command: 'ls', toolUseId: 't', threadId: null, output: 'a', hasOutput: true, error: false,
      expanded: false,
    });
  });
});
