import {
  SLASH_PANEL_COMMANDS,
  buildHelpEntries,
  buildPanel,
  findPanelCommand,
  panelCommandSuggestions,
  parsePanelCommand,
  slashPanelKindLabel,
} from './slash-panel-commands';

/**
 * Le REGISTRE des commandes slash « à notre sauce » (F-165 / SF-165-01). On vérifie l'autocomplétion,
 * l'interception à l'envoi (le socle de la garantie « aucun tour »), et la construction du panneau
 * `/aide`. Module frontend pur — aucun TestBed nécessaire.
 */
describe('slash-panel-commands (F-165 / SF-165-01)', () => {
  it('propose /aide dès qu\'on tape « / »', () => {
    const names = panelCommandSuggestions('/').map((c) => c.name);
    expect(names).toContain('aide');
  });

  it('filtre au préfixe (« /ai » → /aide ; « lance » → rien)', () => {
    expect(panelCommandSuggestions('/ai').map((c) => c.name)).toContain('aide');
    expect(panelCommandSuggestions('lance les tests')).toEqual([]);
    // Jeton déjà clos par une espace : plus une frappe de commande.
    expect(panelCommandSuggestions('/aide ')).toEqual([]);
  });

  it('retrouve une commande par son nom, insensible à la casse', () => {
    expect(findPanelCommand('AIDE')?.name).toBe('aide');
    expect(findPanelCommand('inconnue')).toBeUndefined();
  });

  it('intercepte une commande F-165 connue et en extrait l\'argument', () => {
    const parsed = parsePanelCommand('/aide');
    expect(parsed?.command.name).toBe('aide');
    expect(parsed?.arg).toBe('');
  });

  it('n\'intercepte NI une macro de prompt F-121, NI un message ordinaire (→ null)', () => {
    // `/revue` est une macro F-121 (envoyée au modèle), pas une commande vue/action F-165.
    expect(parsePanelCommand('/revue')).toBeNull();
    expect(parsePanelCommand('lance les tests')).toBeNull();
    expect(parsePanelCommand('')).toBeNull();
  });

  it('construit le panneau /aide (panelKind « help ») avec la liste des commandes', () => {
    const aide = findPanelCommand('aide')!;
    const panel = buildPanel(aide, '', 'slash-0');
    expect(panel.panelKind).toBe('help');
    expect(panel.command).toBe('/aide');
    expect(panel.title).toBe('Commandes disponibles');
    expect(panel.help?.length).toBe(SLASH_PANEL_COMMANDS.length);
    expect(panel.help?.map((e) => e.command)).toContain('/aide');
  });

  it('nomme la famille lisiblement (Vue / Action / Aide)', () => {
    expect(slashPanelKindLabel('view')).toBe('Vue');
    expect(slashPanelKindLabel('action')).toBe('Action');
    expect(slashPanelKindLabel('meta')).toBe('Aide');
  });

  it('buildHelpEntries reflète le registre', () => {
    const entries = buildHelpEntries();
    expect(entries.length).toBe(SLASH_PANEL_COMMANDS.length);
    expect(entries.every((e) => e.command.startsWith('/'))).toBe(true);
  });

  // ------------------------------------------------------ F-165 / SF-165-02 : /cout (vue économie du fil)

  it('inscrit /cout au registre comme une VUE (panelKind « cost »)', () => {
    const cout = findPanelCommand('cout');
    expect(cout).toBeDefined();
    expect(cout?.kind).toBe('view');
    expect(cout?.panelKind).toBe('cost');
    expect(cout?.takesArgument).toBe(false);
  });

  it('propose /cout au préfixe (« /co » → /cout ; « / » → /cout aussi)', () => {
    expect(panelCommandSuggestions('/co').map((c) => c.name)).toContain('cout');
    expect(panelCommandSuggestions('/').map((c) => c.name)).toContain('cout');
  });

  it('intercepte /cout à l\'envoi (aucun argument)', () => {
    const parsed = parsePanelCommand('/cout');
    expect(parsed?.command.name).toBe('cout');
    expect(parsed?.arg).toBe('');
  });

  it('construit le panneau /cout en état de chargement (la donnée vient d\'un GET)', () => {
    const cout = findPanelCommand('cout')!;
    const panel = buildPanel(cout, '', 'slash-1');
    expect(panel.panelKind).toBe('cost');
    expect(panel.command).toBe('/cout');
    expect(panel.costState).toBe('loading');
    expect(panel.cost).toBeUndefined();
  });
});
