import { HttpErrorResponse } from '@angular/common/http';

import { RadarVerification, RadarVerificationCheck } from '../../core/models/radar.models';
import { checkRemedy, refusalRemedy, runnerUpdateCommands, verificationLines } from './radar-verification';

/** La vérification guidée, en fonctions pures (F-100 / SF-100-06). */
describe('radar-verification', () => {
  const origin = 'https://portal.example';
  const check = (ok: boolean, state = '', sentence = ''): RadarVerificationCheck =>
    ({ ok, state, count: ok ? 1 : 0, sentence, okSince: ok ? '2026-09-13T10:00:00Z' : null });

  const verification = (extra: Partial<RadarVerification> = {}): RadarVerification => ({
    complete: false, verifiedAt: '2026-09-13T10:00:00Z',
    session: check(true, 'LINKED', 'Session Microsoft active.'),
    conversations: check(true, '', '2 conversation(s) vue(s).'),
    meetings: check(false, '', 'Aucune réunion vue : ouvrez une réunion passée.'),
    transcripts: check(false, 'NOT_SEEN', "Aucune transcription vue : ouvrez l'onglet Transcription."),
    ...extra,
  });

  it('rend quatre étapes dans l’ordre : vu, en attente, sans remède quand il suffit de suivre l’étape', () => {
    const lines = verificationLines(verification(), 'windows', origin);

    expect(lines.map((l) => l.key)).toEqual(['session', 'conversations', 'meetings', 'transcripts']);
    expect(lines.map((l) => l.state)).toEqual(['seen', 'seen', 'waiting', 'waiting']);
    expect(lines[1].sentence).toBe('2 conversation(s) vue(s).');
    expect(lines[3].remedy).toBeNull();
    expect(verificationLines(null, 'windows', origin).every((l) => l.state === 'waiting')).toBeTrue();
  });

  it('liaison Chrome non établie : la commande de lancement du runner, copiable, sans redite', () => {
    const advice = 'Le navigateur n\'est pas joignable sur 127.0.0.1:9222.\n  "chrome.exe" --remote-debugging-port=9222 …';
    const lines = verificationLines(verification({ session: check(false, 'BROWSER_NOT_DETECTED', advice) }), 'windows', origin);

    expect(lines[0].state).toBe('remedy');
    expect(lines[0].remedy?.text).toContain('commande donnée par le runner');
    expect(lines[0].remedy?.commands).toEqual([{ title: 'Donné par le runner', content: advice }]);
    expect(lines[0].sentence).toBe('');
  });

  it('Teams a changé ou volet désactivé : mise à jour ou relance sans --no-teams', () => {
    const changed = checkRemedy('session', check(false, 'TEAMS_CHANGED', 'Teams a changé'), 'macos', origin);
    expect(changed?.text).toContain('Mettez le runner à jour');
    expect(changed?.commands.length).toBe(3);

    const disabled = checkRemedy('session', check(false, 'TEAMS_DISABLED', ''), 'other', origin);
    expect(disabled?.text).toContain('--no-teams');
    expect(checkRemedy('session', check(false, 'NOT_SIGNED_IN', 'Connectez-vous à Teams.'), 'other', origin)?.text)
      .toBe('Connectez-vous à Teams.');
  });

  it('transcriptions : désactivées par le client, ou accès refusé — dit, avec ce qu’on peut y faire', () => {
    expect(checkRemedy('transcripts', check(false, 'DISABLED_OR_NOT_PRODUCED'), 'other', origin)?.text)
      .toContain('désactivée par votre client');
    expect(checkRemedy('transcripts', check(false, 'ACCESS_DENIED'), 'other', origin)?.text)
      .toContain("demandez l'accès à l'organisateur");
    expect(checkRemedy('transcripts', check(true, 'SEEN'), 'other', origin)).toBeNull();
  });

  it('commandes de mise à jour selon le système, sur les routes publiques de la gateway', () => {
    expect(runnerUpdateCommands('windows', `${origin}/`).map((c) => c.content)).toEqual([
      `curl.exe -fL -o claude-runner-windows-x64.zip ${origin}/api/runner/download/windows`,
      `curl.exe -fL -o claude-runner.jar ${origin}/api/runner/download`,
    ]);
    expect(runnerUpdateCommands('macos', origin).map((c) => c.content)).toContain(
      `curl -fL -o claude-runner-macos-aarch64.tar.gz ${origin}/api/runner/download/macos-aarch64`);
    expect(runnerUpdateCommands('other', origin)).toEqual([
      { title: 'Runner (jar)', content: `curl -fL -o claude-runner.jar ${origin}/api/runner/download` },
    ]);
  });

  it('refus : runner trop ancien, réponse illisible, hors ligne, client indisponible, gateway muette', () => {
    const conflict = (error: string, message: string) =>
      new HttpErrorResponse({ status: 409, error: { error, message } });

    const old = refusalRemedy(conflict('radar_teams_disabled', 'Le volet Teams…'), 'windows', origin);
    expect(old.text).toContain('Mettez le runner à jour');
    expect(old.text).toContain('--no-teams');
    expect(old.commands.length).toBe(2);

    const unreadable = refusalRemedy(conflict('radar_runner_unavailable',
      'Réponse illisible du poste : mettez le runner à jour, puis recommencez.'), 'other', origin);
    expect(unreadable.commands.length).toBe(1);

    const offline = refusalRemedy(conflict('radar_runner_unavailable', 'Poste hors ligne : lancez le runner, puis recommencez.'),
      'other', origin);
    expect(offline).toEqual({ text: 'Poste hors ligne : lancez le runner, puis recommencez.', commands: [] });

    expect(refusalRemedy(new HttpErrorResponse({ status: 403 }), 'other', origin).text).toContain("n'est pas disponible");
    expect(refusalRemedy(new HttpErrorResponse({ status: 0 }), 'other', origin).text).toContain("n'a pas répondu");
  });
});
