import {
  CONVERSATION_IDLE_MS, CONVERSATION_IDLE_MS_KEY, CONVERSATION_READY_KEY, Conversation, MAX_CONVERSATION_IDLE_MS,
  MIN_CONVERSATION_IDLE_MS, canAutoStart, conversationIdleMs, isNoise, isPauseCommand,
} from './jarvis-conversation';

function store(values: Record<string, string>): Pick<Storage, 'getItem'> {
  return { getItem: (key: string) => values[key] ?? null };
}

/** **La conversation mains libres** (F-233 / SF-233-11). */
describe('La conversation mains libres (F-233 / SF-233-11)', () => {
  it('reconnaît la pause dite seule, et pas une demande qui en parle', () => {
    for (const said of ['Jarvis, pause.', 'pause', 'Mets-toi en veille', 'mettez-vous en pause', 'en veille merci',
      'Fais une pause, s’il te plaît']) {
      expect(isPauseCommand(said)).withContext(said).toBeTrue();
    }
    for (const said of ['mets le build en pause', 'la pause déjeuner est à quelle heure ?', '', null]) {
      expect(isPauseCommand(said)).withContext(String(said)).toBeFalse();
    }
  });

  it('un bruit ou une hésitation n’est pas une phrase ; une vraie phrase courte l’est', () => {
    for (const said of ['', '   ', '…', 'euh', 'Euh… hum', 'a', 'mmh.']) {
      expect(isNoise(said)).withContext(said).toBeTrue();
    }
    for (const said of ['oui', 'non', 'stop', 'Où en est la migration ?']) {
      expect(isNoise(said)).withContext(said).toBeFalse();
    }
  });

  it('la durée de conversation : 60 s par défaut, réglable et bornée', () => {
    expect(conversationIdleMs(store({}))).toBe(CONVERSATION_IDLE_MS);
    expect(conversationIdleMs(store({ [CONVERSATION_IDLE_MS_KEY]: 'abc' }))).toBe(CONVERSATION_IDLE_MS);
    expect(conversationIdleMs(store({ [CONVERSATION_IDLE_MS_KEY]: '30000' }))).toBe(30_000);
    expect(conversationIdleMs(store({ [CONVERSATION_IDLE_MS_KEY]: '1' }))).toBe(MIN_CONVERSATION_IDLE_MS);
    expect(conversationIdleMs(store({ [CONVERSATION_IDLE_MS_KEY]: '99999999' }))).toBe(MAX_CONVERSATION_IDLE_MS);
  });

  it('réécoute seulement en conversation, au repos, sans tour en cours', () => {
    const conversation = new Conversation(() => 60_000, () => 0);
    expect(conversation.shouldRelisten('repos', false)).toBeFalse();
    conversation.start();
    expect(conversation.shouldRelisten('repos', false)).toBeTrue();
    expect(conversation.shouldRelisten('repos', true)).toBeFalse();
    expect(conversation.shouldRelisten('parole', false)).toBeFalse();
    expect(conversation.shouldRelisten('ecoute', false)).toBeFalse();
    conversation.pause();
    expect(conversation.mode()).toBe('paused');
    expect(conversation.shouldRelisten('repos', false)).toBeFalse();
  });

  it('le silence clôt la conversation après la durée réglée ; une demande le remet à zéro', () => {
    let now = 1_000;
    const conversation = new Conversation(() => 60_000, () => now);
    conversation.start();
    now += 59_999;
    expect(conversation.idleExpired()).toBeFalse();
    conversation.touch();
    now += 59_999;
    expect(conversation.idleExpired()).toBeFalse();
    now += 1;
    expect(conversation.idleExpired()).toBeTrue();
    conversation.rest();
    expect(conversation.idleExpired()).toBeFalse();
  });

  it('démarre seule seulement si le premier geste a été retenu et que la page a déjà vu un geste', () => {
    const ready = store({ [CONVERSATION_READY_KEY]: '1' });
    expect(canAutoStart(ready, { userActivation: { hasBeenActive: true } })).toBeTrue();
    expect(canAutoStart(ready, { userActivation: { hasBeenActive: false } })).toBeFalse();
    expect(canAutoStart(ready, {})).toBeFalse();
    expect(canAutoStart(store({}), { userActivation: { hasBeenActive: true } })).toBeFalse();
  });
});
