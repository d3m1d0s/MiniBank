/**
 * Everything this application asks of the API, in one import for its screens.
 *
 * All of it is now shared with the customer application, which carries the same fraud desk for a
 * signed-in analyst. Both used to hold their own copy of every type and every call below, and the
 * copies were maintained in parallel: the same defect in the response handler had to be found
 * twice, and retiring a field from the server meant remembering both files.
 *
 * This file stays as a barrel rather than being deleted, so a screen keeps importing from './api'
 * and one place still says what this application uses.
 */

export type { ApiError, LoginRequest, LoginResponse } from '@shared/http';
export {
    isApiError,
    setSessionId,
    setSessionExpiredHandler,
    login,
    logoutSession,
} from '@shared/http';

export type { Money } from '@shared/money';
/*
 * The reader as well as the writer now. parseAmount used to be the customer application's alone,
 * and this desk carried a second copy of it under the name readAmount because one application may
 * not import another's src/: two parsers for the one convention that decides whether 1,000 is a
 * thousand crowns or one. There is one, it is shared, and both amount boxes on this desk read
 * exactly what the payment form reads.
 */
export { formatMoney, parseAmount } from '@shared/money';

/*
 * A page of a list as the API sends one, which is how the alert queue now arrives: the rows, the
 * page and size that were asked for, and the total behind the filters.
 */
export type { Page } from '@shared/paging';

export type {
    AlertQueueItem,
    AlertCounters,
    AlertQueueResponse,
    AlertInfo,
    TransferInfo,
    HistoryItem,
    AlertDetail,
    FraudDecision,
    FraudDecisionRequest,
    AlertFilters,
} from '@shared/fraud';

export { fetchAlerts, fetchAlertDetail, postFraudDecision } from '@shared/fraud';
