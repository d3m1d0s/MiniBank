/**
 * Which fields a list of alerts, a list of payments, one payment read in full and one account
 * carry, and what each one is called.
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
 *
 * `settledAt` and `message` reached this row later than the rest, and they are on the list for the
 * reason the list exists. Both used to be readable on one route only, so a table that wanted them
 * had to open the payment; the server now sends them on every history row, and a wire field left
 * off this union is a field one platform prints and the other never learns about. Neither takes a
 * column: the settlement is read against the creation it belongs to, and the payer's own reference
 * is prose, like the reason a payment was stopped.
 */
export type HistoryField =
    | 'id'
    | 'createdAt'
    | 'settledAt'
    | 'amount'
    | 'fee'
    | 'status'
    | 'route'
    | 'message'
    | 'declineReason';

/**
 * One payment read in full, as the panel beside it lists the facts.
 *
 * `fromIban` and `toIban` and not the history row's single `route`, and the difference is the
 * shape rather than the data. A row of a table answers one question, where the money went, and
 * both account numbers are that one answer in one cell. A panel answers each question on its own
 * line and both desks had already written it that way, with a From line and a To line, so one
 * heading reading "From / To" would be a heading for two lines that already have their own.
 *
 * Three of these are why the list exists. `settledAt` says when the money actually moved, which a
 * created timestamp never did; `message` is the customer's own reference, which they had been able
 * to write and never to read back; `dispatchState` says what is left to happen to a payment that
 * has left the account. The first two have since reached the history row as well and are on its
 * field list too, in a form a row can carry. `dispatchState` is still this record's alone.
 *
 * `declineReason` is here under the name the tables use, and the customer's screen says it in the
 * customer's own words. That is the same split the status label makes by audience and not a second
 * word for a field: what is fixed here is which fact the panel is obliged to carry.
 */
export type TransferDetailField =
    | 'fromIban'
    | 'fromBalance'
    | 'toIban'
    | 'amount'
    | 'fee'
    | 'status'
    | 'createdAt'
    | 'settledAt'
    | 'dispatchState'
    | 'authMethod'
    | 'message'
    | 'declineReason';

/**
 * One account of the customer, as the payment form has to describe it.
 *
 * The three below the balance are what decides whether a payment is refused and whether it is
 * asked for a code, and they are useless one without another: a ceiling with no running total
 * against it is a number nobody can act on. They are named as one set for that reason.
 */
export type AccountField =
    | 'iban'
    | 'balance'
    | 'dailyLimit'
    | 'softDailyThreshold'
    | 'spentToday';

/** What the bank quotes for a payment before it is sent: what it moves, what it costs, and the sum. */
export type QuoteField = 'amount' | 'fee' | 'total';

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

/**
 * Reading order for a row, and the two additions sit where they are read rather than at the end.
 *
 * `settledAt` follows `createdAt` because the pair is one reading: asked for then, moved then, and
 * a reader who has to hunt for the second half is comparing two timestamps across the row.
 * `message` comes after the route and before the reason, which puts the two pieces of prose
 * together at the foot in the order they were written: the payer's words, then the bank's.
 */
export const HISTORY_FIELDS: readonly HistoryField[] = [
    'id',
    'createdAt',
    'settledAt',
    'amount',
    'fee',
    'status',
    'route',
    'message',
    'declineReason',
];

/**
 * Reading order for the panel: where the money went, what it was, what happened to it.
 *
 * The two timestamps sit together because they are read against each other, and the onward leg
 * follows the settlement it belongs to: there is nothing to say about it until the money has left.
 * The reason a payment was stopped comes last, being the one entry that is prose.
 */
export const TRANSFER_DETAIL_FIELDS: readonly TransferDetailField[] = [
    'fromIban',
    'fromBalance',
    'toIban',
    'amount',
    'fee',
    'status',
    'createdAt',
    'settledAt',
    'dispatchState',
    'authMethod',
    'message',
    'declineReason',
];

