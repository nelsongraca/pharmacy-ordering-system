import 'htmx.org';
import 'htmx-ext-sse';

/*
 * The board streams each ticket change over SSE (`ticket` events) and mutates the DOM directly.
 * The page loads the full columns fragment once with htmx; after that we never re-fetch because
 * something changed. A dropped event leaves a screen stale until it is reloaded.
 *
 * htmx-ext-sse dispatches `htmx:sseBeforeMessage` for the `sse-swap="ticket"` listener we add on
 * the board element. We preventDefault (so htmx does not swap the raw JSON) and place the ticket.
 *
 * Ordering must match BoardResource.BOARD_ORDER: called first, then sortKey ascending, then id.
 */
function compareTickets(a, b) {
    const ac = Number(a.dataset.calling) ? 1 : 0;
    const bc = Number(b.dataset.calling) ? 1 : 0;
    if (ac !== bc) return bc - ac;
    const as = Number(a.dataset.sortKey);
    const bs = Number(b.dataset.sortKey);
    if (as !== bs) return as - bs;
    return Number(a.dataset.id) - Number(b.dataset.id);
}

function ticketLi(t) {
    const li = document.createElement('li');
    li.className = 'ticket';
    li.dataset.id = t.id;
    li.dataset.calling = t.calling;
    li.dataset.sortKey = t.sortKey;

    const number = document.createElement('span');
    number.className = 'number';
    number.textContent = t.id;
    li.append(number);

    if (t.column === 'ready' && t.calling) {
        li.classList.add('calling');
        li.append(small('Now calling'));
    } else if (t.column === 'preparing' || t.column === 'seePharmacist') {
        li.append(small(t.stage));
    }
    return li;
}

function small(text) {
    const el = document.createElement('small');
    el.textContent = text;
    return el;
}

function place(ticket) {
    const existing = document.querySelector(`#columns li[data-id="${ticket.id}"]`);
    if (ticket.column === 'none') {
        existing?.remove();
        return;
    }
    const list = document.querySelector(`#columns ul[data-column="${ticket.column}"]`);
    if (!list) return; // columns not loaded yet; a reload will pick it up

    const li = ticketLi(ticket);
    if (existing) {
        existing.remove(); // it may be moving to a different column
    }
    insertSorted(list, li);
}

function insertSorted(list, li) {
    for (const sibling of list.children) {
        if (compareTickets(li, sibling) < 0) {
            list.insertBefore(li, sibling);
            return;
        }
    }
    list.append(li);
}

document.body.addEventListener('htmx:sseBeforeMessage', (event) => {
    if (event.detail.type !== 'ticket') return;
    event.preventDefault(); // do not swap the raw JSON into the DOM
    place(JSON.parse(event.detail.data));
});
