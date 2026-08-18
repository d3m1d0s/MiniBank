/**
 * One sentence for every error the API can answer with, for both applications.
 *
 * It was written four times before this: mapPaymentError in the customer application, two
 * describe functions on its waiting screen, and a chain of ternaries in each of the two fraud
 * desks. They did not carry the same branches, which is the defect rather than the duplication.
 * A code handled in one place and not in the other does not fail loudly; it falls through to
 * err.message, which is the server's catalogue sentence written for nobody in particular, or to
 * a fallback like "Failed to apply decision." So the desks answered VALIDATION_ERROR
 * differently, SELF_TRANSFER and DAILY_LIMIT_EXCEEDED had no client sentence at all on the one
 * screen that can provoke them, and ALERT_CHANGED, the code an analyst gets when their verdict
 * loses a race, was handled nowhere.
 *
 * Two levels. GENERAL says what a code means wherever it lands; BY_OPERATION overrides it only
 * where the endpoint changes the advice, which is the part a general sentence cannot get right:
 * CONFLICT on a cancel means the payment has already been sent, and on a decision means somebody
 * else has already decided it.
 *
 * The wording is deliberately platform free: no "above", no "on the left", no "the panel". The
 * two desks put the same objects in different places, and a sentence that names a position is
 * wrong on one of them. Both used to say "The panel above has been refreshed" and on the
 * workstation that panel is on the right.
 *
 * Pure: no DOM, no framework, no environment read, nothing thrown. Every export is a function of
 * its arguments.
 */

import { isApiError } from './http';

/**
 * Every code the ApiErrors catalogue can put on the wire, which is everything a mapped endpoint
 * can produce. A request that reaches no handler, an unmatched URL for instance, is answered by
 * Spring Boot itself with a body that has no code field at all; that case is handled below and
 * is why an unknown or absent code is a supported input rather than an error here.
 */
export type ApiErrorCode =
    | 'AUTH_REQUIRED'
    | 'AUTH_FAILED'
    | 'SESSION_LIMIT_REACHED'
    | 'TOO_MANY_ATTEMPTS'
    | 'FORBIDDEN'
    | 'NOT_FOUND'
    | 'CONFLICT'
    | 'CONCURRENT_MODIFICATION'
    | 'TRANSFER_CHANGED'
    | 'ALERT_CHANGED'
    | 'TRANSFER_UNDER_REVIEW'
    | 'VALIDATION_ERROR'
    | 'SELF_TRANSFER'
    | 'DAILY_LIMIT_EXCEEDED'
    | 'INVALID_OTP'
    | 'INVALID_IBAN'
    | 'INSUFFICIENT_FUNDS'
    | 'METHOD_NOT_ALLOWED'
    | 'UNSUPPORTED_MEDIA_TYPE'
    | 'INTERNAL_ERROR';

/**
 * One entry per endpoint the two applications call, so this list can be read against the
 * controllers. Naming the call rather than the screen is what lets both applications pass the
 * same value: the customer application and the workstation reach the alert queue through
 * different screens and the same endpoint.
 */
export type ApiOperation =
    | 'sign-in'             // POST /api/auth/login
    | 'accounts'            // GET  /api/me/accounts
    | 'payment-create'      // POST /api/payments
    | 'payments-waiting'    // GET  /api/me/waiting-transfers
    | 'payment-details'     // GET  /api/transfers/{id}
    | 'payment-authorize'   // POST /api/transfers/{id}/authorize
    | 'payment-cancel'      // POST /api/transfers/{id}/cancel
    | 'alert-queue'         // GET  /api/fraud/alerts
    | 'alert-details'       // GET  /api/fraud/alerts/{id}
    | 'alert-decision';     // POST /api/fraud/alerts/{id}/decision

/** Facts the caller has and this module cannot read, for the few sentences that use one. */
export interface ApiErrorContext {
    /** From the transfer being confirmed. Only ever read for INVALID_OTP. */
    triesLeft?: number | null;
}

/**
 * What each code means wherever it appears.
 *
 * Close to the server's own catalogue, and not by copying: those messages were written for the
 * caller and several of them are the best sentence there is. Where they differ it is because
 * this side knows it is talking to somebody looking at a screen, so it can say "reload the page"
 * where the server can only say what happened.
 *
 * "one time code" throughout, never OTP: the glossary translates the OTP authorization method to
 * "One time code", and an error box is the worst place to introduce a second word for a thing.
 */
