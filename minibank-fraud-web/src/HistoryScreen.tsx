import { useEffect, useRef, useState, type ReactNode } from 'react';
import { fetchMyTransfers, formatMoney, type HistoryItem, type Page } from './api';
import ErrorBox from './ErrorBox';
import TableFrame from './TableFrame';
import { describeApiFailure, type ApiFailure } from '@shared/apiErrors';
import { formatFeeLine } from '@shared/money';
import {
    bankBoundaryMark,
    describeDeclineReason,
    transferStatusLabel,
    transferStatusTone,
} from '@shared/glossary';
import { formatDateTime, formatIban, formatTransferId } from '@shared/format';
import {
    FIELD_LABEL,
    HISTORY_COLUMN_FIELDS,
    HISTORY_DECLINED_FIELD,
    HISTORY_FIELD_LABEL,
    HISTORY_SETTLED_FIELD,
    HISTORY_UNDER_ROW_FIELDS,
    type HistoryField,
    type HistoryRowCells,
} from '@shared/fields';
import {
    SHOW_MORE,
    SHOW_MORE_BUSY,
    appendPage,
    hasMore,
    nextPage,
    showingLine,
} from '@shared/paging';

/**
 * How many payments arrive at a time.
 *
 * Five rather than the server's twenty-five, and the two work queues keep the larger number. A
 * history is a tall list read newest first, so what is wanted at its foot is a way to go on
 * reading, not the whole of it at once; a work queue is the opposite and is meant to be seen
 * entire. The demonstration set is longer than five, so this is also the one screen in this window
 * where the control the paging was built for can actually be pressed.
 */
const PAGE_SIZE = 5;

/**
 * Where the amount column is marked, and it is marked here rather than counted in the stylesheet.
 * A rule that finds its amount column by counting header cells breaks silently the day any table
 * gains a column.
 */
const CELL_CLASS: Partial<Record<HistoryField, string>> = {
    amount: 'cell--amount',
};

/**
 * One payment as its own owner reads it.
 *
 * Built through the shared row type, so a field the server sends and this screen forgets is a build
 * failure rather than something a customer discovers is missing, and so the day the union grows
 * this screen and both fraud desks fail on the same build.
 *
 * The audience is `customer` and never `analyst`, which is the whole reason the parameter exists: a
 * payment waiting for a code says "Waiting for your code" here and "Awaiting customer code" on the
 * desk, and each sentence is a lie on the other screen.
 *
 * There is no alerted account and there never will be: the parameter that marks one belongs to the
 * case pane, where the payments of one alert are read against the account it was raised on. A
 * customer reading their own payments has no alert, so .route-from--alerted never fires here.
 */
function historyCells(h: HistoryItem): HistoryRowCells<ReactNode> {
    const boundary = bankBoundaryMark(h.toIbanInBank);
    const feeLine = formatFeeLine(h.fee);
    // The empty string and not the table's dash: the second line is drawn only where there is one,
    // so an unsettled payment loses a line rather than gaining a gap.
    const settledLine = h.settledAt ? formatDateTime(h.settledAt) : '';
    const declinedLine = h.declinedAt ? formatDateTime(h.declinedAt) : '';

    // The end this payment came to, whichever it was. A payment settles or it is refused, never
    // both, so the two instants share one line and the word in front of them says which is shown.
    // Written as a preference rather than as an either-or so that a row somehow carrying both,
    // which the domain and the schema each forbid, shows the settlement instead of choosing by
    // accident.
    const endedLine = settledLine || declinedLine;
    const endedField = settledLine ? HISTORY_SETTLED_FIELD : HISTORY_DECLINED_FIELD;

    return {
        id: formatTransferId(h.id),
        // When the payment was asked for, and under it when the money actually moved. Two questions
        // a customer reads against each other, so they share a cell rather than standing at two
        // ends of the row. Which line is which is said once, in the heading, and the muted weight
        // of the second is what carries it down the rows.
        createdAt: (
            <>
                <span className="created-value">{formatDateTime(h.createdAt)}</span>
                {endedLine && (
                    // The class names the second line of this cell rather than the fact in it,
                    // which is why a refusal is drawn under it on the same terms.
                    <span className="created-settled">
                        <span className="visually-hidden">{FIELD_LABEL[endedField]}: </span>
                        {endedLine}
                    </span>
                )}
            </>
        ),
        settledAt: settledLine,
        declinedAt: declinedLine,
        // The amount the customer sent, and under it what the bank added to it. Two lines of one
        // sum, stacked like the two account numbers next door, so the column can be read down. On a
        // payment that never went out the second line is the price rather than a charge, and the
        // status beside it is what says so.
        amount: (
            <>
                <span className="amount-value">{formatMoney(h.amount)}</span>
                {feeLine && (
                    <span className="amount-fee">
                        <span className="visually-hidden">{FIELD_LABEL.fee}: </span>
                        {feeLine}
                    </span>
                )}
            </>
        ),
        fee: feeLine,
        // The word, the colour and, for a refused payment, the sentence under the row. Set in one
        // grey, nine settled rows and the tenth that was stopped read alike, which is what the tone
        // is for.
        status: (
            <span className={`tone-${transferStatusTone(h.status)}`}>
                {transferStatusLabel(h.status, 'customer')}
            </span>
        ),
        // Where the money left, over where it went, and on the rare row where the money did not
        // leave the bank at all, that. The word sits on the exception and nowhere else, so the
        // column keeps its shape.
        route: (
            <>
                <span className="route-from">{formatIban(h.fromIban)}</span>
                <span className="route-to">
                    {formatIban(h.toIban)}
                    {boundary && <> <span className="route-boundary">{boundary}</span></>}
                </span>
            </>
        ),
        // What the customer typed into the message box, read back in their own words. Prose, so it
        // takes no column and stands under the row; absent where the box was left alone.
        message: h.message ?? '',
        // The same function, and therefore the same sentence, the analyst reads for this row.
        declineReason: describeDeclineReason(h.declineReason),
    };
}

