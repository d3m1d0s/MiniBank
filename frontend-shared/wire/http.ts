/**
 * The HTTP client both front ends talk to the API with.
 *
 * It is shared because it had already drifted. Each app carried its own copy of {@link handle},
 * and the same defect had to be found and fixed in each: the throw sat inside the try that parses
 * the body, so the catch replaced the error carrying the code with one carrying the raw JSON, and
 * {@code error.code} was always undefined. Two copies meant two investigations of one bug, and the
 * second app's copy still records that in a comment of its own.
 */

export type ApiError = Error & { code?: string; status?: number };

/**
 * True only for a failure that carries a server response, not for a network error.
 *
 * {@link MalformedResponseError} answers true here as well, and correctly: it carries a status
 * off a real response. It is a different event for a reader, so anything that words a failure
 * has to ask {@link isMalformedResponse} first.
 *
 * {@link RequestTimeoutError} answers false, and just as correctly: nothing came back at all.
 */
export function isApiError(e: unknown): e is ApiError {
    return e instanceof Error && typeof (e as ApiError).status === 'number';
}

/**
 * Relative, so a request goes to whatever origin served the page and that dev server's own proxy
 * forwards it. Naming the API's host and port here meant the proxy was never used and an app
 * could only ever talk to a backend on localhost:8080; it is also what made the customer app need
 * a cross-origin allowlist at all.
 */
export const API_BASE = '/api';

/**
 * How long a request may take before this side stops waiting for it.
 *
 * Two budgets, because one number cannot serve both kinds of call. Measured against this build:
 * an ordinary read answers in hundredths of a second (GET /api/me at 0,018 s), while a sign-in
 * takes seconds of honest work, since every attempt spends 220 000 PBKDF2-HMAC-SHA512 iterations
 * on the server (see Pbkdf2PasswordEncoder). A single five or ten second budget would be a
 * generous ceiling for every read in the application and a guillotine on a working sign-in.
 *
 * They are ceilings and not expectations. Nothing here waits for them on a healthy bank: they
 * exist so that a request which will never be answered ends as a sentence rather than as a
 * spinner nobody can dismiss.
 */
export const REQUEST_TIMEOUT_MS = 15_000;
export const SIGN_IN_TIMEOUT_MS = 30_000;

/**
 * A request this side gave up on, which is not the same event as a bank that cannot be reached.
 *
 * It carries no `status`, deliberately: {@link isApiError} answers false for it, because there
 * was no response and inventing a status would put "HTTP 0" under a reader's error box. That is
 * also why the wording table has to ask {@link isRequestTimeout} before it falls back to the
 * sentence for a failure with no response, which tells somebody to check a connection that is
 * working perfectly well.
 */
export class RequestTimeoutError extends Error {
    readonly timeoutMs: number;

    constructor(timeoutMs: number) {
        super(`The request was given up after ${Math.round(timeoutMs / 1000)} seconds`);
        this.name = 'RequestTimeoutError';
        this.timeoutMs = timeoutMs;
    }
}

export function isRequestTimeout(e: unknown): e is RequestTimeoutError {
    return e instanceof RequestTimeoutError;
}

/**
 * The one door to fetch in this module, so that no call site can be written without a deadline.
 *
 * All three requests this file makes go through it, including the sign-in, which does not use
 * {@link apiFetch} and would therefore have been the one request left unguarded by a timeout
 * installed there.
 *
 * The abort is what actually stops the work: without it the browser keeps the connection and the
 * caller simply stops listening. The timer is cleared on every path, including the successful
 * one, or a finished request would hold a handle for the rest of its budget.
 */
async function fetchWithin(
    input: RequestInfo,
    init: RequestInit,
    timeoutMs: number,
): Promise<Response> {
    const controller = new AbortController();
    let expired = false;
    const timer = setTimeout(() => {
        expired = true;
        controller.abort();
    }, timeoutMs);

    // A signal the caller brought is relayed rather than replaced. Nothing passes one today; a
    // screen that starts cancelling its own reads must not silently lose the deadline for it.
    const callerSignal = init.signal ?? null;
    const relay = () => controller.abort();
    callerSignal?.addEventListener('abort', relay, { once: true });

    try {
        return await fetch(input, { ...init, signal: controller.signal });
    } catch (e) {
        // Only this side's own deadline becomes the timeout. An abort the caller asked for is
        // theirs to describe, and a network failure stays the network failure it was.
        if (expired) throw new RequestTimeoutError(timeoutMs);
        throw e;
    } finally {
        clearTimeout(timer);
        callerSignal?.removeEventListener('abort', relay);
    }
}