const GENERAL: Record<ApiErrorCode, readonly string[]> = {
    // The session went away under a signed-in screen. handle() has already cleared the session
    // and called the expiry handler by the time this is read, so the screen behind this sentence
    // is on its way back to sign-in.
    AUTH_REQUIRED: ['You have been signed out.', 'Please sign in again.'],
    AUTH_FAILED: ['The username or password is not correct.'],
    SESSION_LIMIT_REACHED: [
        'Too many people are signed in right now.',
        'Please try again in a few minutes.',
    ],
    // Says outright that nothing is locked, for the reason the server's copy does: an account
    // never is, and "your account is locked" would send somebody to change a correct password.
    TOO_MANY_ATTEMPTS: [
        'Too many sign-in attempts have been made from this computer or network. No account has been locked.',
        'Please wait a few minutes and try again.',
    ],
    FORBIDDEN: ['You do not have access to this operation.'],
    NOT_FOUND: ['The requested item does not exist or is not available to you.'],
    CONFLICT: ['This is no longer possible, because the item has already changed state.'],
    // Says nothing was charged, and it is true: the debit lived only in the transaction the
    // server rolled back. A conflict on a payment otherwise reads as "it may or may not have
    // gone through", which is the worst thing to leave somebody believing about money.
    CONCURRENT_MODIFICATION: [
        'Another change was applied first, so nothing was charged.',
        'Please send it again.',
    ],
    // Deliberately does not say "nothing was charged" and does not invite a retry. Two tabs on
    // one payment: if a cancel lost to an authorization, the money has gone, and "send it again"
    // would be an invitation to pay twice.
    TRANSFER_CHANGED: [
        'This payment changed while you were working on it, so nothing in this request was applied.',
        'Open the payment again to see where it stands.',
    ],
    // Answers an analyst, and must not invite them to send the decision again: the write that
    // beat theirs may have been the opposite verdict on the same alert.
    ALERT_CHANGED: [
        'Someone else decided or updated this alert while you were working on it, so nothing in this request was applied.',
        'Open the alert again to see where it stands.',
    ],
    TRANSFER_UNDER_REVIEW: [
        'The bank is reviewing this payment.',
        'You will be able to confirm it once the review is finished, or you can cancel it.',
    ],
    VALIDATION_ERROR: ['Some of the values in this request are not valid.'],
    SELF_TRANSFER: [
        'The destination is the account the payment is sent from.',
        'Choose a different account.',
    ],
    DAILY_LIMIT_EXCEEDED: [
        "This payment would take the day's payments on the selected account above its daily limit.",
    ],
    INVALID_OTP: ['That one time code is not valid.', 'Please check it and try again.'],
    INVALID_IBAN: ['The IBAN is not valid.', 'Please check the country code and all digits.'],
    INSUFFICIENT_FUNDS: [
        'There are not enough funds on the selected account to cover the amount and the fee.',
    ],
    // Neither of these two can be provoked by either application, which sends the verb and the
    // content type each endpoint declares. They are here because the catalogue can send them and
    // a code with no sentence is exactly the hole this module exists to close.
    METHOD_NOT_ALLOWED: ['This operation is not available on this address.'],
    UNSUPPORTED_MEDIA_TYPE: ['The request body is not in a format this operation accepts.'],
    INTERNAL_ERROR: ['Something went wrong at the bank.', 'Please try again in a moment.'],
};

/**
 * Where the endpoint changes the sentence. Sparse on purpose: an entry here has to earn itself
 * by being more useful than the general one, because every entry is another place the vocabulary
 * can drift.
 *
 * A total record over ApiOperation, so adding an endpoint to the union is a compile error until
 * somebody has decided whether its errors need their own words. An endpoint with nothing to add
 * says so with an empty object.
 */
