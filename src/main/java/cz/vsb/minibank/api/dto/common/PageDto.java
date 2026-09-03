package cz.vsb.minibank.api.dto.common;

import java.util.List;

/**
 * One page of a list endpoint: the rows that were asked for, and enough for a screen to know
 * what it is looking at.
 *
 * Every list this API serves used to answer with the whole store. The alert queue read every
 * alert ever raised, fetched the transfer behind each one in its own statement, and the only row
 * limit anywhere was a hard coded ten in the history beside an alert. That is fine against a demo
 * set of ten payments and it is not a shape anything can grow into, so the three lists (the alert
 * queue, the waiting transfers, and the customer's own payment history) now take {@code page} and
 * {@code size} as query parameters and answer with this.
 *
 * WHY THE RECORD IS GENERIC. Three endpoints in two controllers page three different rows. A
 * wrapper per row is three records that drift, and the drift is not hypothetical here: the same
 * field is already called beneficiaryIban on one wire and toIban on three others. One envelope
 * over a type parameter cannot drift from itself.
 *
 * WHY A TOTAL AND NOT A hasMore FLAG. The control at the foot of the list is a Show more button
 * with a line beside it reading "Showing 25 of 137", so the count is required whatever else is
 * sent. Once it is sent, whether more exists is arithmetic the client already has:
 * {@code page * size + items.size() < total}. Sending both would be one fact stated twice, and
 * two fields that can disagree is exactly how a Show more button survives the last page.
 *
 * WHY page AND size COME BACK. They echo the parameters that were sent, by the same names, so a
 * response is readable on its own and the client derives its next request from the page it was
 * given rather than from how many rows it happens to be holding. Those two numbers part company
 * the moment a row arrives twice: offset paging shifts under a list when a payment is created
 * between two requests, the client drops the repeat, and a next page counted from the rows on
 * screen would then ask for a page it has already seen.
 *
 * WHAT IS DELIBERATELY NOT IN HERE. The fraud queue's counters. They count the whole queue before
 * any filter is applied, while {@code total} counts what the current filters matched, and the two
 * must never be derived from each other: a strip reading "New: 1" over a queue holding forty is
 * worse than no strip at all. The queue's response keeps its own counters beside a page of this
 * shape rather than folding them into it.
 */
public record PageDto<T>(
        List<T> items,
        int page,
        int size,
        int total
) {

    /**
     * What a request that names no size gets, and the largest one it may name.
     *
     * The request bounds sit beside the response shape because there is no request object to put
     * them on: page and size arrive as query parameters and are validated in each handler. Three
     * endpoints agreeing on two numbers by copying them into three controllers is how they stop
     * agreeing, and an unbounded size re-opens the very defect paging exists to close.
     */
    public static final int DEFAULT_SIZE = 25;

    /** The ceiling a size parameter is refused above, with VALIDATION_ERROR. */
    public static final int MAX_SIZE = 100;
}