// --- The session, and where it is kept ---

/** The four roles the server can sign somebody in as. Two of them have screens. */
export type SessionRole = 'CUSTOMER' | 'FRAUD_ANALYST' | 'OPERATIONS' | 'MANAGEMENT';

/**
 * What is known about whoever is signed in, beside the id itself.
 *
 * None of it is secret and none of it is trusted: the server re-reads the user row on every
 * request through SessionStore.resolve, so these three fields decide only which screen this side
 * draws first. A record that claims a role the session does not have gets a 403 on its first
 * call, exactly as a typed URL would.
 */
export interface SessionIdentity {
    username: string;
    role: SessionRole;
    customerId: number | null;
}

/** An identity with the id that proves it. What survives a reload of the tab. */
export interface StoredSession extends SessionIdentity {
    sessionId: string;
}

const SESSION_STORAGE_KEY = 'minibank.session';

let currentSessionId: string | null = null;
let currentIdentity: SessionIdentity | null = null;

/**
 * sessionStorage and never localStorage: the record must die with the tab, so that a browser
 * left open on a shared machine does not hand the next person a signed-in bank.
 *
 * A browser can refuse storage outright, by policy or in a private window, and reading the
 * property itself throws there. That must cost a reload and never the application, so every read
 * and every write is guarded and a refusal is simply a session that does not survive F5.
 */
function storage(): Storage | null {
    try {
        return typeof sessionStorage === 'undefined' ? null : sessionStorage;
    } catch {
        return null;
    }
}

function writeRecord(record: StoredSession | null) {
    const store = storage();
    if (!store) return;
    try {
        if (record) {
            store.setItem(SESSION_STORAGE_KEY, JSON.stringify(record));
        } else {
            store.removeItem(SESSION_STORAGE_KEY);
        }
    } catch {
        // Full, or refused after the check. The session still works in this tab.
    }
}

/**
 * The single door that writes the session, both into this module and into the tab's storage.
 *
 * `null` erases the record, and that is the half worth stating: refusal() calls this on a 401
 * AUTH_REQUIRED, and a record left behind there would start the next reload holding an id the
 * server has already rejected.
 *
 * The identity is optional because one caller has it, {@link login}, and everything else is
 * either erasing the session or restoring one it has just read. An id set with no identity known
 * is not written down: a record with no username and no role restores nothing a shell can draw,
 * and half a record is worse than none.
 */
export function setSessionId(id: string | null, identity?: SessionIdentity) {
    currentSessionId = id;

    if (identity) currentIdentity = identity;

    if (id === null) {
        currentIdentity = null;
        writeRecord(null);
        return;
    }

    writeRecord(currentIdentity ? { sessionId: id, ...currentIdentity } : null);
}

/**
 * The session this tab was holding before it was reloaded, or null.
 *
 * Meant to be read once, in the shell's own initial state rather than in an effect: an effect
 * paints one frame of the sign-in card over a session that was never lost, which is the flicker
 * this exists to prevent.
 *
 * Nothing here asks the server whether the id is still good, and it does not need to. The first
 * request the restored screen makes answers 401 AUTH_REQUIRED if it is not, and that path
 * already ends at the sign-in card with the notice; both shells ask GET /api/me on the way in,
 * so the check happens on its own.
 *
 * A record this build cannot read is erased rather than carried: a shape written by an older
 * version of this file must not be able to throw on the first render forever.
 */
export function restoreSession(): StoredSession | null {
    const store = storage();
    if (!store) return null;

    let raw: string | null = null;
    try {
        raw = store.getItem(SESSION_STORAGE_KEY);
    } catch {
        return null;
    }
    if (!raw) return null;

    const record = readRecord(raw);
    if (!record) {
        writeRecord(null);
        return null;
    }

    currentSessionId = record.sessionId;
    currentIdentity = {
        username: record.username,
        role: record.role,
        customerId: record.customerId,
    };
    return record;
}

/**
 * Every field checked for its type, in the pattern refusal() uses on an error body: what comes
 * out of storage is text somebody could have typed, and the shells are written against the shape
 * rather than against a promise.
 *
 * The role is the one value that is cast rather than checked against the four names, and on
 * purpose: the sign-in response is not validated either, and both shells parse an unknown role
 * into "there are no screens for this account" instead of trusting it.
 */