const BY_OPERATION: Record<ApiOperation, Partial<Record<ApiErrorCode, readonly string[]>>> = {
    'sign-in': {
        // The one screen where a 401 is not a lost session. handle() knows this too, which is
        // why only AUTH_REQUIRED sends anyone back to sign-in.
        VALIDATION_ERROR: ['Enter a username and a password.'],
    },

    accounts: {
        NOT_FOUND: ['Your accounts could not be loaded.', 'Reload the page and try again.'],
    },

    'payment-create': {
        VALIDATION_ERROR: [
            'Some of the payment details are not valid.',
            'Check the amount and the beneficiary IBAN.',
        ],
        NOT_FOUND: ['The selected account is not available.', 'Reload the page and try again.'],
        FORBIDDEN: ['You are not allowed to send a payment from this account.'],
        INSUFFICIENT_FUNDS: [
            'There are not enough funds on the selected account.',
            'Try a lower amount, or use a different account.',
        ],
        CONCURRENT_MODIFICATION: [
            'Another change was applied to this account first, so nothing was charged.',
            'Please send the payment again.',
        ],
    },

    'payments-waiting': {
        FORBIDDEN: ['This list is only available to a customer.'],
    },

    'payment-details': {
        NOT_FOUND: ['This payment is no longer available.'],
    },

    'payment-authorize': {
        // The attempts left are appended by describeApiErrorLines, from the transfer the screen
        // is holding: this module has no way to know them and the number is what the sentence
        // is for.
        INVALID_OTP: ['That one time code is not valid.', 'Please check it and try again.'],
        INSUFFICIENT_FUNDS: [
            'There is not enough money on the account to send this payment.',
            'Top up the account and try again, or cancel the payment.',
        ],
        // Above CONFLICT and reachable in its own right: the payment was held between the screen
        // loading and Confirm being pressed. CONFLICT's advice, to refresh, shows nothing new.
        CONCURRENT_MODIFICATION: [
            'Another change was applied to this account first, so nothing was charged.',
            'Please confirm again.',
        ],
        CONFLICT: [
            'This payment can no longer be confirmed.',
            'Refresh the list to see where it stands.',
        ],
        NOT_FOUND: ['This payment is no longer available.'],
        VALIDATION_ERROR: ['Enter the one time code before confirming.'],
    },

    'payment-cancel': {
        // The one status a cancel loses to. Named rather than left general, because "it has
        // already changed state" reads as a reason to try again and this one is final.
        CONFLICT: ['This payment can no longer be canceled. It has already been sent.'],
        NOT_FOUND: ['This payment is no longer available.'],
    },

    'alert-queue': {
        // The desks refuse a backwards amount range themselves, which is the affordance: it can
        // name which two numbers are the wrong way round and the server cannot, since no handler
        // echoes an exception message. This is what the analyst gets for the filters the desk
        // does not check, a date or a state the server does not recognise.
        VALIDATION_ERROR: [
            'One of the filters was not accepted.',
            'Check the amount range and the dates, then search again.',
        ],
        FORBIDDEN: ['The alert queue is only available to a fraud analyst.'],
    },

    'alert-details': {
        NOT_FOUND: ['This alert no longer exists.'],
    },

    'alert-decision': {
        // Both desks reload the alert before showing this, so the second sentence is a promise
        // they keep. It does not say where the alert is on screen: the two desks disagree.
        CONFLICT: [
            'This decision was not applied: the alert or its payment has already changed state.',
            'The alert has been reloaded, so what you see is current.',
        ],
        ALERT_CHANGED: [
            'Someone else decided or updated this alert first, so your decision was not applied.',
            'The alert has been reloaded, so what you see is current.',
        ],
        NOT_FOUND: ['This alert no longer exists.'],
        VALIDATION_ERROR: ['That decision was not accepted.', 'Please try again.'],
        FORBIDDEN: ['You are not allowed to decide this alert.'],
    },
};

/**
 * No response at all: the request never reached the bank, or the answer never came back.
 *
 * Every catch in both applications used to render (e as Error).message for this, which is the
 * browser's own text and differs by engine: "Failed to fetch" in Chrome, "NetworkError when
 * attempting to fetch resource." in Firefox. Neither says the one thing the reader can act on.
 */
const UNREACHABLE: readonly string[] = [
    'The bank could not be reached.',
    'Check your connection and try again.',
];

/** A server answer this table has no entry for, and which carried no sentence of its own. */
const UNRECOGNIZED: readonly string[] = [
    'The bank refused this request and did not say why.',
    'Please try again in a moment.',
];

function attemptsSentence(triesLeft: number): string {
    if (triesLeft <= 0) return 'No attempts are left.';
    if (triesLeft === 1) return 'You have 1 attempt left.';
    return `You have ${triesLeft} attempts left.`;
}

/**
 * The sentences for one failure, as separate lines: a statement of what happened, and where
 * there is one, the action to take. A screen may render them as two lines or join them.
 *
 * Takes `unknown` rather than an ApiError, because that is what a catch block holds and because
 * the failure with no response at all is the one both applications rendered worst.
 *
 * An unknown code falls back to the server's own message when there is one. That is not a
 * concession: it is how a code added to the catalogue tomorrow still reads as a sentence today,
 * and the server's messages are written for the caller.
 */
export function describeApiErrorLines(
    error: unknown,
    operation: ApiOperation,
    context: ApiErrorContext = {},
): string[] {
    if (!isApiError(error)) {
        return [...UNREACHABLE];
    }

    const raw = error.code;
    if (raw === undefined || !(raw in GENERAL)) {
        const message = error.message.trim();
        return message ? [message] : [...UNRECOGNIZED];
    }

    const code = raw as ApiErrorCode;
    const lines = [...(BY_OPERATION[operation][code] ?? GENERAL[code])];

    if (code === 'INVALID_OTP' && typeof context.triesLeft === 'number') {
        lines.push(attemptsSentence(context.triesLeft));
    }

    return lines;
}

/** The same sentences as one string, for a screen with one line to render them in. */
export function describeApiError(
    error: unknown,
    operation: ApiOperation,
    context: ApiErrorContext = {},
): string {
    return describeApiErrorLines(error, operation, context).join(' ');
}
