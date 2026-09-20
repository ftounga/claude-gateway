/**
 * **Aucun téléchargement réel pendant les tests.**
 *
 * <p>Le Chrome de Karma est un vrai navigateur : une ancre `<a download>` cliquée par un composant
 * écrit pour de bon dans le dossier de téléchargement du poste. Un test de l'appairage du runner
 * l'a fait pendant des semaines, à raison d'un `claude-runner.jar` d'un octet par exécution de la
 * suite — une centaine de fichiers dans `~/Downloads` avant qu'on ne comprenne d'où ils venaient.</p>
 *
 * <p>Ce garde-fou est global : il rend inerte, pour la durée des tests, tout clic sur une ancre qui
 * porte l'attribut `download`. Les tests qui veulent observer le geste espionnent l'instance
 * (`spyOn(anchor, 'click')`) et ne sont donc pas concernés ; ceux qui ne l'espionnent pas ne
 * salissent plus le poste.</p>
 */
const nativeClick = HTMLAnchorElement.prototype.click;

beforeEach(() => {
  HTMLAnchorElement.prototype.click = function (this: HTMLAnchorElement): void {
    if (this.hasAttribute('download')) {
      return;
    }
    nativeClick.call(this);
  };
});

afterEach(() => {
  HTMLAnchorElement.prototype.click = nativeClick;
});

describe('Garde-fou des téléchargements', () => {
  it('rend inerte le clic sur une ancre de téléchargement', () => {
    const url = URL.createObjectURL(new Blob(['x']));
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = 'garde-fou.bin';
    const navigated = jasmine.createSpy('navigated');
    anchor.addEventListener('click', navigated);

    anchor.click();

    expect(navigated).not.toHaveBeenCalled();
    URL.revokeObjectURL(url);
  });

  it('laisse passer un clic ordinaire', () => {
    const anchor = document.createElement('a');
    const clicked = jasmine.createSpy('clicked');
    anchor.addEventListener('click', clicked);

    anchor.click();

    expect(clicked).toHaveBeenCalled();
  });
});
