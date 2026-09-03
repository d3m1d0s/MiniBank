import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import type {
    AlertDetail,
    AlertInfo,
    AlertNote,
    AlertQueueItem,
    AlertQueueResponse,
    HistoryItem,
    TransferInfo,
} from './fraud';
import type { Page } from '../logic/paging';

/**
 * The three answers in `__fixtures__/` against the types the screens were written from.
 *
 * Every other test in this directory builds its own object and hands it to the function under
 * test, which is the right shape for a test about wording or arithmetic and proves nothing at all
 * about the wire: the object was written by the same person as the expectation. That gap is not
 * hypothetical here. The waiting list DTO was declared with `beneficiaryIban`, the server had
 * never sent anything but `toIban`, the screen read the value through a cast because the field it
 * wanted was not on the type it had, and the suite stayed green the whole time.
 *
 * So this file reads bytes the server actually sent. Two failures are worth having and they fail
 * differently on purpose. A field the interface declares and the body does not have fails here, at
 * run time, on a real answer. A field the body has and the interface does not is caught by the
 * same assertion from the other side. And a field added to an interface without being added to the
 * list below stops the build rather than this test, because each list is a mapped type over the
 * interface's own keys and TypeScript will not let it be short.
 */

/**
 * Every key of T, and the compiler will not accept a list that misses one or invents one.
 *
 * The `true` is filler. What is being written down is the set of names, and writing it as a record
 * rather than as an array of strings is what buys the exhaustiveness: an array of `keyof T` can be
 * empty and still type-check.
 */
type Keys<T> = Record<keyof T, true>;

const ALERT_QUEUE_ITEM: Keys<AlertQueueItem> = {
    id: true,
    alertCode: true,
    transferCode: true,
    state: true,
    transferStatus: true,
    amount: true,
    shortReason: true,
    createdAt: true,
    riskScore: true,
    assignee: true,
};

const ALERT_INFO: Keys<AlertInfo> = {
    id: true,
    state: true,
    decision: true,
    decidedBy: true,
    resolvedAt: true,
    decisionComment: true,
    reason: true,
    riskScore: true,
    createdAt: true,
    assignee: true,
    tags: true,
};

const TRANSFER_INFO: Keys<TransferInfo> = {
    id: true,
    code: true,
    status: true,
    fromIban: true,
    fromBalance: true,
    toIban: true,
    toIbanInBank: true,
    dispatchState: true,
    amount: true,
    feeAmount: true,
    createdAt: true,
    settledAt: true,
    declinedAt: true,
    message: true,
    declineReason: true,
    authMethod: true,
};

const HISTORY_ITEM: Keys<HistoryItem> = {
    id: true,
    createdAt: true,
    settledAt: true,
    declinedAt: true,
    amount: true,
    fee: true,
    status: true,
    fromIban: true,
    toIban: true,
    toIbanInBank: true,
    message: true,
    declineReason: true,
};

const ALERT_NOTE: Keys<AlertNote> = {
    author: true,
    writtenAt: true,
    text: true,
};

const PAGE_ENVELOPE: Keys<Page<unknown>> = {
    items: true,
    page: true,
    size: true,
    total: true,
};

/**
 * A row of the waiting list, written out rather than derived.
 *
 * `WaitingTransferItem` lives in `minibank-web/src/api.ts` and not here, so this one list cannot be
 * a mapped type over it: the shared directory is what both applications import, and importing an
 * application's module into it would point the dependency the wrong way. The names are therefore
 * copied, which is the weaker guarantee, and it is still the guarantee that was missing when the
 * field this list starts with was called something else.
 */
const WAITING_TRANSFER_ITEM = ['id', 'toIban', 'amount', 'createdAt', 'authMethod', 'status'];

function captured<T>(name: string): T {
    return JSON.parse(
        readFileSync(new URL(`../__fixtures__/${name}.json`, import.meta.url), 'utf8'),
    ) as T;
}

/** Names both directions of the difference, so a failure says which field and which way. */
function expectSameKeys(value: object, declared: object | readonly string[]) {
    const want = Array.isArray(declared) ? declared : Object.keys(declared);
    expect(Object.keys(value).sort()).toEqual([...want].sort());
}

