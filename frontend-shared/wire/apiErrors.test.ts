import { describe, it, expect } from 'vitest';
import {
    describeApiError,
    describeApiErrorLines,
    type ApiErrorCode,
    type ApiOperation,
} from './apiErrors';
import type { ApiError } from './http';

/**
 * The table exists because the four copies it replaces did not carry the same branches, so most
 * of what is worth asserting is coverage rather than wording: every code the catalogue can send
 * has a sentence, on every endpoint either application calls.
 */

/** Exactly the codes in ApiErrors.java, listed again here so a code added there fails a test. */
const CODES: ApiErrorCode[] = [
    'AUTH_REQUIRED',
    'AUTH_FAILED',
    'SESSION_LIMIT_REACHED',
    'TOO_MANY_ATTEMPTS',
    'FORBIDDEN',
    'NOT_FOUND',
    'CONFLICT',
    'CONCURRENT_MODIFICATION',
    'TRANSFER_CHANGED',
    'ALERT_CHANGED',
    'TRANSFER_UNDER_REVIEW',
    'VALIDATION_ERROR',
    'SELF_TRANSFER',
    'DAILY_LIMIT_EXCEEDED',
    'INVALID_OTP',
    'INVALID_IBAN',
    'INSUFFICIENT_FUNDS',
    'METHOD_NOT_ALLOWED',
    'UNSUPPORTED_MEDIA_TYPE',
    'INTERNAL_ERROR',
];

const OPERATIONS: ApiOperation[] = [
    'sign-in',
    'accounts',
    'payment-create',
    'payments-waiting',
    'payment-details',
    'payment-authorize',
    'payment-cancel',
    'alert-queue',
    'alert-details',
    'alert-decision',
];

/** What handle() builds: an Error carrying the code and the status off the response. */
function failure(code: string | undefined, status = 400, message = ''): ApiError {
    const e = new Error(message) as ApiError;
    e.code = code;
    e.status = status;
    return e;
}

/** A sentence, not a token: it ends in a full stop and carries no SHOUTING_ENUM. */
function readable(text: string): boolean {
    return text.length > 0 && text.trim().endsWith('.') && !/[A-Z]{2,}_[A-Z]/.test(text);
}

describe('every code has a sentence on every endpoint', () => {
    it.each(OPERATIONS)('answers all twenty codes on %s', (operation) => {
        for (const code of CODES) {
            const text = describeApiError(failure(code), operation);
            expect(readable(text), `${operation} / ${code}: ${text}`).toBe(true);
        }
    });
});

describe('the branches that were missing on one side or the other', () => {
    it('has a sentence of its own for a payment sent to its own account', () => {
        // Reachable on POST /api/payments and handled by neither client: mapPaymentError had no
        // SELF_TRANSFER case, so it fell through to the catalogue message.
        expect(describeApiError(failure('SELF_TRANSFER'), 'payment-create')).toContain(
            'Choose a different account',
        );
    });

    it('has a sentence of its own for the daily limit', () => {
        expect(describeApiError(failure('DAILY_LIMIT_EXCEEDED'), 'payment-create')).toContain(
            'daily limit',
        );
    });

    it('tells an analyst who lost a race not to send the decision again', () => {
        // ALERT_CHANGED was handled on neither desk. The one thing this must not do is invite a
        // retry: the write that beat this one may have been the opposite verdict.
        const text = describeApiError(failure('ALERT_CHANGED', 409), 'alert-decision');
        expect(text).toContain('not applied');
        expect(text).not.toContain('try again');
        expect(text).not.toContain('again.');
    });

    it('refuses a filter in words on the queue, where only one desk said anything', () => {
        expect(describeApiError(failure('VALIDATION_ERROR'), 'alert-queue')).toContain('filter');
    });
});