function readRecord(raw: string): StoredSession | null {
    try {
        const parsed = JSON.parse(raw) as unknown;
        if (parsed === null || typeof parsed !== 'object') return null;

        const r = parsed as Record<string, unknown>;
        if (typeof r.sessionId !== 'string' || !r.sessionId) return null;
        if (typeof r.username !== 'string' || !r.username) return null;
        if (typeof r.role !== 'string') return null;
        if (r.customerId !== null && typeof r.customerId !== 'number') return null;

        return {
            sessionId: r.sessionId,
            username: r.username,
            role: r.role as SessionRole,
            customerId: r.customerId as number | null,
        };
    } catch {
        return null;
    }
}

/** Attaches the current session header to every call that needs one. */
export async function apiFetch(input: RequestInfo, init: RequestInit = {}): Promise<Response> {
    const headers = new Headers(init.headers || {});
    if (currentSessionId) {
        headers.set('X-Session-Id', currentSessionId);
    }
    return fetchWithin(input, { ...init, headers }, REQUEST_TIMEOUT_MS);
}

let onSessionExpired: (() => void) | null = null;

/**
 * Registered by the app shell. Without it a rejected session leaves a signed-in UI whose every
 * request fails, and the only way back to the sign-in screen is a page reload.
 */
export function setSessionExpiredHandler(fn: (() => void) | null) {
    onSessionExpired = fn;
}

/**
 * An answer the caller cannot use: a 2xx whose body is not the JSON that {@link handle} promised
 * the caller it would return.
 *
 * It is a throw rather than a return because there is no honest value to return. Handing the raw
 * text back as `T` moved the failure out of the request and into the render: a screen typed for
 * an array called `.map` on a string, and that throw happened outside the try every screen had
 * honestly written around its own call, so nothing caught it and the whole application went
 * blank. This way the failure lands in the catch that is already there.
 *
 * `empty` is the case worth telling apart, because it is the one that is sometimes correct. A
 * call whose success has no body reads it with {@link handleEmpty} instead, and gets no error.
 */
export class MalformedResponseError extends Error {
    readonly status: number;
    readonly contentType: string | null;
    readonly empty: boolean;

    constructor(status: number, contentType: string | null, empty: boolean) {
        // Says what arrived, and never what was in it: the body is exactly the thing that must
        // not be carried any further. See the note in refusal() below.
        super(
            empty
                ? `The bank answered ${status} with an empty body where a value was expected`
                : `The bank answered ${status} with a body that is not JSON (${contentType ?? 'no content type'})`,
        );
        this.name = 'MalformedResponseError';
        this.status = status;
        this.contentType = contentType;
        this.empty = empty;
    }
}

export function isMalformedResponse(e: unknown): e is MalformedResponseError {
    return e instanceof MalformedResponseError;
}

/**
 * The error every refused request throws, built from the response and from nothing else.
 *
 * The parse must not wrap the throw. It used to, in both copies of this function: `throw error`
 * sat inside the try, so the catch swallowed the error that carried the code and replaced it with
 * `new Error(rawBody)`. That is why every error box rendered a literal JSON blob and why
 * error.code was always undefined.
 */
function refusal(res: Response, text: string, options: HandleOptions = {}): ApiError {
    let code: string | undefined;
    let message = '';

    if (text) {
        try {
            const parsed = JSON.parse(text) as unknown;
            if (parsed !== null && typeof parsed === 'object') {
                const body = parsed as { code?: unknown; message?: unknown };
                // Both are checked for their type rather than read: a body that parses is not
                // yet the error contract. Spring Boot's own answer to a request that reaches no
                // handler parses and carries neither field, and it was that miss which fell
                // through to res.statusText and printed "Internal Server Error" at a customer.
                if (typeof body.code === 'string') code = body.code;
                if (typeof body.message === 'string') message = body.message;
            }
        } catch {
            // Not the contract at all: a gateway's HTML page, a proxy's plain text. Dropped on
            // purpose, and this is the whole point of the branch. The old line here was
            // `message = text`, and it is what printed the source of a 502 page into the error
            // box of a bank's customer screen.
        }
    }

    // No filler. An empty message means the answer carried no sentence for a reader, which is a
    // fact the wording table needs: it answers by status there, and res.statusText is machine
    // text that must not reach a screen. What keeps the failure diagnosable is the pair below.
    const error = new Error(message) as ApiError;
    error.code = code;
    error.status = res.status;

    // AUTH_REQUIRED means the session is gone; AUTH_FAILED is a rejected sign-in on the
    // login screen itself and must not trigger this. The session is dropped either way, because
    // it is dead either way; what `silentAuth` holds back is the eviction of whoever is reading
    // the screen. See HandleOptions.
    if (res.status === 401 && code === 'AUTH_REQUIRED') {
        setSessionId(null);
        if (!options.silentAuth) {
            onSessionExpired?.();
        }
    }

    return error;
}

