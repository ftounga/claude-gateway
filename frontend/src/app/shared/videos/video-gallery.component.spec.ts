import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { VideoGallery, VideoGalleryItem, VideoSummary } from '../../core/models/videos.models';
import { VideosService } from '../../core/services/videos.service';
import { VideoGalleryComponent } from './video-gallery.component';
import { clearPosition, readPosition, savePosition } from './video-resume';

function item(id: string, episode: number | null, overrides: Partial<VideoGalleryItem> = {}): VideoGalleryItem {
  return {
    id, title: `Titre ${id}`, description: null, seriesId: episode == null ? null : 's-1', episode,
    durationS: 120, currentVersion: 1, chapterCount: 0, posterUrl: null, createdAt: '', ...overrides,
  };
}

function summary(id: string): VideoSummary {
  return {
    id, kind: 'VIDEO', title: `Titre ${id}`, description: null, seriesId: null, seriesTitle: null, episode: null,
    currentVersion: 1, version: 1, durationS: 120, mime: 'video/mp4', sizeBytes: 10, label: null, chapters: [],
    mediaUrl: `/api/video-media/${id}`, posterUrl: null, downloadUrl: `/api/video-media/${id}?download=1`,
    createdAt: '', updatedAt: '',
  };
}

const gallery: VideoGallery = {
  series: [{
    id: 's-1', title: 'Cours EKS', description: null, episodeCount: 2, totalDurationS: 240,
    episodes: [item('e-1', 1, { chapterCount: 3 }), item('e-2', 2)], createdAt: '',
  }],
  videos: [item('v-1', null, { posterUrl: '/api/video-media/t/poster' })],
};

