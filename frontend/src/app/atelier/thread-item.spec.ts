import { AtelierMessage, AtelierProofBlock } from '../core/models/atelier.models';
import { toThreadItem } from './thread-item';

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
