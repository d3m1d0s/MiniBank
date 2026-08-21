// src/HistoryPage.tsx

import { useEffect, useRef, useState, type ReactNode } from 'react';
import './App.css';
import { fetchMyTransfers, type HistoryItem, type Page } from './api';
import { formatMoney } from './money';
import { formatFeeLine } from '@shared/money';
import ErrorBox from './ErrorBox';
import { describeApiFailure, type ApiFailure } from '@shared/apiErrors';
import {
    bankBoundaryMark,
    describeDeclineReason,
    transferStatusLabel,
    transferStatusTone,
} from '@shared/glossary';
import {
    formatDateTime,
    formatIban,
    formatTransferId,
} from '@shared/format';
import {
    FIELD_LABEL,
    HISTORY_FIELD_LABEL,
    HISTORY_COLUMN_FIELDS,
    HISTORY_NOTE_FIELD,
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
import Nav from './Nav';
import type { NavRole, NavView } from '@shared/navigation';

/**
 * How many payments arrive at a time.
 *
 * Five rather than the server's default of twenty-five, and the two work queues keep the larger
 * number. A history is a tall list read newest first, so what is wanted at its foot is a way to
 * go on reading, not the whole of it at once; a work queue is the opposite and is meant to be
 * seen entire. The demonstration set is ten payments, so this is also the one screen in the
 * application where the control the paging was built for can actually be pressed.
 */
const PAGE_SIZE = 5;

/**
 * Where the amount column is marked, and it is marked here rather than counted in the stylesheet.
 *
 * The customer application's CSS finds its amount column by counting header cells, which matches
 * a table of nine columns and one of six and therefore misses this one, and which breaks silently
 * the day any table gains a column. The workstation's history already marks the cell where it is
 * built; this does the same.
 */
const CELL_CLASS: Partial<Record<HistoryField, string>> = {
    amount: 'cell--amount',
};

interface Props {
    role: NavRole;
    /* The mark and the name of the application, built by App and rendered here as it arrives. */
    brand?: ReactNode;
    /* Who is signed in and the way out, built by App and rendered here as it arrives. */
    identity?: ReactNode;
    onNavigate: (view: NavView) => void;
}

/**
 * One payment as its own owner reads it.
 *
 * Built through the shared row type, so a field the server sends and this screen forgets is a
 * build failure rather than something a customer discovers is missing, and so the day the union
 * grows this screen and both fraud desks fail on the same build.
 *
 * The audience is `customer` and never `analyst`, which is the whole reason the parameter exists:
 * a payment waiting for a code says "Waiting for your code" here and "Awaiting customer code" on
 * the desk, and each sentence is a lie on the other screen.
 *
 * `alertedIban` is null here and always will be. The parameter is on all three builders of this
 * row so the cell below can be one piece of markup on both platforms; the account it would mark
 * is the one an alert was raised on, and a customer reading their own payments has no alert.
 */
function historyCells(h: HistoryItem, alertedIban: string | null): HistoryRowCells<ReactNode> {
    const alerted = alertedIban !== null && h.fromIban === alertedIban;
    const boundary = bankBoundaryMark(h.toIbanInBank);
    const feeLine = formatFeeLine(h.fee);

    return {
        id: formatTransferId(h.id),
        createdAt: formatDateTime(h.createdAt),
        // The amount the customer sent, and under it what the bank added to it. Two lines of one
        // sum, stacked like the two account numbers next door, so the column can be read down.
        // Every row has the second line: on a payment that never went out it is the price rather
        // than a charge, and the status beside it is what says so.
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
        // The word, the colour and, for a refused payment, the sentence under it. Set in the
        // same grey as nine settled rows the word alone marks out nothing, which is what the
        // tone is for.
        status: (
            <span className={`tone-${transferStatusTone(h.status)}`}>
                {transferStatusLabel(h.status, 'customer')}
            </span>
        ),
        // Where the money left, over where it went, and on the rare row where the money did not
        // leave the bank at all, that. The word sits on the exception and nowhere else, so the
        // column keeps its shape; see the glossary for why this is the way round it is.
        route: (
            <>
                <span className={alerted ? 'route-from route-from--alerted' : 'route-from'}>
                    {alerted && <span className="visually-hidden">Alerted account: </span>}
                    {formatIban(h.fromIban)}
                </span>
                <span className="route-to">
                    {formatIban(h.toIban)}
                    {boundary && <> <span className="route-boundary">{boundary}</span></>}
                </span>
            </>
        ),
        // The same function, and therefore the same sentence, the analyst reads for this row.
        // A payment that was not declined has no note row under it at all, which is why nothing
        // stands in for the absent value any more.
        declineReason: describeDeclineReason(h.declineReason),
    };
}

/**
 * Every payment the customer has made, from every account they hold.
 *
 * The list a customer had no way to reach. A payment left their world the moment it was sent or
 * declined: the waiting list holds the two open statuses only, and the details endpoint answers
 * for any of their transfers but the id could be got from nowhere but a result panel that every
 * handler clears. The analyst could read the last ten payments of this same customer.
 */
export default function HistoryPage({ role, brand, identity, onNavigate }: Props) {
    const [items, setItems] = useState<HistoryItem[]>([]);

    /**
     * The page envelope as it last arrived, rows and all.
     *
     * Held whole rather than unpacked into two numbers, because the next request is asked for by
     * the page that arrived and the total beside the list is the one that came with it. Null
     * until the first answer, which is what tells the foot of the list there is nothing to say
     * yet.
     */
    const [last, setLast] = useState<Page<HistoryItem> | null>(null);
    const total = last?.total ?? 0;

    /**
     * Whether the first page is still in flight, and it starts true.
     *
     * The waiting screen has no such state and draws an in-flight list as an empty one: it says
     * "No waiting transfers." for as long as the request takes, over an API answering with two.
     * The empty sentence below is reachable only once a request has completed.
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
     * The first page, and the way back from a first page that failed.
     *
     * The retry is the same call rather than a reload of the browser tab, which is what a customer
     * was left with: the screen is mounted by a nav click, and clicking the entry they are already
     * standing on does not mount it again.
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
            setError(
                describeApiFailure(e, 'payments-history', { retry: () => void loadFirst() }),
            );
        } finally {
            if (alive.current) setLoading(false);
        }
    }

    /**
     * The next page, appended under the rows already on screen.
     *
     * A failure here is not the failure of a first page and must not be answered like one: the
     * rows already fetched stay, the table stays, and the sentence is drawn at the foot beside
     * the button, which returns to Show more so it can be pressed again. Losing ten rows because
     * the eleventh page timed out would be worse than the failure.
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
        <div className="app-shell">
            <div className="card">
                <header className="card-header">
                    {brand}
                    {identity}
                </header>

                <div className="card-body layout">
                    <Nav role={role} current="history" onNavigate={onNavigate} />

                    <main className="form-panel">
                        {/*
                          The screen is called what it shows. The navigation entry above it says
                          History & Statements and this screen offers no statement, so the heading
                          promises only what is here; the entry's wording is the owner's.
                        */}
                        <h2>Payment history</h2>

                        <section className="section">
                            <h2 className="section-title">All payments</h2>
                            {/*
                              The caption earns itself: that the list is complete and that it is
                              ordered are the two things a customer looking for one payment needs
                              to know before they start reading.
                            */}
                            <p className="section-caption">
                                Every payment from all of your accounts, newest first.
                            </p>

                            {/*
                              Three states, and the third may only be reached once a request has
                              finished: loading, then a first page that failed, then an empty
                              list. A later page that fails is a fourth case and is drawn at the
                              foot, under the rows that are already there.
                            */}
                            {loading ? (
                                <p className="helper-text">Loading payments…</p>
                            ) : error && items.length === 0 ? (
                                <ErrorBox failure={error} />
                            ) : items.length === 0 ? (
                                <p className="helper-text">No payments yet.</p>
                            ) : (
                                <>
                                    {/*
                                      table--static: these rows go nowhere. The application's
                                      hover rule paints every table it has, so without this a
                                      dead row lights up under the pointer and reads as a control.
                                    */}
                                    <div className="table-wrapper gap-above-sm">
                                        <table className="table table--static">
                                            <thead>
                                            <tr>
                                                {HISTORY_COLUMN_FIELDS.map((f) => (
                                                    <th key={f} className={CELL_CLASS[f]}>
                                                        {HISTORY_FIELD_LABEL[f]}
                                                    </th>
                                                ))}
                                            </tr>
                                            </thead>
                                            {items.map((h) => {
                                                const cells = historyCells(h, null);
                                                return (
                                                    /* One row group per payment, so the reason a
                                                       payment was refused stays part of the row it
                                                       explains rather than becoming a column of
                                                       prose. */
                                                    <tbody key={h.id}>
                                                    <tr>
                                                        {HISTORY_COLUMN_FIELDS.map((f) => (
                                                            <td key={f} className={CELL_CLASS[f]}>
                                                                {cells[f]}
                                                            </td>
                                                        ))}
                                                    </tr>
                                                    {h.declineReason && (
                                                        <tr className="history-note">
                                                            <td
                                                                colSpan={
                                                                    HISTORY_COLUMN_FIELDS.length
                                                                }
                                                            >
                                                                {FIELD_LABEL[HISTORY_NOTE_FIELD]}:{' '}
                                                                {cells[HISTORY_NOTE_FIELD]}
                                                            </td>
                                                        </tr>
                                                    )}
                                                    </tbody>
                                                );
                                            })}
                                        </table>
                                    </div>

                                    {error && <ErrorBox failure={error} />}

                                    {/*
                                      Paging decides nothing, so the button carries no shape of
                                      its own: the same rank as Refresh on the waiting screen,
                                      which was demoted for exactly this reason. It is removed
                                      rather than disabled once everything is on screen, because
                                      a dead control still invites the press that proves it.
                                    */}
                                    <div className="section-block inline gap-above-sm">
                                        {hasMore(items.length, total) && (
                                            <button
                                                type="button"
                                                className="btn-quiet"
                                                onClick={() => void loadMore()}
                                                disabled={loadingMore}
                                                aria-busy={loadingMore || undefined}
                                            >
                                                {loadingMore ? SHOW_MORE_BUSY : SHOW_MORE}
                                            </button>
                                        )}
                                        <span className="list-count">
                                            {showingLine(items.length, total)}
                                        </span>
                                    </div>
                                </>
                            )}
                        </section>
                    </main>
                </div>
            </div>
        </div>
    );
}