describe('the alert queue, as it answered', () => {
    const queue = captured<AlertQueueResponse>('fraud-alerts');

    it('is an envelope of a page and the counters, and nothing else', () => {
        expectSameKeys(queue, { alerts: true, counters: true });
        expectSameKeys(queue.alerts, PAGE_ENVELOPE);
        expectSameKeys(queue.counters, { newCount: true, suspiciousCount: true, okCount: true });
    });

    it('sends every row with every field the queue table draws', () => {
        expect(queue.alerts.items.length).toBeGreaterThan(0);
        for (const row of queue.alerts.items) {
            expectSameKeys(row, ALERT_QUEUE_ITEM);
        }
    });

    it('counts the whole queue in the counters and the matched rows in the total, separately', () => {
        // The two numbers are the reason the envelope exists, and the fixture is a queue where
        // they happen to agree. What is asserted is that both arrived, not that they are equal:
        // a screen that derived one from the other would pass this and still be wrong.
        const counted =
            queue.counters.newCount + queue.counters.suspiciousCount + queue.counters.okCount;
        expect(Number.isInteger(counted)).toBe(true);
        expect(Number.isInteger(queue.alerts.total)).toBe(true);
    });
});

describe('one alert in full, as it answered', () => {
    const detail = captured<AlertDetail>('fraud-alert-detail');

    it('is the alert, the payment, the earlier payments and the journal', () => {
        expectSameKeys(detail, { alert: true, transfer: true, history: true, notes: true });
    });

    it('sends the alert with every field the panel lists', () => {
        expectSameKeys(detail.alert, ALERT_INFO);
    });

    it('sends the payment with every field the panel lists', () => {
        expectSameKeys(detail.transfer, TRANSFER_INFO);
    });

    it('sends both account numbers on every history row, not only the destination', () => {
        // A customer can hold more than one account, so a row that named only where the money went
        // could not say which account it left. Asserted on real rows because the guarantee is the
        // server's: a hand written row proves only that the test author remembered both.
        expect(detail.history.length).toBeGreaterThan(0);
        for (const row of detail.history) {
            expectSameKeys(row, HISTORY_ITEM);
            expect(typeof row.fromIban).toBe('string');
            expect(typeof row.toIban).toBe('string');
        }
    });

    it('answers where the beneficiary account is held rather than the two halves of it', () => {
        for (const row of detail.history) {
            expect(typeof row.toIbanInBank).toBe('boolean');
        }
        expect(typeof detail.transfer.toIbanInBank).toBe('boolean');
    });

    it('sends a fee on every row, including the ones that never settled', () => {
        // The wire used to carry the charge alone and null everything unsettled, which on a desk
        // is most of the table. The fixture is a desk's table: held, declined and sent together.
        const unsettled = detail.history.filter((row) => row.settledAt === null);
        expect(unsettled.length).toBeGreaterThan(0);
        for (const row of detail.history) {
            expect(row.fee).not.toBeNull();
            expect(typeof row.fee.amount).toBe('string');
            expect(row.fee.currency).toBe(row.amount.currency);
        }
    });

    it('sends the journal as a list, entry by entry, and never as one blob of text', () => {
        expect(Array.isArray(detail.notes)).toBe(true);
        for (const note of detail.notes) {
            expectSameKeys(note, ALERT_NOTE);
        }
    });
});

describe('the payments waiting on the customer, as they answered', () => {
    // Typed as the envelope alone, because the row type is the application's and not this
    // directory's; the keys are checked against the copied list below.
    const waiting = captured<Page<Record<string, unknown>>>('waiting-transfers');

    it('is a page like every other page on this wire', () => {
        expectSameKeys(waiting, PAGE_ENVELOPE);
    });

    it('calls the destination toIban, which is the name the drift was found under', () => {
        expect(waiting.items.length).toBeGreaterThan(0);
        for (const row of waiting.items) {
            expectSameKeys(row, WAITING_TRANSFER_ITEM);
            expect(row).not.toHaveProperty('beneficiaryIban');
            expect(typeof row.toIban).toBe('string');
        }
    });
});
