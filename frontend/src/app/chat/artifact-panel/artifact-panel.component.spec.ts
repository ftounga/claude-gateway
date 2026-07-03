import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { ArtifactPanelComponent } from './artifact-panel.component';
import { Artifact } from '../../shared/artifact.model';

function makeArtifact(over: Partial<Artifact>): Artifact {
  return {
    id: 'm1#0',
    messageId: 'm1',
    index: 0,
    type: 'code',
    language: 'typescript',
    title: 'Code (typescript)',
    content: 'const a = 1;',
    ...over,
  };
}

describe('ArtifactPanelComponent', () => {
  let fixture: ComponentFixture<ArtifactPanelComponent>;
  let component: ArtifactPanelComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ArtifactPanelComponent],
      providers: [provideNoopAnimations()],
    }).compileComponents();

    fixture = TestBed.createComponent(ArtifactPanelComponent);
    component = fixture.componentInstance;
  });

  function setArtifacts(artifacts: Artifact[]): void {
    fixture.componentRef.setInput('artifacts', artifacts);
    fixture.detectChanges();
  }

  it('sélectionne le premier artefact par défaut', () => {
    const a = makeArtifact({ id: 'm1#0' });
    const b = makeArtifact({ id: 'm1#1', title: 'Code (sql)', content: 'SELECT 1' });
    setArtifacts([a, b]);
    expect(component.selected()?.id).toBe('m1#0');
  });

  it('sélectionne l’artefact ciblé via focusArtifactId', () => {
    const a = makeArtifact({ id: 'm1#0' });
    const b = makeArtifact({ id: 'm2#0', messageId: 'm2' });
    fixture.componentRef.setInput('artifacts', [a, b]);
    fixture.componentRef.setInput('focusArtifactId', 'm2#0');
    fixture.detectChanges();
    expect(component.selected()?.id).toBe('m2#0');
  });

  it('propose l’aperçu pour un document et bascule en source', () => {
    const doc = makeArtifact({ id: 'm1#0', type: 'doc', language: 'markdown', content: '# Titre' });
    setArtifacts([doc]);
    expect(component.previewAvailable()).toBeTrue();
    expect(component.effectiveView()).toBe('preview');
    component.setView('source');
    expect(component.effectiveView()).toBe('source');
  });

  it('force la source pour un artefact code (aucun aperçu)', () => {
    setArtifacts([makeArtifact({ type: 'code' })]);
    expect(component.previewAvailable()).toBeFalse();
    expect(component.effectiveView()).toBe('source');
  });

  it('copie le contenu brut via navigator.clipboard', async () => {
    setArtifacts([makeArtifact({ content: 'const a = 1;' })]);
    const writeText = jasmine.createSpy('writeText').and.returnValue(Promise.resolve());
    spyOnProperty(navigator, 'clipboard', 'get').and.returnValue({ writeText } as unknown as Clipboard);

    component.copy();

    expect(writeText).toHaveBeenCalledWith('const a = 1;');
  });

  it('affiche une erreur douce si le presse-papiers est indisponible', () => {
    setArtifacts([makeArtifact({})]);
    spyOnProperty(navigator, 'clipboard', 'get').and.returnValue(undefined as unknown as Clipboard);
    const snack = spyOn((component as unknown as { snackBar: { open: () => void } }).snackBar, 'open');

    expect(() => component.copy()).not.toThrow();
    expect(snack).toHaveBeenCalled();
  });

  it('émet closePanel à la fermeture', () => {
    setArtifacts([makeArtifact({})]);
    const spy = jasmine.createSpy('close');
    component.closePanel.subscribe(spy);
    component.close();
    expect(spy).toHaveBeenCalled();
  });
});