/**
 * A LEDGER, AND NOTHING ELSE.
 *
 * One table, no selection, no detail pane, no control on a row. Rows do not light up under the
 * pointer, because there is nothing to do with a payment here except read it, and a row that
 * lights up under a pointer that cannot press it reads as a press that was ignored.
 *
 * Weight goes to the amount column and to the status column; everything else recedes, and the
 * recession is the design. Three of the five cells are two-line stacks and two fields stand under
 * the row as prose spanning the table, drawn only where there is text.
 */
export default function HistoryScreen() {
    const [items, setItems] = useState<HistoryItem[]>([]);

    /**
     * The page envelope as it last arrived, rows and all. Held whole rather than unpacked into two
     * numbers, because the next request is asked for by the page that arrived and the total beside
     * the list is the one that came with it. Null until the first answer.
     */
    const [last, setLast] = useState<Page<HistoryItem> | null>(null);
    const total = last?.total ?? 0;

    /**
     * Whether the first page is still in flight, and it starts true, so an in-flight list is not
     * drawn as an empty one. The empty sentence below is reachable only once a request has
     * completed.
     */
    const [loading, setLoading] = useState(true);
    const [loadingMore, setLoadingMore] = useState(false);
    const [error, setError] = useState<ApiFailure | null>(null);

    /* Whether this screen is still on. Held in a ref rather than in a local of the effect, because
       the loader below is called from the effect and from the retry beside its own error box. */
    const alive = useRef(true);

    useEffect(() => {
        alive.current = true;
        void loadFirst();
        return () => {
            alive.current = false;
        };
    }, []);

    /**
     * The first page, and the way back from a first page that failed. The retry is the same call
     * rather than a reload of the browser tab: the screen is reached from the rail, and pressing
     * the entry somebody is already standing on mounts nothing.
     */
    async function loadFirst() {
        try {
            setLoading(true);
            setError(null);
            const answer = await fetchMyTransfers(0, PAGE_SIZE);
            if (!alive.current) return;
            setItems(answer.items);
            setLast(answer);
        } catch (e) {
            if (!alive.current) return;
            setError(describeApiFailure(e, 'payments-history', { retry: () => void loadFirst() }));
        } finally {
            if (alive.current) setLoading(false);
        }
    }

    /**
     * The next page, appended under the rows already on screen.
     *
     * A failure here is not the failure of a first page and must not be answered like one: the rows
     * already fetched stay, the table stays, and the sentence is drawn at the foot beside the
     * button, which returns to Show more so it can be pressed again.
     *
     * The page asked for is counted from the page that arrived rather than from the row count,
     * because appendPage drops a row that has arrived twice and from then on the two disagree.
     */
    async function loadMore() {
        if (!last) return;

        try {
            setLoadingMore(true);
            setError(null);
            const answer = await fetchMyTransfers(nextPage(last), PAGE_SIZE);
            setItems((held) => appendPage(held, answer.items));
            setLast(answer);
        } catch (e) {
            // The retry asks for the page that failed, not for the first one.
            setError(describeApiFailure(e, 'payments-history', { retry: () => void loadMore() }));
        } finally {
            setLoadingMore(false);
        }
    }

    return (
        <main className="form-panel">
            <section className="section">
                {/* The screen names itself where the work begins, at the rung this window sets every
                    block name at. The rail above says History & Statements and this screen offers
                    no statement, so the heading promises only what is here. */}
                <h2 className="section-title">Payment history</h2>
                {/*
                  The caption earns itself: that the list is complete and that it is ordered are the
                  two things a customer looking for one payment needs to know before they start.
                */}
                <p className="section-caption">
                    Every payment from all of your accounts, newest first.
                </p>

                {/*
                  Three states, and the third may only be reached once a request has finished:
                  loading, then a first page that failed, then an empty list. A later page that
                  fails is a fourth case and is drawn at the foot, under the rows that are there.
                */}
                {loading ? (
                    <p className="helper-text">Loading payments…</p>
                ) : error && items.length === 0 ? (
                    <ErrorBox failure={error} />
                ) : items.length === 0 ? (
                    <p className="helper-text">No payments yet.</p>
                ) : (
                    <>
                        {/* table--static: these rows go nowhere. The window's hover rule paints
                            every table it has, so without this a dead row lights up under the
                            pointer and reads as a control. */}
                        <TableFrame label="All payments" className="gap-above-sm">
                            <table className="table table--static">
                                <thead>
                                    <tr>
                                        {/* Each heading claims its column. Read out cell by cell, a
                                            table whose headings claim nothing is a run of values
                                            with no names on them. */}
                                        {HISTORY_COLUMN_FIELDS.map((f) => (
                                            <th key={f} scope="col" className={CELL_CLASS[f]}>
                                                {HISTORY_FIELD_LABEL[f]}
                                            </th>
                                        ))}
                                    </tr>
                                </thead>
                                {items.map((h) => {
                                    const cells = historyCells(h);
                                    return (
                                        /* One row group per payment, so the two pieces of prose it
                                           carries stay part of the row they belong to rather than
                                           becoming two columns of sentences. The shared list is
                                           what decides which fields those are and in what order:
                                           the payer's words, then the bank's. */
                                        <tbody key={h.id}>
                                            <tr>
                                                {HISTORY_COLUMN_FIELDS.map((f) => (
                                                    <td key={f} className={CELL_CLASS[f]}>
                                                        {cells[f]}
                                                    </td>
                                                ))}
                                            </tr>
                                            {HISTORY_UNDER_ROW_FIELDS.map((f) =>
                                                cells[f] ? (
                                                    <tr className="history-note" key={f}>
                                                        <td
                                                            colSpan={HISTORY_COLUMN_FIELDS.length}
                                                        >
                                                            {FIELD_LABEL[f]}: {cells[f]}
                                                        </td>
                                                    </tr>
                                                ) : null,
                                            )}
                                        </tbody>
                                    );
                                })}
                            </table>
                        </TableFrame>

                        {/* The page that did not arrive, under the rows that did, beside the button
                            that asked for it. */}
                        {error && <ErrorBox failure={error} />}

                        {/*
                          Paging decides nothing, so the button carries no shape of its own: the
                          same rank as Refresh on the waiting screen. It is removed rather than
                          disabled once everything is on screen, because a dead control still
                          invites the press that proves it.

                          Not .list-more, which is this window's tray control and is drawn as the
                          last row of a queue. Here the pair stands on a plain line at the foot of a
                          table, beside the count it lengthens.
                        */}
                        <div className="inline gap-above-sm">
                            {hasMore(items.length, total) && (
                                <button
                                    type="button"
                                    className="btn btn--quiet"
                                    onClick={() => void loadMore()}
                                    disabled={loadingMore}
                                    aria-busy={loadingMore || undefined}
                                >
                                    {loadingMore ? SHOW_MORE_BUSY : SHOW_MORE}
                                </button>
                            )}
                            <span className="list-count">{showingLine(items.length, total)}</span>
                        </div>
                    </>
                )}
            </section>
        </main>
    );
}