/** La galerie, les séries, la reprise et la suppression (F-213 / SF-213-03). */
describe('VideoGalleryComponent', () => {
  let videos: jasmine.SpyObj<VideosService>;
  let dialog: jasmine.SpyObj<MatDialog>;
  let snack: jasmine.SpyObj<MatSnackBar>;

  beforeEach(() => {
    for (const id of ['e-1', 'e-2', 'v-1']) {
      clearPosition(id, 1);
    }
    videos = jasmine.createSpyObj<VideosService>('VideosService',
      ['gallery', 'get', 'remove', 'removeSeries', 'reportDuration']);
    videos.gallery.and.returnValue(of(gallery));
    videos.get.and.callFake((id: string) => of(summary(id)));
    videos.remove.and.returnValue(of(undefined));
    videos.removeSeries.and.returnValue(of(undefined));
    videos.reportDuration.and.returnValue(of(undefined));
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    snack = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    TestBed.configureTestingModule({
      imports: [VideoGalleryComponent, NoopAnimationsModule],
      providers: [
        { provide: VideosService, useValue: videos },
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: snack },
      ],
    });
  });

  afterEach(() => {
    for (const id of ['e-1', 'e-2', 'v-1']) {
      clearPosition(id, 1);
    }
  });

  function create(inputs: Record<string, unknown>) {
    const fixture = TestBed.createComponent(VideoGalleryComponent);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    fixture.detectChanges();
    return fixture;
  }

  it('montre la série (titre · N épisodes · durée totale) et la vidéo seule', () => {
    const fixture = create({ workspaceId: 'w-1' });
    const root = fixture.nativeElement as HTMLElement;
    expect(videos.gallery).toHaveBeenCalledWith({ workspaceId: 'w-1' });
    const cards = root.querySelectorAll('.gallery__card');
    expect(cards.length).toBe(2);
    expect(cards[0].textContent).toContain('Cours EKS');
    expect(cards[0].textContent).toContain('2 épisodes · 4:00');
    expect(cards[1].textContent).toContain('Titre v-1');
    expect(cards[1].querySelector('img')!.getAttribute('src')).toBe('/api/video-media/t/poster');
  });

  it('la vue du poste interroge par poste ; une galerie vide le dit', () => {
    videos.gallery.and.returnValue(of({ series: [], videos: [] }));
    const fixture = create({ hostId: 'h-1' });
    expect(videos.gallery).toHaveBeenCalledWith({ hostId: 'h-1' });
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Aucune vidéo publiée ici');
  });

  it('une série ouverte : épisodes dans l\'ordre, lecteur, épisode suivant enchaîné à la fin', () => {
    const fixture = create({ workspaceId: 'w-1' });
    const root = fixture.nativeElement as HTMLElement;
    (root.querySelector('.gallery__card--series') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(videos.get).toHaveBeenCalledWith('e-1');
    const episodes = root.querySelectorAll('.gallery__episode');
    expect(episodes.length).toBe(2);
    expect(episodes[0].getAttribute('aria-current')).toBe('true');
    expect(root.textContent).toContain('Épisode 1 — Titre e-1');
    expect(root.querySelector('video')!.getAttribute('src')).toContain('/api/video-media/e-1');

    fixture.componentInstance.onEnded();
    fixture.detectChanges();
    expect(videos.get).toHaveBeenCalledWith('e-2');
    expect(fixture.componentInstance.autoplay()).toBeTrue();
    expect(root.textContent).toContain('Épisode 2 — Titre e-2');
    expect(root.querySelector('.gallery__next')).toBeNull();
  });

  it('reprend là où on s\'était arrêté (sur cet appareil) et ouvre l\'épisode à reprendre', () => {
    savePosition('e-2', 1, 42, 120);
    expect(readPosition('e-2', 1)).toBe(42);
    const fixture = create({ workspaceId: 'w-1' });
    const root = fixture.nativeElement as HTMLElement;
    (root.querySelector('.gallery__card--series') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(videos.get).toHaveBeenCalledWith('e-2');
    expect(fixture.componentInstance.startAt()).toBe(42);
    expect(root.textContent).toContain('reprise à 0:42');
  });

  it('?video= ouvre directement la vidéo ; la durée lue corrige celle annoncée', () => {
    const fixture = create({ workspaceId: 'w-1', initialVideoId: 'v-1' });
    expect(videos.get).toHaveBeenCalledWith('v-1');
    fixture.componentInstance.onDuration(154.2);
    expect(videos.reportDuration).toHaveBeenCalledWith('v-1', 154.2);
    videos.reportDuration.calls.reset();
    fixture.componentInstance.onDuration(154.6);
    expect(videos.reportDuration).not.toHaveBeenCalled();
  });

  it('supprimer demande confirmation ; refusée rien ne part, acceptée la vidéo est supprimée', () => {
    const fixture = create({ workspaceId: 'w-1', initialVideoId: 'v-1' });
    const root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();

    dialog.open.and.returnValue({ afterClosed: () => of(false) } as never);
    (root.querySelector('.gallery__delete') as HTMLButtonElement).click();
    expect(dialog.open).toHaveBeenCalled();
    expect(videos.remove).not.toHaveBeenCalled();

    dialog.open.and.returnValue({ afterClosed: () => of(true) } as never);
    (root.querySelector('.gallery__delete') as HTMLButtonElement).click();
    expect(videos.remove).toHaveBeenCalledWith('v-1');
    expect(snack.open).toHaveBeenCalledWith('Vidéo supprimée.', 'OK', jasmine.any(Object));
    expect(videos.gallery).toHaveBeenCalledTimes(2);
  });

  it('supprimer une série supprime ses épisodes', () => {
    const fixture = create({ workspaceId: 'w-1', initialVideoId: 'e-1' });
    fixture.detectChanges();
    dialog.open.and.returnValue({ afterClosed: () => of(true) } as never);
    ((fixture.nativeElement as HTMLElement).querySelector('.gallery__delete-series') as HTMLButtonElement).click();
    expect(videos.removeSeries).toHaveBeenCalledWith('s-1');
  });
});

describe('video-resume', () => {
  afterEach(() => clearPosition('x', 1));

  it('garde la position, l\'ignore au début et l\'efface près de la fin', () => {
    savePosition('x', 1, 3, 100);
    expect(readPosition('x', 1)).toBeNull();
    savePosition('x', 1, 50.7, 100);
    expect(readPosition('x', 1)).toBe(50);
    savePosition('x', 1, 97, 100);
    expect(readPosition('x', 1)).toBeNull();
  });

  it('tolère un stockage indisponible', () => {
    spyOn(Storage.prototype, 'getItem').and.throwError('bloqué');
    spyOn(Storage.prototype, 'setItem').and.throwError('bloqué');
    expect(() => savePosition('x', 1, 50, 100)).not.toThrow();
    expect(readPosition('x', 1)).toBeNull();
  });
});