export const ACCOUNT_FIELDS: readonly AccountField[] = [
    'iban',
    'balance',
    'dailyLimit',
    'softDailyThreshold',
    'spentToday',
];

export const QUOTE_FIELDS: readonly QuoteField[] = ['amount', 'fee', 'total'];

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

/** The payer's own reference: the second thing on the row that is prose somebody typed. */
export const HISTORY_MESSAGE_FIELD: HistoryField = 'message';

/** When the money moved, which shares the cell of the moment it was asked for. */
export const HISTORY_SETTLED_FIELD: HistoryField = 'settledAt';

/**
 * The two fields that stand under the row rather than in it, in the order they are read.
 *
 * The payer's words first and the bank's second, because that is the order they happened in and
 * because the reason a payment was stopped is the last word on it. A desk draws each of the two
 * only where there is text, and a row with neither draws nothing extra at all: an empty line under
 * every payment would double the height of a table whose rows mostly have nothing to add.
 */
export const HISTORY_UNDER_ROW_FIELDS: readonly HistoryField[] = [
    HISTORY_MESSAGE_FIELD,
    HISTORY_NOTE_FIELD,
];

/**
 * The history fields that open no column of their own, and they do not for two different reasons.
 *
 * Two of them are prose. A sentence somebody wrote has no width to be given, so it stands under
 * the row it belongs to; that is the set above. The other two are numbers with widths, and they
 * are still not columns, because each is the second line of the value it belongs to. The fee read
 * as a column of its own would be a second money value about the same payment, and the customer
 * would have to add the two to learn what left the account. The settlement read as a column of its
 * own would be a second timestamp with nothing beside it saying which question it answers, and it
 * is empty on most of a fraud desk's rows, so it would be a column that is mostly dashes.
 */
export const HISTORY_NON_COLUMN_FIELDS: readonly HistoryField[] = [
    HISTORY_FEE_FIELD,
    HISTORY_SETTLED_FIELD,
    ...HISTORY_UNDER_ROW_FIELDS,
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
 *
 * `settledAt` and `message` are here for the same reason and carry the same words the panel gives
 * them, which is what the map below now takes rather than restates. Both are read on the row
 * itself, one under the timestamp it is compared against and one under the row entirely, and a
 * line of prose with no word in front of it is a sentence the reader has to guess the subject of.
 *
 * The three sets below this one carry their own maps rather than more keys here, and the reason is
 * the beneficiary. A history row says where the money left AND where it went in one field, so a
 * lone `toIban` in this map is exactly the second name that used to give the desks a column
 * nobody was writing. The panel that reads a payment in full does need that name, on its own line,
 * so it gets it in a map of its own and takes the words for everything else from this one.
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
    settledAt: 'Settled',
    fee: 'Fee',
    status: 'Status',
    route: 'From / To',
    message: 'Message for recipient',
    declineReason: 'Decline reason',
};

/**
 * What the two boxes of the amount filter are called, and what stands in them while they are empty.
 *
 * `FIELD_LABEL.amount` names the field and cannot name either box: the filter is one field asked
 * about twice, and a single `Amount` over a pair of inputs leaves a reader to work out which end
 * is which from their order. Two words, one per box, so each control has a name of its own.
 *
 * How that name is delivered is each skin's business and the two differ on purpose. The customer
 * application prints both above their boxes; the workstation has room for one row label and gives
 * these to the boxes as accessible names, so a person reading the screen sees the short form and a
 * person listening to it hears the same two words the other platform prints.
 *
 * The placeholder is the same in all four boxes and it is an example rather than an instruction.
 * `min` and `max` repeat what the labels already say and answer the question these boxes actually
 * raise, which is what a number looks like here: the queue beside them prints Czech amounts, the
 * boxes accept the string the queue prints, and a comma in the hint says so without a sentence.
 */
