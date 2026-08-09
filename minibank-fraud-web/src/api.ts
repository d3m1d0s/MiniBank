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
export { formatMoney } from '@shared/money';

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
