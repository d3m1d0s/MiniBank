import { afterEach, describe, expect, it, vi } from 'vitest';
import {
    apiFetch,
    handle,
    handleEmpty,
    isApiError,
    isMalformedResponse,
    MalformedResponseError,
    setSessionExpiredHandler,
    setSessionId,
} from './http';
import { apiErrorReference, describeApiError, describeApiErrorLines, describeApiFailure } from './apiErrors';

/**
 * handle() is the one function both applications route every request through, and it had no test
 * at all. Its outcomes are what the screens are written against: which of them throws decides
 * whether a failure lands in the catch a screen wrote or in the render, where nothing catches it.
 *
 * The cases are named after what comes back on the wire rather than after the code, so a change
 * to the implementation that keeps the promise keeps these passing.
 */

/** A response built the way the API answers: a body, a status, and a content type. */
function answer(status: number, body: string | null, contentType = 'application/json'): Response {
    return new Response(body, {
        status,
        headers: body === null ? undefined : { 'Content-Type': contentType },
    });
}

afterEach(() => {
    setSessionExpiredHandler(null);
    setSessionId(null);
    vi.unstubAllGlobals();
});

describe('a 2xx that carries the value', () => {
    it('parses an object', async () => {
        const value = await handle<{ id: number }>(answer(200, '{"id":7}'));
        expect(value).toEqual({ id: 7 });
    });

    it('parses an array, which is the type most callers ask for', async () => {
        const value = await handle<number[]>(answer(200, '[1,2,3]'));
        expect(value).toEqual([1, 2, 3]);
    });
});

describe('a 2xx that does not carry the value', () => {
    /*
     * The defect these two replace: both used to RETURN. An empty body became {} and a page of
     * HTML became a string, each handed to a caller typed for an array or an object, and the
     * first .map or property read threw during render, outside the try the screen had written
     * around its own call.
     */
    it('throws on an empty body rather than returning {}', async () => {
        const failed = await handle(answer(200, '')).catch((e: unknown) => e);
        expect(isMalformedResponse(failed)).toBe(true);
        expect((failed as MalformedResponseError).empty).toBe(true);
        expect((failed as MalformedResponseError).status).toBe(200);
    });

    it('throws on a body that is not JSON, and records the content type it got instead', async () => {
        const page = '<html><body><h1>502 Bad Gateway</h1></body></html>';
        const failed = await handle(answer(200, page, 'text/html')).catch((e: unknown) => e);
        expect(isMalformedResponse(failed)).toBe(true);
        expect((failed as MalformedResponseError).empty).toBe(false);
        expect((failed as MalformedResponseError).contentType).toContain('text/html');
    });

    it('never carries the body into the message, not even for the developer', async () => {
        const page = '<html><body><pre>upstream connect error</pre></body></html>';
        const failed = await handle(answer(200, page, 'text/html')).catch((e: unknown) => e);
        expect((failed as Error).message).not.toContain('upstream connect error');
        expect((failed as Error).name).toBe('MalformedResponseError');
    });
});

describe('a refusal', () => {
    it('carries the code, the status and the sentence when the body is the error contract', async () => {
        const body = '{"code":"CONFLICT","message":"The alert has already been decided."}';
        const failed = await handle(answer(409, body)).catch((e: unknown) => e);
        expect(isApiError(failed)).toBe(true);
        expect((failed as { code?: string }).code).toBe('CONFLICT');
        expect((failed as { status?: number }).status).toBe(409);
        expect((failed as Error).message).toBe('The alert has already been decided.');
    });

    it('takes no code and no sentence from a body that parses but is not the contract', async () => {
        // Spring Boot's own answer when a request reaches no handler of ours. It is valid JSON
        // and it has no code, and "error" is a status line rather than a sentence for a reader.
        const body = '{"timestamp":"2026-08-20T10:00:00Z","status":500,"error":"Internal Server Error","path":"/api/me"}';
        const failed = await handle(answer(500, body)).catch((e: unknown) => e);
        expect((failed as { code?: string }).code).toBeUndefined();
        expect((failed as Error).message).toBe('');
        expect((failed as { status?: number }).status).toBe(500);
    });

    it('drops a body that is not JSON at all', async () => {
        const page = '<html><head><title>500</title></head><body><h1>502 Bad Gateway</h1></body></html>';
        const failed = await handle(answer(502, page, 'text/html')).catch((e: unknown) => e);
        expect((failed as Error).message).toBe('');
        expect((failed as { status?: number }).status).toBe(502);
    });

    it('invents nothing for an empty body', async () => {
        // res.statusText used to stand in here, which is where "Internal Server Error" and
        // "Request failed" came from. An empty message is the truth and the wording table
        // answers by status.
        const failed = await handle(answer(500, '')).catch((e: unknown) => e);
        expect((failed as Error).message).toBe('');
        expect((failed as { status?: number }).status).toBe(500);
    });
});

describe('the session, which only one refusal is allowed to end', () => {
    it('clears the session and calls the handler on 401 AUTH_REQUIRED', async () => {
        const expired = vi.fn();
        setSessionExpiredHandler(expired);
        setSessionId('abc');

        await handle(answer(401, '{"code":"AUTH_REQUIRED","message":"Sign in."}')).catch(() => {});

        expect(expired).toHaveBeenCalledTimes(1);

        // There is no getter for the session id, so it is read the way the API reads it: off the
        // header of the next request.
        const fetchMock = vi.fn(async () => answer(200, '{}'));
        vi.stubGlobal('fetch', fetchMock);
        await apiFetch('/api/me');
        const sent = new Headers((fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1].headers);
        expect(sent.has('X-Session-Id')).toBe(false);
    });

    it('leaves the session alone on 401 AUTH_FAILED, which is a rejected sign-in', async () => {
        const expired = vi.fn();
        setSessionExpiredHandler(expired);
        setSessionId('abc');

        await handle(answer(401, '{"code":"AUTH_FAILED","message":"Wrong password."}')).catch(() => {});

        expect(expired).not.toHaveBeenCalled();

        const fetchMock = vi.fn(async () => answer(200, '{}'));
        vi.stubGlobal('fetch', fetchMock);
        await apiFetch('/api/me');
        const sent = new Headers((fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1].headers);
        expect(sent.get('X-Session-Id')).toBe('abc');
    });
});

