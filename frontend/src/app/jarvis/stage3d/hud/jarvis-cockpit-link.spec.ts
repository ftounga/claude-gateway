import { testUniverse } from '../../scenes/jarvis-universe.fixture';
import { IDLE_SCREEN, ScreenSignals } from '../jarvis-stage-director';
import { BANC_SCENE_VIEWS } from '../scenes3d/jarvis-scene3d.fixture';
import {
  CockpitFollower, CockpitLeader, LinkChannel, cockpitPlacement, decodeSignals, encodeSignals,
} from './jarvis-cockpit-link';

/** Deux bouts d'un canal en mémoire : ce que l'un poste, l'autre le reçoit (clonage structuré comme le navigateur). */
function pair(): [LinkChannel, LinkChannel, { posts: number }] {
  const stats = { posts: 0 };
  const make = (): LinkChannel & { other?: LinkChannel; closed: boolean } => ({
    onmessage: null, closed: false,
    postMessage(message: unknown) {
      if (this.closed) {
        throw new Error('fermé');
      }
      stats.posts++;
      const other = this.other;
      if (other && !(other as unknown as { closed: boolean }).closed) {
        other.onmessage?.({ data: structuredClone(message) });
      }
    },
    close() {
      this.closed = true;
    },
  });
  const a = make();
  const b = make();
  a.other = b;
  b.other = a;
  return [a, b, stats];
}

const SIGNALS: ScreenSignals = { ...IDLE_SCREEN, universe: testUniverse(), subjectId: 's-forge', state: 'reflexion',
  turnActive: true, pulses: 3, scene: BANC_SCENE_VIEWS['commande'],
  ambient: { hostDown: null, liveSubjects: new Set(['s-forge']), calling: null, cost: null, alert: null } };

describe('F-236 / SF-236-24 — vue cockpit : lien meneur → suiveur (canal local, aucun réseau)', () => {
  it('codage : l’ensemble des sujets vivants traverse le clonage, tout le reste à l’identique', () => {
    const back = decodeSignals(structuredClone(encodeSignals(SIGNALS)));
    expect(back.ambient?.liveSubjects.has('s-forge')).toBeTrue();
    expect(back.scene).toEqual(SIGNALS.scene);
    expect(back.universe).toEqual(SIGNALS.universe);
  });

  it('le meneur ne publie qu’en présence d’un suiveur, et lui renvoie aussitôt l’état courant', () => {
    const [a, b, stats] = pair();
    const leader = new CockpitLeader(a);
    expect(stats.posts).toBe(1); // l'annonce « leader »
    leader.publish(SIGNALS);
    expect(stats.posts).toBe(1);
    const got: (ScreenSignals | null)[] = [];
    new CockpitFollower(b, (s) => got.push(s));
    expect(leader.listening).toBeTrue();
    expect(got.length).toBe(1);
    expect(got[0]?.pulses).toBe(3);
    leader.publish({ ...SIGNALS, pulses: 4 });
    expect(got.at(-1)?.pulses).toBe(4);
  });

  it('fermeture de l’un sans erreur pour l’autre : meneur parti ⇒ null ; suiveur parti ⇒ plus de publication', () => {
    const [a, b, stats] = pair();
    const leader = new CockpitLeader(a);
    const got: (ScreenSignals | null)[] = [];
    const follower = new CockpitFollower(b, (s) => got.push(s));
    leader.publish(SIGNALS);
    follower.close();
    expect(leader.listening).toBeFalse();
    const before = stats.posts;
    expect(() => leader.publish(SIGNALS)).not.toThrow();
    expect(stats.posts).toBe(before);

    const [c, d] = pair();
    const leader2 = new CockpitLeader(c);
    const got2: (ScreenSignals | null)[] = [];
    new CockpitFollower(d, (s) => got2.push(s));
    leader2.publish(SIGNALS);
    expect(() => leader2.close()).not.toThrow();
    expect(got2.at(-1)).toBeNull();
  });

  it('un écran principal qui arrive après la vue cockpit s’annonce : elle se présente et le suit', () => {
    const [a, b] = pair();
    const got: (ScreenSignals | null)[] = [];
    new CockpitFollower(b, (s) => got.push(s)); // « hello » perdu : personne n'écoute encore
    const leader = new CockpitLeader(a);
    expect(leader.listening).toBeTrue();
    leader.publish(SIGNALS);
    expect(got.at(-1)?.pulses).toBe(3);
  });

  it('messages étrangers ou en retard ignorés', () => {
    const [a, b] = pair();
    const got: (ScreenSignals | null)[] = [];
    new CockpitFollower(b, (s) => got.push(s));
    a.postMessage({ type: 'autre' });
    a.postMessage({ type: 'signals', leader: 'x', seq: 2, sentAt: 0, signals: encodeSignals(SIGNALS) });
    a.postMessage({ type: 'signals', leader: 'x', seq: 1, sentAt: 0,
      signals: encodeSignals({ ...SIGNALS, pulses: 99 }) });
    expect(got.length).toBe(1);
    expect(got[0]?.pulses).toBe(3);
  });

  it('BroadcastChannel réel : le cockpit suit l’écran principal en moins de 100 ms', async () => {
    const leaderChannel = new BroadcastChannel('jarvis-cockpit-test') as unknown as LinkChannel;
    const followerChannel = new BroadcastChannel('jarvis-cockpit-test') as unknown as LinkChannel;
    const leader = new CockpitLeader(leaderChannel);
    let resolve: (latency: number) => void = () => undefined;
    const arrived = new Promise<number>((r) => (resolve = r));
    const follower = new CockpitFollower(followerChannel, (s) => {
      if (s?.pulses === 7) {
        resolve(performance.now() - sent);
      }
    });
    await new Promise((r) => setTimeout(r, 50)); // le « hello » arrive au meneur
    const sent = performance.now();
    leader.publish({ ...SIGNALS, pulses: 7 });
    const latency = await arrived;
    expect(latency).toBeLessThan(100);
    follower.close();
    leader.close();
  });

  it('placement : sur un autre écran que l’écran courant ; un seul écran ⇒ le navigateur choisit', () => {
    const main = { availLeft: 0, availTop: 0, availWidth: 1920, availHeight: 1040, isPrimary: true };
    const second = { availLeft: 1920, availTop: 0, availWidth: 2560, availHeight: 1400, isPrimary: false };
    expect(cockpitPlacement([main, second], main)).toBe('left=1920,top=0,width=2560,height=1400');
    expect(cockpitPlacement([main], main)).toBeNull();
    expect(cockpitPlacement([main, second], second)).toBe('left=0,top=0,width=1920,height=1040');
  });
});
