import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { AtelierGuideComponent } from './atelier-guide.component';
import { AtelierGuideSteps } from '../../core/services/atelier-guide.service';

describe('AtelierGuideComponent', () => {
  let fixture: ComponentFixture<AtelierGuideComponent>;
  let component: AtelierGuideComponent;

  function setup(steps: Partial<AtelierGuideSteps> = {}, projectOpen = true): void {
    TestBed.configureTestingModule({
      imports: [AtelierGuideComponent],
      providers: [provideNoopAnimations()],
    });
    fixture = TestBed.createComponent(AtelierGuideComponent);
    component = fixture.componentInstance;
    component.steps = { project: false, host: false, command: false, ...steps };
    component.projectOpen = projectOpen;
    fixture.detectChanges();
  }

  function text(): string {
    return fixture.nativeElement.textContent as string;
  }

  it('affiche les trois étapes du premier succès', () => {
    setup();

    expect(text()).toContain('Créez votre projet');
    expect(text()).toContain('Connectez votre poste');
    expect(text()).toContain('Faites exécuter une commande');
    expect(text()).toContain('0 / 3');
  });

  it('l\'étape courante est la première non franchie', () => {
    setup({ project: true });

    expect(component.currentStep()).toBe('host');
    expect(component.doneCount()).toBe(1);
    // Seule l'étape courante déploie son texte : les autres restent sur leur titre.
    expect(text()).toContain("L'écran de connexion vérifie l'accès réseau");
    expect(text()).not.toContain('Déclarez le dossier sur lequel vous travaillez');
  });

  it('ne recopie ni la fiche DSI ni la commande de lancement — il y renvoie', () => {
    setup({ project: true });

    expect(text()).not.toContain('407');
    expect(text()).not.toContain('--root');
    expect(text()).toContain('Connecter mon poste');
  });

  it('émet createProject puis connectHost selon l\'étape courante', () => {
    setup();
    const created = jasmine.createSpy('createProject');
    const connected = jasmine.createSpy('connectHost');
    component.createProject.subscribe(created);
    component.connectHost.subscribe(connected);

    const action = (): HTMLButtonElement =>
      fixture.nativeElement.querySelector('.guide-step-action') as HTMLButtonElement;
    action().click();
    expect(created).toHaveBeenCalled();

    component.steps = { project: true, host: false, command: false };
    fixture.detectChanges();
    action().click();
    expect(connected).toHaveBeenCalled();
  });

  it('ne propose pas de connecter un poste tant qu\'aucun projet n\'est ouvert', () => {
    setup({ project: true }, false);

    expect(fixture.nativeElement.querySelector('.guide-step-action')).toBeNull();
    // L'étape reste lisible : elle n'a simplement rien à ouvrir.
    expect(text()).toContain('Connectez votre poste');
  });

  it('la dernière étape n\'a aucun bouton : elle se coche sur un tour abouti', () => {
    setup({ project: true, host: true });

    expect(component.currentStep()).toBe('command');
    expect(fixture.nativeElement.querySelector('.guide-step-action')).toBeNull();
  });

  it('s\'abandonne depuis n\'importe quelle étape', () => {
    setup({ project: true });
    const dismissed = jasmine.createSpy('dismiss');
    component.dismiss.subscribe(dismissed);

    (fixture.nativeElement.querySelector('.guide-skip') as HTMLButtonElement).click();
    expect(dismissed).toHaveBeenCalled();

    (fixture.nativeElement.querySelector('.guide-close') as HTMLButtonElement).click();
    expect(dismissed).toHaveBeenCalledTimes(2);
  });

  it('conclut quand les trois étapes sont franchies', () => {
    setup({ project: true, host: true, command: true });
    const finished = jasmine.createSpy('finish');
    component.finish.subscribe(finished);

    expect(component.completed()).toBeTrue();
    expect(text()).toContain('Votre poste exécute');
    expect(fixture.nativeElement.querySelector('.guide-steps')).toBeNull();

    (fixture.nativeElement.querySelector('.guide-done-action') as HTMLButtonElement).click();
    expect(finished).toHaveBeenCalled();
  });
});