describe('handleEmpty, for a call whose success has no value', () => {
    it('accepts a 204', async () => {
        await expect(handleEmpty(new Response(null, { status: 204 }))).resolves.toBeUndefined();
    });

    it('throws the same error on a refusal', async () => {
        const failed = await handleEmpty(answer(403, '{"code":"FORBIDDEN"}')).catch((e: unknown) => e);
        expect((failed as { code?: string }).code).toBe('FORBIDDEN');
        expect((failed as { status?: number }).status).toBe(403);
    });
});

/**
 * The seam this whole change exists for: what a person ends up reading. Four refusals used to
 * reach the screen as machine text, one of them as page source.
 */
describe('what the four bodies say once the wording table has them', () => {
    it('says the bank has a problem for a 500 with no code', async () => {
        const body = '{"timestamp":"2026-08-20T10:00:00Z","status":500,"error":"Internal Server Error"}';
        const failed = await handle(answer(500, body)).catch((e: unknown) => e);
        const text = describeApiError(failed, 'payments-waiting');
        expect(text).toContain('went wrong at the bank');
        expect(text).not.toContain('Internal Server Error');
    });

    it('says the same for a 502 whose body is a gateway page, and prints none of it', async () => {
        const page = '<html><head><title>500</title></head><body><h1>502 Bad Gateway</h1><pre>upstream connect error</pre></body></html>';
        const failed = await handle(answer(502, page, 'text/html')).catch((e: unknown) => e);
        const text = describeApiError(failed, 'payments-waiting');
        expect(text).toContain('went wrong at the bank');
        expect(text).not.toContain('<');
        expect(text).not.toContain('upstream');
    });

    it('says the request was refused for a 4xx with nothing in it', async () => {
        const failed = await handle(answer(418, '')).catch((e: unknown) => e);
        expect(describeApiError(failed, 'payments-waiting')).toContain('refused this request');
    });

    it('says the answer could not be read when a 2xx is not JSON', async () => {
        const failed = await handle(answer(200, 'not json at all', 'text/plain')).catch((e: unknown) => e);
        const lines = describeApiErrorLines(failed, 'payments-waiting');
        expect(lines[0]).toContain("answer could not be read");
        // Not a refusal: the request was accepted and the answer is what failed.
        expect(lines.join(' ')).not.toContain('refused');
    });

    it('still prefers the server sentence when the body carried one', async () => {
        // The fallback that keeps a code added to the catalogue tomorrow readable today.
        const body = '{"code":"WHAT_IS_THIS","message":"The vault door is ajar."}';
        const failed = await handle(answer(400, body)).catch((e: unknown) => e);
        expect(describeApiError(failed, 'payments-waiting')).toBe('The vault door is ajar.');
    });
});

describe('the reference that keeps a screenshot diagnosable', () => {
    it('names the status and the code when there is one', async () => {
        const failed = await handle(answer(409, '{"code":"CONFLICT"}')).catch((e: unknown) => e);
        expect(apiErrorReference(failed)).toBe('HTTP 409 CONFLICT');
    });

    it('names the status alone when the body carried no code', async () => {
        const failed = await handle(answer(502, '<html></html>', 'text/html')).catch((e: unknown) => e);
        expect(apiErrorReference(failed)).toBe('HTTP 502');
    });

    it('tells the two unreadable answers apart', async () => {
        const empty = await handle(answer(200, '')).catch((e: unknown) => e);
        const garbage = await handle(answer(200, 'nope', 'text/plain')).catch((e: unknown) => e);
        expect(apiErrorReference(empty)).toBe('HTTP 200 EMPTY_BODY');
        expect(apiErrorReference(garbage)).toBe('HTTP 200 MALFORMED_BODY');
    });

    it('has nothing to show when there was no answer at all', () => {
        // A failed fetch. UNREACHABLE has already said so in words, and "HTTP 0" is an invention.
        expect(apiErrorReference(new TypeError('Failed to fetch'))).toBeNull();
    });
});

describe('the failure shape the error boxes render', () => {
    it('carries the sentences and the reference, and no retry unless one is offered', async () => {
        const failed = await handle(answer(500, '')).catch((e: unknown) => e);
        const failure = describeApiFailure(failed, 'payments-waiting');
        expect(failure.lines.join(' ')).toContain('went wrong at the bank');
        expect(failure.reference).toBe('HTTP 500');
        expect(failure.retry).toBeUndefined();
        expect(failure.retryLabel).toBeUndefined();
    });

    it('carries the retry the screen passed, with one label for both applications', async () => {
        const retry = vi.fn();
        const failed = await handle(answer(500, '')).catch((e: unknown) => e);
        const failure = describeApiFailure(failed, 'payments-waiting', { retry });
        expect(failure.retryLabel).toBe('Try again');
        failure.retry?.();
        expect(retry).toHaveBeenCalledTimes(1);
    });

    it('keeps the attempts left, which is the one fact only the screen has', async () => {
        const failed = await handle(answer(400, '{"code":"INVALID_OTP"}')).catch((e: unknown) => e);
        const failure = describeApiFailure(failed, 'payment-authorize', { triesLeft: 2, retry: () => {} });
        expect(failure.lines.join(' ')).toContain('2 attempts left');
    });
});