describe('the same code, two endpoints, two actions', () => {
    it('says a payment that cannot be canceled has already been sent', () => {
        expect(describeApiError(failure('CONFLICT', 409), 'payment-cancel')).toContain(
            'already been sent',
        );
    });

    it('says a payment that cannot be confirmed should be looked at again', () => {
        expect(describeApiError(failure('CONFLICT', 409), 'payment-authorize')).toContain(
            'Refresh the list',
        );
    });

    it('says a decision that was not applied has been reloaded', () => {
        expect(describeApiError(failure('CONFLICT', 409), 'alert-decision')).toContain(
            'reloaded',
        );
    });

    it('promises nothing was charged only where that is true', () => {
        // CONCURRENT_MODIFICATION rolled the debit back, so resending is right.
        // TRANSFER_CHANGED cannot know which way the race went: if a cancel lost to an
        // authorization the money has gone, and "send it again" would invite paying twice.
        expect(describeApiError(failure('CONCURRENT_MODIFICATION', 409), 'payment-create'))
            .toContain('nothing was charged');
        expect(describeApiError(failure('TRANSFER_CHANGED', 409), 'payment-authorize'))
            .not.toContain('charged');
    });
});

describe('the attempts left, which only the screen knows', () => {
    it('counts them into the sentence', () => {
        const text = describeApiError(failure('INVALID_OTP'), 'payment-authorize', {
            triesLeft: 2,
        });
        expect(text).toContain('You have 2 attempts left.');
    });

    it('says one attempt, not one attempts', () => {
        const text = describeApiError(failure('INVALID_OTP'), 'payment-authorize', {
            triesLeft: 1,
        });
        expect(text).toContain('You have 1 attempt left.');
    });

    it('leaves the count out when the screen does not have it', () => {
        // The server now sends triesLeft only on a WAITING_AUTH transfer, so absent is a state
        // this reaches rather than an oversight.
        const text = describeApiError(failure('INVALID_OTP'), 'payment-authorize', {
            triesLeft: null,
        });
        expect(text).not.toContain('attempt');
    });
});

describe('what is not an answer from the API', () => {
    it('names the connection when there was no response at all', () => {
        const text = describeApiError(new TypeError('Failed to fetch'), 'alert-queue');
        expect(text).toContain('could not be reached');
        expect(text).not.toContain('Failed to fetch');
    });

    it('renders the server sentence for a code this table does not know', () => {
        // A code added to the catalogue later still reads as a sentence today.
        const text = describeApiError(
            failure('SOMETHING_NEW', 400, 'The bank has a new rule about this.'),
            'payment-create',
        );
        expect(text).toBe('The bank has a new rule about this.');
    });

    it('still says something for a body with neither a code nor a message', () => {
        // Spring Boot answers an unmatched URL itself, with a body that has no code field.
        expect(readable(describeApiError(failure(undefined, 404), 'payment-details'))).toBe(true);
    });
});

describe('the lines a screen may render separately', () => {
    it('splits the statement from the action', () => {
        const lines = describeApiErrorLines(failure('INVALID_IBAN'), 'payment-create');
        expect(lines.length).toBe(2);
        expect(describeApiError(failure('INVALID_IBAN'), 'payment-create')).toBe(lines.join(' '));
    });

    it('hands back a copy, so a caller cannot edit the table', () => {
        const lines = describeApiErrorLines(failure('NOT_FOUND', 404), 'alert-details');
        lines.push('and something else');
        expect(describeApiErrorLines(failure('NOT_FOUND', 404), 'alert-details')).toHaveLength(1);
    });
});

describe('the wording rules this table keeps', () => {
    it('never names a position on screen', () => {
        // The two desks put the same objects in different places. Both used to say "The panel
        // above has been refreshed", and on the workstation that panel is on the right.
        for (const operation of OPERATIONS) {
            for (const code of CODES) {
                const text = describeApiError(failure(code), operation);
                expect(text, `${operation} / ${code}`).not.toMatch(
                    /panel|on the left|on the right|\bbelow\b|\babove\b(?! its daily limit)/,
                );
            }
        }
    });

    it('never prints a raw enum at the reader', () => {
        for (const operation of OPERATIONS) {
            for (const code of CODES) {
                expect(describeApiError(failure(code), operation)).not.toContain(code);
            }
        }
    });
});
