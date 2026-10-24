import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { VideoComment, VideoSummary } from '../../core/models/videos.models';
import { VideosService } from '../../core/services/videos.service';
import { VideoReviewComponent, VideoReviewSend } from './video-review.component';
import {
  commentsOfVersion,
  draftWithMessage,
  frameAt,
  instantLabel,
  isTypingTarget,
  markersOf,
  reviewClock,
  statusLabel,
} from './video-review';
import { TerminalDraftBridge } from './terminal-draft-bridge';

function summary(version = 1, overrides: Partial<VideoSummary> = {}): VideoSummary {
  return {
    id: 'v-1', workspaceId: 'w-1', kind: 'VIDEO', title: 'Cours', description: null, seriesId: null,
    seriesTitle: null, episode: null, currentVersion: 2, version, durationS: 100, mime: 'video/mp4', sizeBytes: 10,
    label: null, chapters: [], mediaUrl: `/api/video-media/v${version}`, posterUrl: null,
    downloadUrl: `/api/video-media/v${version}?download=1`, createdAt: '', updatedAt: '', ...overrides,
  };
}

function comment(id: string, overrides: Partial<VideoComment> = {}): VideoComment {
  return {
    id, videoId: 'v-1', version: 1, tStartMs: 10_133, tEndMs: null, frame: 304, endFrame: null,
    text: 'Bannière en noir', status: 'OUVERT', resolution: null, resolvedVersion: null, sentAt: null,
    createdAt: '2026-10-25T10:00:00Z', ...overrides,
  };
}

describe('video-review (fonctions pures)', () => {
  it('horloge, image à 30 i/s, étiquette d\'instant et de plage', () => {
    expect(reviewClock(10_133)).toBe('00:10');
    expect(reviewClock(3_723_000)).toBe('1:02:03');
    expect(frameAt(10_133)).toBe(304);
    expect(instantLabel(10_133, null)).toBe('00:10 · image 304');
    expect(instantLabel(80_000, 95_000)).toBe('01:20 → 01:35 · images 2400–2850');
  });

  it('statuts par version, marqueurs à t / durée, tri, brouillon gardé', () => {
    expect(statusLabel(comment('a'))).toBe('Ouvert');
    expect(statusLabel(comment('a', { status: 'ENVOYE' }))).toBe('Envoyé à l\'agent');
    expect(statusLabel(comment('a', { status: 'TRAITE', resolvedVersion: 2 }))).toBe('Traité en v2');
    expect(statusLabel(comment('a', { status: 'REJETE', resolvedVersion: 2, resolution: 'voulu' })))
      .toBe('Rejeté en v2 — voulu');
    const markers = markersOf([comment('a', { tStartMs: 25_000 }), comment('b', { tStartMs: 50_000, tEndMs: 75_000 })], 100);
    expect(markers[0].left).toBe(25);
    expect(markers[0].width).toBe(0);
    expect(markers[1].width).toBe(25);
    expect(markersOf([comment('a')], null)).toEqual([]);
    expect(commentsOfVersion([comment('b', { tStartMs: 9 }), comment('a', { tStartMs: 1 }), comment('c', { version: 2 })], 1)
      .map((c) => c.id)).toEqual(['a', 'b']);
    expect(draftWithMessage('Retours', '')).toBe('Retours');
    expect(draftWithMessage('Retours', ' ma note ')).toBe('Retours\n\nma note');
    expect(isTypingTarget(document.createElement('input'))).toBeTrue();
    expect(isTypingTarget(document.createElement('button'))).toBeFalse();
  });
});