export const AMOUNT_FROM_LABEL = 'Amount from';
export const AMOUNT_TO_LABEL = 'Amount to';
export const AMOUNT_PLACEHOLDER = '0,00';

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
    settledAt: FIELD_LABEL.settledAt,
    amount: 'Full Amount',
    fee: FIELD_LABEL.fee,
    status: FIELD_LABEL.status,
    route: FIELD_LABEL.route,
    message: FIELD_LABEL.message,
    declineReason: FIELD_LABEL.declineReason,
};

/**
 * What each fact of one payment is called in the panel that reads it in full.
 *
 * Seven of the twelve are taken from the map above rather than restated, which is what keeps the
 * panel and the table that lists the same payment saying one word for one thing. The five that are
 * written out here are the ones a history row has no cell for.
 *
 * Two of the seven joined it when the wire grew. `settledAt` and `message` are now on a history
 * row as well, so the word for each is settled once next door and read from there; the sentences
 * below that explain the choice of word are kept here, where the panel that first needed them is.
 *
 * `From` and `To` on their own lines. The table's `From / To` is a heading for one cell holding
 * two account numbers, and it has to state their order because nothing on the row does; a panel
 * puts each on its own line with its own word, which both desks had already done.
 *
 * `dispatchState` is `Onward transfer` and not `Dispatch`: the reader is the customer whose money
 * it is, and the fact is the second half of their payment's journey rather than a queue in the
 * bank's machinery. It also has to stay clear of `Status` above it, which is the payment's own
 * lifecycle - `Status: Sent` over `Dispatch: Sent` is two words a reader would take for one fact.
 *
 * `message` keeps the caption it was written under. The customer typed it into a box labelled
 * `Message for recipient` and reads it back here, and a shorter word would make the two look like
 * two fields.
 */
export const TRANSFER_DETAIL_LABEL: Record<TransferDetailField, string> = {
    fromIban: 'From',
    fromBalance: 'From balance',
    toIban: 'To',
    amount: FIELD_LABEL.amount,
    fee: FIELD_LABEL.fee,
    status: FIELD_LABEL.status,
    createdAt: FIELD_LABEL.createdAt,
    settledAt: FIELD_LABEL.settledAt,
    dispatchState: 'Onward transfer',
    authMethod: 'Auth method',
    message: FIELD_LABEL.message,
    declineReason: FIELD_LABEL.declineReason,
};

/**
 * What the three numbers under an account balance are called.
 *
 * `softDailyThreshold` is named by what it does to the customer and not by what the rules call it.
 * `Soft daily threshold` is the tier's name inside the bank and says nothing on a form; the figure
 * means the point above which this account will be asked for a code, so that is the word.
 *
 * There is deliberately no cells type beside this one. An account is chosen from a control on a
 * form rather than laid out as a row, so there is no table for a mapped type to oblige, and the
 * one thing that must not vary between the platforms is what the numbers are called.
 */
export const ACCOUNT_LABEL: Record<AccountField, string> = {
    iban: 'Account',
    balance: 'Balance',
    dailyLimit: 'Daily limit',
    softDailyThreshold: 'Code required above',
    spentToday: 'Spent today',
};

/**
 * What the bank's quote calls its three figures, before the payment is sent.
 *
 * `Total` is the one word this map adds, and it is the whole reason a quote is worth showing: the
 * amount and the fee are two numbers the customer has to add up themselves otherwise, and the sum
 * is what will actually leave the account.
 */
export const QUOTE_LABEL: Record<QuoteField, string> = {
    amount: FIELD_LABEL.amount,
    fee: FIELD_LABEL.fee,
    total: 'Total',
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

/**
 * The same for one payment read in full, and it is total for the reason the two above it are.
 *
 * A fact the wire has nothing for is a null value and not a missing key: the panel decides whether
 * an absent settlement is drawn as a dash, said in words or left out, and it can only make that
 * decision about a cell it was obliged to build. A key quietly missing is the case that produced
 * this file, a field that exists on the wire and on one platform's screen only.
 */
export type TransferDetailCells<T> = { [K in TransferDetailField]: T };
