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

let currentSessionId: string | null = null;

export function setSessionId(id: string | null) {
    currentSessionId = id;
}

/** Attaches the current session header to every call that needs one. */
export async function apiFetch(input: RequestInfo, init: RequestInit = {}): Promise<Response> {
    const headers = new Headers(init.headers || {});
    if (currentSessionId) {
        headers.set('X-Session-Id', currentSessionId);
    }
    return fetch(input, { ...init, headers });
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
function refusal(res: Response, text: string): ApiError {
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
    // login screen itself and must not trigger this.
    if (res.status === 401 && code === 'AUTH_REQUIRED') {
        setSessionId(null);
        onSessionExpired?.();
    }

    return error;
}

/**
 * Common response handler:
 * - throws an ApiError carrying { code, status } for non-2xx responses
 * - parses JSON on success, and throws {@link MalformedResponseError} when it is not JSON
 *
 * There is no third outcome. A caller that asks for `T` gets a parsed `T` or a rejection.
 */
export async function handle<T>(res: Response): Promise<T> {
    const text = await res.text();

    if (!res.ok) {
        throw refusal(res, text);
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
export async function handleEmpty(res: Response): Promise<void> {
    const text = await res.text();

    if (!res.ok) {
        throw refusal(res, text);
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
    role: 'CUSTOMER' | 'FRAUD_ANALYST' | 'OPERATIONS' | 'MANAGEMENT';
    customerId: number | null;
}

export async function login(payload: LoginRequest): Promise<LoginResponse> {
    const res = await fetch(`${API_BASE}/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    });
    const data = await handle<LoginResponse>(res);
    setSessionId(data.sessionId);
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
        void fetch(`${API_BASE}/auth/logout`, { method: 'POST', headers: { 'X-Session-Id': id } })
            .catch(() => { /* the session is gone locally either way */ });
    }
}
