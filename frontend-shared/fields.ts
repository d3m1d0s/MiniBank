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

/**
 * A row of the payment history shown beside an alert.
 *
 * `route` and not `toIban`, because the row answers where the money left and where it went, and a
 * customer holding two accounts cannot read the first half off a row that only names the second.
 * It stays ONE field with two account numbers in it: this list is a register of facts, not a
 * register of columns, and each desk decides how many lines one fact takes.
 *
 * The route carries a third thing that is deliberately not a field of its own: whether the bank
 * holds the beneficiary account. It is a property OF that account number rather than a fourth
 * fact about the payment, so it is said on the line that prints it, and a column would have to
 * repeat the account number to have anything to attach it to. The wire calls it `toIbanInBank`,
 * the words for it are in glossary.ts, and it is the one thing on this row that the mapped type
 * below cannot oblige a desk to render.
 *
 * The fee goes the other way, and the two together say where the line is. The route stayed one
 * field because two account numbers are one fact, the journey of the money. What the bank charged
 * for that journey is a second fact about the same payment, told in its own money value and true
 * or absent independently of the amount, so it is a field of its own. Being a field is not being a
 * column: it is the second line of the amount it was added to, and the list below says which of
 * the two it takes.
 */
export type HistoryField =
    | 'id'
    | 'createdAt'
    | 'amount'
    | 'fee'
    | 'status'
    | 'route'
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
    'fee',
    'status',
    'route',
    'declineReason',
];

/**
 * The one history field that is prose, which is one of the two reasons a field takes no column.
 *
 * A decline reason is a sentence somebody wrote, and a sentence has no width to be given. The
 * workstation had already worked this out and put it in a row of its own under the payment it
 * explains, rather than in a sixth column sixty pixels wide; the customer application ran the
 * whole field list out as columns and paid for the sixth in the route cell, which is the tightest
 * one it has. One form for both, named here, because a split that both desks make and neither
 * declares is exactly how the two of them came apart the first time.
 */
export const HISTORY_NOTE_FIELD: HistoryField = 'declineReason';

/** The fee, which is a fact of the payment and shares the cell of the amount it was added to. */
export const HISTORY_FEE_FIELD: HistoryField = 'fee';

/**
 * The history fields that open no column of their own, and they do not for two different reasons.
 *
 * The decline reason is prose and a sentence has no width to be given, so it stands under the row
 * it explains. The fee is a number and has a width, but it is the second line of the amount it was
 * added to: read as a column of its own it would be a second money value about the same payment,
 * and the customer would have to add the two to learn what left the account.
 */
export const HISTORY_NON_COLUMN_FIELDS: readonly HistoryField[] = [
    HISTORY_FEE_FIELD,
    HISTORY_NOTE_FIELD,
];

/**
 * The history fields that do take a column, in the same reading order.
 *
 * Derived by subtracting the named set above rather than written out again, which is a weaker
 * promise than this comment used to make and the honest one: a field added to the union is NOT a
 * column on both platforms on the next build, it is a column only if nobody put it in
 * HISTORY_NON_COLUMN_FIELDS. What survives the change is the guarantee that matters, and it never
 * lived here: the mapped type HistoryRowCells still obliges every desk to build a value for every
 * field, so a field this list leaves out is still a build error until each of the three tables
 * says where it goes. A field that wants a column of its own gets one by being absent from the set
 * above, and a field that wants none has to be named there, out loud, in one place for both
 * platforms.
 */
export const HISTORY_COLUMN_FIELDS: readonly HistoryField[] = HISTORY_FIELDS.filter(
    (f) => !HISTORY_NON_COLUMN_FIELDS.includes(f),
);

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
 *
 * `route` is labelled with both ends of the journey rather than the one the old `To` named. The
 * heading has to say the order the two account numbers are in, because nothing else on the row
 * does: the cell prints the source above the beneficiary and no per-row wording repeats it.
 *
 * `fee` has a word here although it heads no column, and that is not an oversight. Its line is read
 * out on the row itself, to a reader who hears "plus 15,00 CZK" under the amount and has nothing
 * else to tell them what the number is; a field with no column needs its word more than one with a
 * heading, not less.
 *
 * The word is `Fee` and not `Charged`, and the difference is the field's rule. On a payment that
 * settled the number is what was taken; on one that has not, it is what the tariff would take, and
 * the status in the next cell is what says which. One word that covers both is the only honest
 * heading for a field with two readings - see HistoryItemDto on the wire, which carries the rule.
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
    fee: 'Fee',
    status: 'Status',
    route: 'From / To',
    declineReason: 'Decline reason',
};

/**
 * The headings of a history table, which differ from the map above in exactly one word.
 *
 * `amount` is one field name serving two different cells. In the alert queue it is the sum a
 * payment moves and nothing else; in a history row it is that sum with what the payment cost
 * printed under it, so the heading has to cover the pair. Renaming the shared entry would carry
 * `Full Amount` onto the queue column, onto the amount filter and onto the figure at the head of
 * the alert, none of which show a fee, so the exception is stated here rather than there.
 *
 * A total Record and not a partial one, for the reason the row types next door are total: a
 * history field added tomorrow has to fail the build here rather than fall back to a heading
 * somebody has to notice is the wrong one.
 */
export const HISTORY_FIELD_LABEL: Record<HistoryField, string> = {
    id: FIELD_LABEL.id,
    createdAt: FIELD_LABEL.createdAt,
    amount: 'Full Amount',
    fee: FIELD_LABEL.fee,
    status: FIELD_LABEL.status,
    route: FIELD_LABEL.route,
    declineReason: FIELD_LABEL.declineReason,
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
