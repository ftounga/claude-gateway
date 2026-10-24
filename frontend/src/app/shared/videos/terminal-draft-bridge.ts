import { Injectable } from '@angular/core';

/**
 * **Le pont vers la saisie du terminal** (F-214 / SF-214-02). La galerie vit dans un autre onglet que le
 * terminal (le tour vit dans le flux du terminal, le quitter le tuerait) : « Envoyer à l'agent » y propose le
 * message à l'onglet du terminal **du même sujet**, qui le place dans sa saisie — **sans l'envoyer** — et
 * accuse réception. Sans accusé (aucun terminal ouvert sur ce sujet), l'appelant ouvre lui-même le terminal.
 *
 * <p>Rien ne sort du navigateur : un `BroadcastChannel` ne relie que les onglets de la même origine.</p>
 */
export const TERMINAL_DRAFT_CHANNEL = 'cg-terminal-draft';

/** Délai d'attente de l'accusé (ms). */
export const TERMINAL_DRAFT_ACK_MS = 800;

interface DraftOffer {
  kind: 'offer';
  id: string;
  workspaceId: string;
  text: string;
}

interface DraftAck {
  kind: 'ack';
  id: string;
}

type DraftMessage = DraftOffer | DraftAck;

@Injectable({ providedIn: 'root' })
export class TerminalDraftBridge {
  /** Vrai si le navigateur sait relier les onglets. */
  supported(): boolean {
    return typeof BroadcastChannel !== 'undefined';
  }

  /**
   * Propose le message à un terminal ouvert sur ce sujet. Rend `true` si un terminal l'a pris dans le délai.
   */
  offer(workspaceId: string, text: string, timeoutMs = TERMINAL_DRAFT_ACK_MS): Promise<boolean> {
    if (!this.supported()) {
      return Promise.resolve(false);
    }
    const channel = new BroadcastChannel(TERMINAL_DRAFT_CHANNEL);
    const id = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    return new Promise<boolean>((resolve) => {
      let done = false;
      const finish = (taken: boolean) => {
        if (done) {
          return;
        }
        done = true;
        clearTimeout(timer);
        channel.close();
        resolve(taken);
      };
      const timer = setTimeout(() => finish(false), timeoutMs);
      channel.onmessage = (event: MessageEvent<DraftMessage>) => {
        if (event.data?.kind === 'ack' && event.data.id === id) {
          finish(true);
        }
      };
      channel.postMessage({ kind: 'offer', id, workspaceId, text } satisfies DraftOffer);
    });
  }

  /**
   * Côté terminal : écoute les propositions. `accept` rend `true` s'il a placé le message (le sujet est le
   * sien) ; l'accusé part alors. Rend la fonction qui arrête l'écoute.
   */
  listen(accept: (workspaceId: string, text: string) => boolean): () => void {
    if (!this.supported()) {
      return () => undefined;
    }
    const channel = new BroadcastChannel(TERMINAL_DRAFT_CHANNEL);
    channel.onmessage = (event: MessageEvent<DraftMessage>) => {
      const data = event.data;
      if (data?.kind !== 'offer' || typeof data.text !== 'string' || typeof data.workspaceId !== 'string') {
        return;
      }
      if (accept(data.workspaceId, data.text)) {
        channel.postMessage({ kind: 'ack', id: data.id } satisfies DraftAck);
      }
    };
    return () => channel.close();
  }
}
