import {
  SLASH_COMMANDS,
  expandSlashCommand,
  findSlashCommand,
  slashSuggestions,
} from './slash-commands';

describe('slash-commands (F-121 / SF-121-23)', () => {
  it('propose tout le catalogue pour « / » seul', () => {
    expect(slashSuggestions('/').length).toBe(SLASH_COMMANDS.length);
  });

  it('filtre les suggestions en préfixe', () => {
    const suggestions = slashSuggestions('/rev');
    expect(suggestions.length).toBe(1);
    expect(suggestions[0].name).toBe('revue');
  });

  it('ne propose rien hors d’un jeton en cours de frappe', () => {
    expect(slashSuggestions('bonjour')).toEqual([]);
    expect(slashSuggestions('')).toEqual([]);
    // Un espace signe la fin du nom : le menu se ferme.
    expect(slashSuggestions('/revue x')).toEqual([]);
    expect(slashSuggestions('/revue ')).toEqual([]);
  });

  it('propose une liste vide quand aucun préfixe ne matche', () => {
    expect(slashSuggestions('/zzz')).toEqual([]);
  });

  it('expanse une commande connue en son prompt imposé', () => {
    const revue = findSlashCommand('revue')!;
    expect(expandSlashCommand('/revue')).toBe(revue.prompt);
  });

  it('ajoute les arguments libres au prompt imposé', () => {
    const explique = findSlashCommand('explique')!;
    expect(expandSlashCommand('/explique le service X')).toBe(
      `${explique.prompt}\n\nle service X`,
    );
  });

  it('est insensible à la casse du nom', () => {
    const revue = findSlashCommand('revue')!;
    expect(expandSlashCommand('/REVUE')).toBe(revue.prompt);
  });

  it('tolère les espaces autour du brouillon', () => {
    const revue = findSlashCommand('revue')!;
    expect(expandSlashCommand('  /revue  ')).toBe(revue.prompt);
  });

  it('renvoie null pour une commande inconnue ou un texte ordinaire', () => {
    expect(expandSlashCommand('/xyz')).toBeNull();
    expect(expandSlashCommand('bonjour')).toBeNull();
    expect(expandSlashCommand('')).toBeNull();
  });

  it('a un catalogue fermé, cohérent (noms en minuscules, uniques)', () => {
    const names = SLASH_COMMANDS.map((command) => command.name);
    expect(new Set(names).size).toBe(names.length);
    for (const command of SLASH_COMMANDS) {
      expect(command.name).toMatch(/^[a-z0-9-]+$/);
      expect(command.prompt.trim().length).toBeGreaterThan(0);
    }
  });
});
