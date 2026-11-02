import { CockpitFollower, CockpitLeader, LinkChannel, LinkMessage } from '../stage3d/hud/jarvis-cockpit-link';
import { initialNav, reduceNav } from './jarvis-piece-nav';
import {
  clockOf, commentQuestion, isPinCommand, isUnpinCommand, parseVoiceComment, videoOf,
} from './jarvis-piece-comment';
import { Piece } from './jarvis-piece.models';

const VIDEO_ID = '0f8fad5b-d9cb-469f-a165-70867728950e';
const VIDEO: Piece = { id: `video:${VIDEO_ID}@v2`, kind: 'VIDEO', category: 'PRODUCTION', title: 'Cours 3',
  meta: { durationS: 120 } };
const IMAGE: Piece = { id: 'dep:a', kind: 'IMAGE', category: 'ENTREE', title: 'Capture' };

function opened(piece: Piece) {
  const g = reduceNav(initialNav(), { type: 'openGallery', scope: { subjectId: 's1', label: 'x' }, pieces: [piece] }).state;
  return reduceNav(g, { type: 'select', ref: 1 }).state;
}

/** Deux bouts d'un même canal local (doublure de BroadcastChannel). */
function pair(): [LinkChannel, LinkChannel] {
  const a: LinkChannel = { onmessage: null, postMessage: (m: LinkMessage) => b.onmessage?.({ data: m } as MessageEvent), close: () => undefined } as LinkChannel;
  const b: LinkChannel = { onmessage: null, postMessage: (m: LinkMessage) => a.onmessage?.({ data: m } as MessageEvent), close: () => undefined } as LinkChannel;
  return [a, b];
}

describe('Jarvis — retour vocal horodaté et cockpit (F-237 / SF-237-12)', () => {
  it('« note ici : … » sur une vidéo ouverte : retour horodaté à l\'instant de lecture', () => {
    const c = parseVoiceComment('Note ici : le logo est trop petit', opened(VIDEO), 42.4);
    expect(c).toEqual({ videoId: VIDEO_ID, version: 2, tStartMs: 42_400, text: 'le logo est trop petit' });
    expect(commentQuestion(c!)).toBe('Je note à 0:42 : « le logo est trop petit ». Vous confirmez ?');
    expect(parseVoiceComment('commentaire, couper la fin', opened(VIDEO), null)?.text).toBe('couper la fin');
  });

  it('rien hors d\'une vidéo, rien sans texte, rien pour une phrase ordinaire', () => {
    expect(parseVoiceComment('Note ici : trop sombre', opened(IMAGE), 3)).toBeNull();
    expect(parseVoiceComment('Note ici : trop sombre', initialNav(), 3)).toBeNull();
    expect(parseVoiceComment('note', opened(VIDEO), 3)).toBeNull();
    expect(parseVoiceComment('la suivante', opened(VIDEO), 3)).toBeNull();
    expect(videoOf('page:x@v1')).toBeNull();
    expect(clockOf(3_723_000)).toBe('1:02:03');
  });

  it('épingler / désépingler : liste fermée, phrase entière', () => {
    expect(isPinCommand('Épingle-la au cockpit')).toBeTrue();
    expect(isPinCommand('mets-la sur le grand écran')).toBeTrue();
    expect(isPinCommand('épingle la vidéo du cours 3 sur le mur')).toBeFalse();
    expect(isUnpinCommand('Retire du cockpit')).toBeTrue();
  });

  it('la pièce épinglée passe au cockpit, y compris quand il arrive après', () => {
    const [leaderEnd, followerEnd] = pair();
    const leader = new CockpitLeader(leaderEnd);
    expect(leader.pin(VIDEO)).toBeFalse(); // personne n'écoute encore
    const received: (Piece | null)[] = [];
    const follower = new CockpitFollower(followerEnd, () => undefined, () => 0, (p) => received.push(p));
    expect(received).toEqual([VIDEO]); // renvoyée à l'arrivée du cockpit
    expect(leader.pin(null)).toBeTrue();
    expect(received).toEqual([VIDEO, null]);
    follower.close();
  });
});
