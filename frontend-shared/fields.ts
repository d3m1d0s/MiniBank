/**
 * Which fields a list of alerts and a list of payments carry, and what each one is called.
 *
 * The two fraud desks had drifted apart without anybody noticing. The analyst's workstation
 * showed four of a payment's six history fields and nine of a queue entry's nine minus three,
 * and the missing one that mattered was the decline reason: an analyst on the desktop could not
 * find out why an earlier payment from the same account had been refused, while the same analyst
 * on the web could. One role is supposed to get the same functionality on both platforms.
 *
 * WHY A TYPE AND NOT A COLUMN DESCRIPTOR. An array of `{key, header, width, align}` consumed by
 * both desks would force one rendering on two idioms that are each right: the web reads a queue
 * entry across nine columns, the workstation reads it down a card in four lines. A descriptor
 * rich enough to express both has become a layout engine, and this project has decided it has no
 * UI library. It also enforces nothing - a desk can ignore an entry in an array forever and no
 * build ever complains, which is exactly how the divergence happened.
 *
 * So the field set is a union and the row is a total mapped type over it. Each desk builds one
 * object per row and then lays the values out however its idiom wants. Leaving a field out is
 * `TS2739: Property 'riskScore' is missing`, and adding a field to the union breaks both desks on
 * the same build. That is the property block 8 needs before the customer screens exist on the
 * second platform: they declare their field set the same way and cannot open with fewer fields
 * than the web's.
 *
 * HONEST LIMITATION. A mapped type catches a cell that was never built. It does not catch a cell
 * that was built and then never rendered. A desk that spreads the fields in list order is
 * exhaustive by construction; a desk that picks them out by name is checked only where the row is
 * built. Both are better than an array nobody has to read.
 *
 * FORMATTING IS NOT DECIDED HERE. Amounts go through money.ts, timestamps, identifiers and
 * account numbers through format.ts, the words for the server's enums through glossary.ts. This
 * module names fields and labels, or it becomes a second place where a format is settled.
 */

/** A row of the fraud alert queue: the alert, the payment it was raised on, and the triage. */
export type AlertQueueField =
    | 'alertCode'
    | 'transferCode'
    | 'state'
    | 'transferStatus'
    | 'amount'
    | 'shortReason'
    | 'riskScore'
    | 'assignee'
    | 'createdAt';

/** A row of the payment history shown beside an alert. */
export type HistoryField =
    | 'id'
    | 'createdAt'
    | 'amount'
    | 'status'
    | 'toIban'
    | 'declineReason';

/**
 * Reading order, for a skin that lays its fields out in one line.
 *
 * Identity first, then what the row is about, then the triage: an analyst scanning the queue
 * chooses a case by its amount and its risk, and reads the rest only once one is chosen.
 */
export const ALERT_QUEUE_FIELDS: readonly AlertQueueField[] = [
    'alertCode',
    'transferCode',
    'state',
    'transferStatus',
    'amount',
    'shortReason',
    'riskScore',
    'assignee',
    'createdAt',
];

export const HISTORY_FIELDS: readonly HistoryField[] = [
    'id',
    'createdAt',
    'amount',
    'status',
    'toIban',
    'declineReason',
];

/**
 * One word per field, on both platforms.
 *
 * They already disagreed: the web queue headed a column `Risk` while its own detail panel four
 * hundred pixels below said `Risk score` for the same number, and the workstation said a third
 * thing. One field, one word, decided once.
 *
 * Two pairs look like duplicates and are not. A queue row is an alert, so its own lifecycle is
 * `Alert state` and the payment's is `Payment status`; a history row is a payment, so there its
 * status is simply `Status`. And `createdAt` is when the row's own subject came into being, which
 * is why one word serves the alert that was raised and the payment that was created.
 */
export const FIELD_LABEL: Record<AlertQueueField | HistoryField, string> = {
    alertCode: 'Alert',
    transferCode: 'Payment',
    state: 'Alert state',
    transferStatus: 'Payment status',
    amount: 'Amount',
    shortReason: 'Reason',
    riskScore: 'Risk score',
    assignee: 'Assignee',
    createdAt: 'Created',

    id: 'Payment',
    status: 'Status',
    toIban: 'To',
    declineReason: 'Decline reason',
};

/**
 * One rendering of one queue row: a value per field, and neither desk may leave a key out.
 *
 * Generic in what a value is, because the two skins render different things. A cell may be a
 * string on one platform and a marked-up node on the other, and this module has no opinion about
 * which: it says only that every field is present.
 */
export type QueueRowCells<T> = { [K in AlertQueueField]: T };

/** The same for one row of payment history. */
export type HistoryRowCells<T> = { [K in HistoryField]: T };
