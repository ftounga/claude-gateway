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

  // -------------------------------------------- F-165 / SF-165-03 : /contexte (vue état mémoire du fil)

  it('inscrit /contexte au registre comme une VUE (panelKind « context »)', () => {
    const contexte = findPanelCommand('contexte');
    expect(contexte).toBeDefined();
    expect(contexte?.kind).toBe('view');
    expect(contexte?.panelKind).toBe('context');
    expect(contexte?.takesArgument).toBe(false);
  });

  it('propose /contexte au préfixe (« /con » → /contexte ; « / » → /contexte aussi)', () => {
    expect(panelCommandSuggestions('/con').map((c) => c.name)).toContain('contexte');
    expect(panelCommandSuggestions('/').map((c) => c.name)).toContain('contexte');
  });

  it('intercepte /contexte à l\'envoi (aucun argument)', () => {
    const parsed = parsePanelCommand('/contexte');
    expect(parsed?.command.name).toBe('contexte');
    expect(parsed?.arg).toBe('');
  });

  it('construit le panneau /contexte en état de chargement (la donnée vient d\'un GET)', () => {
    const contexte = findPanelCommand('contexte')!;
    const panel = buildPanel(contexte, '', 'slash-2');
    expect(panel.panelKind).toBe('context');
    expect(panel.command).toBe('/contexte');
    expect(panel.contextState).toBe('loading');
    expect(panel.context).toBeUndefined();
  });

  // ------------------------------------ F-165 / SF-165-04 : /quota + /budget (vues conso & budget)

  it('inscrit /quota et /budget au registre comme des VUES', () => {
    const quota = findPanelCommand('quota');
    const budget = findPanelCommand('budget');
    expect(quota?.kind).toBe('view');
    expect(quota?.panelKind).toBe('quota');
    expect(budget?.kind).toBe('view');
    expect(budget?.panelKind).toBe('budget');
  });

  it('propose /quota et /budget au préfixe', () => {
    expect(panelCommandSuggestions('/qu').map((c) => c.name)).toContain('quota');
    expect(panelCommandSuggestions('/bu').map((c) => c.name)).toContain('budget');
    expect(panelCommandSuggestions('/').map((c) => c.name)).toEqual(
      jasmine.arrayContaining(['quota', 'budget']),
    );
  });

  it('intercepte /quota et /budget à l\'envoi', () => {
    expect(parsePanelCommand('/quota')?.command.name).toBe('quota');
    expect(parsePanelCommand('/budget')?.command.name).toBe('budget');
  });

  it('construit le panneau /quota en chargement, et /budget en simple cadre', () => {
    const quota = buildPanel(findPanelCommand('quota')!, '', 'slash-3');
    expect(quota.panelKind).toBe('quota');
    expect(quota.quotaState).toBe('loading');

    const budget = buildPanel(findPanelCommand('budget')!, '', 'slash-4');
    expect(budget.panelKind).toBe('budget');
    expect(budget.title).toBe('Budget de la semaine');
  });

  // ------------------------------------ F-165 / SF-165-05 : /poste + /sujet (vues poste & projet)

  it('inscrit /poste et /sujet au registre comme des VUES', () => {
    expect(findPanelCommand('poste')?.kind).toBe('view');
    expect(findPanelCommand('poste')?.panelKind).toBe('poste');
    expect(findPanelCommand('sujet')?.kind).toBe('view');
    expect(findPanelCommand('sujet')?.panelKind).toBe('sujet');
  });

  it('propose /poste et /sujet au préfixe', () => {
    expect(panelCommandSuggestions('/po').map((c) => c.name)).toContain('poste');
    expect(panelCommandSuggestions('/su').map((c) => c.name)).toContain('sujet');
  });

  it('intercepte /poste et /sujet à l\'envoi', () => {
    expect(parsePanelCommand('/poste')?.command.name).toBe('poste');
    expect(parsePanelCommand('/sujet')?.command.name).toBe('sujet');
  });

  it('construit /poste et /sujet en chargement', () => {
    const poste = buildPanel(findPanelCommand('poste')!, '', 'slash-5');
    expect(poste.panelKind).toBe('poste');
    expect(poste.posteState).toBe('loading');

    const sujet = buildPanel(findPanelCommand('sujet')!, '', 'slash-6');
    expect(sujet.panelKind).toBe('sujet');
    expect(sujet.sujetState).toBe('loading');
  });
});