/** Le lecteur à retours (F-214 / SF-214-02). */
describe('VideoReviewComponent', () => {
  let videos: jasmine.SpyObj<VideosService>;
  let snack: jasmine.SpyObj<MatSnackBar>;
  let dialog: jasmine.SpyObj<MatDialog>;

  beforeEach(() => {
    videos = jasmine.createSpyObj<VideosService>('VideosService',
      ['get', 'comments', 'versions', 'addComment', 'reopenComment', 'removeComment', 'sendComments']);
    videos.comments.and.returnValue(of([
      comment('c-1'),
      comment('c-2', { tStartMs: 50_000, status: 'TRAITE', resolvedVersion: 2 }),
      comment('c-3', { version: 2, tStartMs: 70_000, text: 'Sur la v2' }),
    ]));
    videos.versions.and.returnValue(of([
      { version: 2, sizeBytes: 1, mime: 'video/mp4', poster: false, createdAt: '' },
      { version: 1, sizeBytes: 1, mime: 'video/mp4', poster: false, createdAt: '' },
    ]));
    snack = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    TestBed.configureTestingModule({
      imports: [VideoReviewComponent, NoopAnimationsModule],
      providers: [
        { provide: VideosService, useValue: videos },
        { provide: MatSnackBar, useValue: snack },
        { provide: MatDialog, useValue: dialog },
      ],
    });
  });

  function create(video = summary(1)) {
    const fixture = TestBed.createComponent(VideoReviewComponent);
    fixture.componentRef.setInput('video', video);
    fixture.detectChanges();
    return fixture;
  }

  function videoElement(root: HTMLElement): HTMLVideoElement {
    return root.querySelector('video') as HTMLVideoElement;
  }

  it('liste les retours de la version affichée, avec leur statut, et les marqueurs sur la barre', () => {
    const fixture = create();
    const root = fixture.nativeElement as HTMLElement;

    const items = root.querySelectorAll('.review__item');
    expect(items.length).toBe(2);
    expect(items[0].textContent).toContain('00:10 · image 304');
    expect(items[0].textContent).toContain('Ouvert');
    expect(items[1].textContent).toContain('Traité en v2');
    expect(root.querySelectorAll('.review__marker').length).toBe(2);
    expect(root.textContent).toContain('Envoyer à l\'agent (1)');
    expect(root.textContent).not.toContain('Sur la v2');
  });

  it('« Commenter cet instant » met en pause, affiche l\'instant et l\'image ; Entrée enregistre sur la version', () => {
    videos.addComment.and.callFake((_id, body) => of(comment('c-new', { tStartMs: body.tStartMs, text: body.text })));
    const fixture = create();
    const root = fixture.nativeElement as HTMLElement;
    const element = videoElement(root);
    const pause = spyOn(element, 'pause');
    element.currentTime = 10.133;
    Object.defineProperty(element, 'currentTime', { configurable: true, get: () => 10.133, set: () => undefined });

    (root.querySelector('.review__comment') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(pause).toHaveBeenCalled();
    expect(root.textContent).toContain('00:10 · image 304');
    const input = root.querySelector('.review__input') as HTMLInputElement;
    input.value = 'La bannière à droite n\'est pas visible, je la veux en noir';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    fixture.detectChanges();

    expect(videos.addComment).toHaveBeenCalledWith('v-1', {
      version: 1, tStartMs: 10_133, tEndMs: null, text: 'La bannière à droite n\'est pas visible, je la veux en noir',
    });
    expect(root.querySelectorAll('.review__item').length).toBe(3);
    expect(root.querySelector('.review__input')).toBeNull();
  });

  it('la touche C ouvre le champ, hors d\'un champ de texte', () => {
    const fixture = create();
    const root = fixture.nativeElement as HTMLElement;
    root.dispatchEvent(new KeyboardEvent('keydown', { key: 'c', bubbles: true }));
    fixture.detectChanges();
    expect(root.querySelector('.review__input')).not.toBeNull();
  });

  it('« Envoyer à l\'agent » compose côté gateway et émet le message ; rien n\'est envoyé ailleurs', () => {
    videos.sendComments.and.returnValue(of({
      message: 'Retours sur « Cours » v1 (v-1) :\n00:10 (image 304) — Bannière en noir',
      comments: [comment('c-1', { status: 'ENVOYE' })],
    }));
    const fixture = create();
    const sent: VideoReviewSend[] = [];
    fixture.componentInstance.sendToTerminal.subscribe((event) => sent.push(event));
    const root = fixture.nativeElement as HTMLElement;

    (root.querySelector('.review__send-agent') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(videos.sendComments).toHaveBeenCalledWith('v-1', 1);
    expect(sent).toEqual([{
      message: 'Retours sur « Cours » v1 (v-1) :\n00:10 (image 304) — Bannière en noir', workspaceId: 'w-1', videoId: 'v-1',
    }]);
    expect(root.querySelector('.review__item')!.textContent).toContain('Envoyé à l\'agent');
    expect((root.querySelector('.review__send-agent') as HTMLButtonElement).disabled).toBeTrue();
  });

  it('« Copier » met le message dans le presse-papiers', async () => {
    videos.sendComments.and.returnValue(of({ message: 'Retours…', comments: [comment('c-1', { status: 'ENVOYE' })] }));
    const existing = navigator.clipboard.writeText;
    const write = (jasmine.isSpy(existing) ? existing as unknown as jasmine.Spy : spyOn(navigator.clipboard, 'writeText'))
      .and.returnValue(Promise.resolve());
    write.calls.reset();
    const fixture = create();
    (fixture.nativeElement.querySelector('.review__copy') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(write).toHaveBeenCalledWith('Retours…');
    expect(snack.open).toHaveBeenCalledWith('Retours copiés.', 'OK', jasmine.any(Object));
  });

  it('le sélecteur de version recharge l\'autre version au même instant', () => {
    videos.get.and.returnValue(of(summary(2)));
    const fixture = create();
    const root = fixture.nativeElement as HTMLElement;
    Object.defineProperty(videoElement(root), 'currentTime', { configurable: true, get: () => 42, set: () => undefined });

    const toggles = root.querySelectorAll('.review__version-toggle button');
    expect(toggles.length).toBe(2);
    (toggles[0] as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(videos.get).toHaveBeenCalledWith('v-1', 2);
    expect(fixture.componentInstance.start()).toBe(42);
    expect(fixture.componentInstance.shown().version).toBe(2);
    expect(root.textContent).toContain('Sur la v2');
    expect(root.textContent).not.toContain('Bannière en noir');
  });

  it('un instant cliqué fait sauter la vidéo, en pause ; un retour clos se rouvre', () => {
    videos.reopenComment.and.returnValue(of(comment('c-2', { tStartMs: 50_000, status: 'OUVERT' })));
    const fixture = create();
    const root = fixture.nativeElement as HTMLElement;
    const element = videoElement(root);
    const pause = spyOn(element, 'pause');

    (root.querySelectorAll('.review__time')[1] as HTMLButtonElement).click();
    expect(element.currentTime).toBe(50);
    expect(pause).toHaveBeenCalled();

    (root.querySelector('.review__reopen') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(videos.reopenComment).toHaveBeenCalledWith('v-1', 'c-2');
    expect(root.textContent).toContain('Envoyer à l\'agent (2)');
  });

  it('supprimer demande confirmation (MatDialog), jamais window.confirm', () => {
    videos.removeComment.and.returnValue(of(undefined));
    dialog.open.and.returnValue({ afterClosed: () => of(true) } as ReturnType<MatDialog['open']>);
    const confirm = spyOn(window, 'confirm');
    const fixture = create();
    const root = fixture.nativeElement as HTMLElement;

    (root.querySelector('.review__remove') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(dialog.open).toHaveBeenCalled();
    expect(confirm).not.toHaveBeenCalled();
    expect(videos.removeComment).toHaveBeenCalledWith('v-1', 'c-1');
    expect(root.querySelectorAll('.review__item').length).toBe(1);
  });
});

describe('TerminalDraftBridge', () => {
  it('un terminal du même sujet prend le message et accuse réception ; un autre sujet, non', async () => {
    const bridge = new TerminalDraftBridge();
    const received: string[] = [];
    const stop = bridge.listen((workspaceId, text) => {
      if (workspaceId !== 'w-1') {
        return false;
      }
      received.push(text);
      return true;
    });

    expect(await bridge.offer('w-1', 'Retours', 500)).toBeTrue();
    expect(received).toEqual(['Retours']);
    expect(await bridge.offer('w-2', 'Autres', 100)).toBeFalse();
    stop();
    expect(await bridge.offer('w-1', 'Plus personne', 100)).toBeFalse();
  });
});