/**
 * What a caller may say about how its own failure is to be treated.
 *
 * `silentAuth` is for the one shape of call that must not be able to take the screen down with
 * it: a read fired after something has already succeeded, whose whole purpose is to freshen a
 * number beside a result the reader has not finished reading. The refresh of the balances after
 * a payment is exactly that, and today a session that died between the two requests replaces the
 * confirmation with the sign-in card, so the person never learns what happened to their money.
 *
 * It does not keep the session alive and does not pretend the call worked. The id is still
 * cleared and the record still erased, so nothing carries a rejected id any further; the next
 * thing the reader tries fails loudly and ejects them properly. Never pass it on a call whose
 * answer the screen is written against.
 */
export interface HandleOptions {
    silentAuth?: boolean;
}

/**
 * Common response handler:
 * - throws an ApiError carrying { code, status } for non-2xx responses
 * - parses JSON on success, and throws {@link MalformedResponseError} when it is not JSON
 *
 * There is no third outcome. A caller that asks for `T` gets a parsed `T` or a rejection.
 */
export async function handle<T>(res: Response, options: HandleOptions = {}): Promise<T> {
    const text = await res.text();

    if (!res.ok) {
        throw refusal(res, text, options);
    }

    const contentType = res.headers.get('content-type');

    if (!text.trim()) {
        throw new MalformedResponseError(res.status, contentType, true);
    }

    try {
        return JSON.parse(text) as T;
    } catch {
        throw new MalformedResponseError(res.status, contentType, false);
    }
}

/**
 * The same handler for a call whose success carries no value: a 204, or a 200 with nothing in it.
 *
 * The explicit way to say so, and the reason {@link handle} can be strict. It still throws the
 * same ApiError on a refusal, so a caller loses nothing by using it; what it gives up is the
 * body, which it did not want. A body that arrives anyway is ignored rather than refused: no
 * endpoint here promises 204 today, and a server that grows one must not break a screen that
 * asked for nothing.
 */
export async function handleEmpty(res: Response, options: HandleOptions = {}): Promise<void> {
    const text = await res.text();

    if (!res.ok) {
        throw refusal(res, text, options);
    }
}

// --- Auth, which both apps do identically ---

export interface LoginRequest {
    username: string;
    password: string;
}

export interface LoginResponse {
    sessionId: string;
    username: string;
    role: SessionRole;
    customerId: number | null;
}

/**
 * Signs in and, with the answer, writes down everything a reload needs to draw the same screen:
 * the id, and the three facts about the person that the response already carries.
 *
 * Its own budget, and not apiFetch's: this request is the one call in the application that does
 * real work before it can answer, and a read's ceiling would cut off a sign-in that was going to
 * succeed.
 */
export async function login(payload: LoginRequest): Promise<LoginResponse> {
    const res = await fetchWithin(
        `${API_BASE}/auth/login`,
        {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
        },
        SIGN_IN_TIMEOUT_MS,
    );
    const data = await handle<LoginResponse>(res);
    setSessionId(data.sessionId, {
        username: data.username,
        role: data.role,
        customerId: data.customerId,
    });
    return data;
}

/**
 * Closes the session at both ends. Clearing only the local variable would leave a fully usable
 * session on the server, which is what every Logout press did until the server put logout behind
 * authentication.
 *
 * Fire and forget on purpose: a session that has already expired answers 401 AUTH_REQUIRED, and
 * routing that through handle() would show "you have been signed out" to somebody who just
 * pressed Logout.
 */
export function logoutSession() {
    const id = currentSessionId;
    setSessionId(null);
    if (id) {
        // Through the same door as everything else, and here only so that no controller and no
        // timer is left hanging on a request nobody will ever look at the answer of.
        void fetchWithin(
            `${API_BASE}/auth/logout`,
            { method: 'POST', headers: { 'X-Session-Id': id } },
            REQUEST_TIMEOUT_MS,
        ).catch(() => { /* the session is gone locally either way */ });
    }
}
