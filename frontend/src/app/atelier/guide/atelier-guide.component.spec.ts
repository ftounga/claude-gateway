import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { AtelierGuideComponent } from './atelier-guide.component';
import { AtelierGuideSteps } from '../../core/services/atelier-guide.service';

describe('AtelierGuideComponent', () => {
  let fixture: ComponentFixture<AtelierGuideComponent>;
  let component: AtelierGuideComponent;

  function setup(
    steps: Partial<AtelierGuideSteps> = {},
    projectOpen = true,
    turnFailed = false,
  ): void {
    TestBed.configureTestingModule({
      imports: [AtelierGuideComponent],
      providers: [provideNoopAnimations()],
    });
    fixture = TestBed.createComponent(AtelierGuideComponent);
    component = fixture.componentInstance;
    component.steps = { project: false, host: false, command: false, ...steps };
    component.projectOpen = projectOpen;
    component.turnFailed = turnFailed;
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

  it('la dernière étape n\'ouvre rien : elle se coche sur un tour abouti', () => {
    setup({ project: true, host: true });

    expect(component.currentStep()).toBe('command');
    // Aucune action « d'étape » : rien à ouvrir ici, seulement une demande à taper (SF-53-02).
    expect(component.canAct(component.views[2])).toBeFalse();
    const labels = Array.from(
      fixture.nativeElement.querySelectorAll('.guide-step-action'),
    ).map((b) => ((b as HTMLElement).textContent ?? '').trim());
    expect(labels.length).toBe(1);
    expect(labels[0]).toContain('Écrire dans le terminal');
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

  // --- Première commande et tour en échec (F-53 / SF-53-02) ---

  it('propose la première demande et demande qu\'elle soit écrite, sans l\'envoyer', () => {
    setup({ project: true, host: true });
    const written = jasmine.createSpy('writeCommand');
    component.writeCommand.subscribe(written);

    expect(text()).toContain(component.firstCommand);
    (fixture.nativeElement.querySelector('.guide-write') as HTMLButtonElement).click();

    expect(written).toHaveBeenCalled();
  });

  it('ne propose pas d\'écrire dans le terminal sans projet ouvert', () => {
    setup({ project: true, host: true }, false);

    expect(fixture.nativeElement.querySelector('.guide-write')).toBeNull();
    // La demande proposée reste lisible : elle dit ce qu'il y aura à taper.
    expect(text()).toContain(component.firstCommand);
  });

  it('dit qu\'un tour n\'a pas abouti et propose de vérifier le poste', () => {
    setup({ project: true, host: true }, true, true);
    const checked = jasmine.createSpy('checkHost');
    component.checkHost.subscribe(checked);

    expect(text()).toContain("Le dernier tour ne s'est pas terminé sur votre poste");
    const buttons = Array.from(
      fixture.nativeElement.querySelectorAll('.guide-step-action'),
    ) as HTMLButtonElement[];
    const check = buttons.find((b) => (b.textContent ?? '').includes('Vérifier mon poste'));
    check!.click();

    expect(checked).toHaveBeenCalled();
    // L'étape reste ouverte : un tour en échec n'est pas le premier succès.
    expect(component.currentStep()).toBe('command');
  });

  it('ne signale aucun échec par défaut', () => {
    setup({ project: true, host: true });

    expect(fixture.nativeElement.querySelector('.guide-failure')).toBeNull();
  });
});
