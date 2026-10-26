/**
 * Group B of the manual (#684): what the stand-in model answers in the
 * document, wireframe and diagram editors, so their pictures show a model at
 * work - a sentence finished in grey, a summary in the chat, a shape and a
 * screen added by the editor's own chat.
 *
 * Each conversation is matched on something only it says - the name of the
 * demo item and the words the capture types - so no other group's is answered.
 */

const toolsOf = (asked) => (asked.tools ?? []).map((one) => one.function?.name).filter(Boolean);

export function answer(asked, { textOf }) {
  const messages = asked.messages ?? [];
  const all = messages.map((one) => textOf(one.content ?? '')).join('\n');
  const last = messages.at(-1);
  const lastText = textOf(last?.content ?? '');
  const answered = last?.role === 'tool';

  // A sentence finished while typing in the handbook: only the words that follow.
  if (/The text before the caret:/.test(lastText) && /Escalation handbook/.test(lastText)) {
    return { text: ' and post the first status page update within fifteen minutes.' };
  }

  // Summarise, from the document editor's bar: an answer in the chat, nothing written.
  if (/Escalation handbook/.test(all) && /Summarise the whole document\./.test(lastText)) {
    return {
      text:
        'The handbook sets four severities, from a P1 outage answered within 15 minutes to a P4 question answered within a working day. ' +
        'A P1 follows the P1 outage checklist and is never closed before the customer confirms the fix. ' +
        'Handing a ticket on means saying what the customer sees, attaching the request id and naming what was tried; the owner stays on the ticket until the engineer has answered once.',
    };
  }

  // The diagram's own chat, adding the status page the platform was missing.
  if (/Northwind support platform/.test(all) && /status page/i.test(all) && toolsOf(asked).includes('orknux_change_diagram')) {
    if (answered) {
      return { text: 'Added the status page beside the support agent: the ticket service publishes P1 incidents to it, and customers read it.' };
    }
    return {
      call: {
        name: 'orknux_change_diagram',
        arguments: {
          diagram: 'Northwind support platform',
          addNodes: [{ id: 'status', kind: 'component', label: 'Status page', x: 1180, y: 280, w: 200, h: 70, color: '#fecaca' }],
          addEdges: [
            { id: 'e7', from: 'api', to: 'status', label: 'publishes P1s', line: 'dashed' },
            { id: 'e8', from: 'customer', to: 'status', label: 'reads', line: 'dotted', end: 'open' },
          ],
        },
      },
    };
  }

  // The wireframe's own chat, drawing a ticket's own screen beside the list.
  if (/Customer portal/.test(all) && /ticket's own screen|ticket detail/i.test(all) && toolsOf(asked).includes('orknux_change_wireframe')) {
    if (answered) {
      return { text: 'Drew a third screen, Ticket TCK-1042: the conversation with the agent, a reply box and a button to close the ticket once it is solved.' };
    }
    const el = (id, type, x, y, w, h, words = '', props = {}) => ({ id, type, x, y, w, h, text: words, props });
    return {
      call: {
        name: 'orknux_change_wireframe',
        arguments: {
          wireframe: 'Customer portal',
          add: [
            el('detail', 'frame', 1180, 0, 390, 760, 'TCK-1042'),
            el('dt', 'text', 1212, 30, 326, 32, 'Invoice shows the wrong VAT', { size: 18, weight: 'bold' }),
            el('m1', 'rect', 1212, 90, 300, 80, 'You: The invoice for March charges 23% VAT; we are in Germany.'),
            el('m2', 'rect', 1238, 186, 300, 80, 'Priya (Northwind): Thanks - corrected invoice attached.'),
            el('reply', 'input', 1212, 600, 326, 60, 'Write a reply'),
            el('solved', 'button', 1212, 680, 326, 48, 'Close the ticket', { variant: 'primary' }),
          ],
        },
      },
    };
  }

  return null;
}
