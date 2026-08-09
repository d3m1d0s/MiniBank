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

/** True only for a failure that carries a server response, not for a network error. */
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
 * Common response handler:
 * - throws an ApiError carrying { code, status } for non-2xx responses
 * - parses JSON on success, or returns plain text as a fallback
 */
export async function handle<T>(res: Response): Promise<T> {
    const text = await res.text();

    if (!res.ok) {
        let code: string | undefined;
        let message = '';

        // The parse must not wrap the throw. It used to, in both copies of this function:
        // `throw error` sat inside this try, so the catch below swallowed the error that carried
        // the code and replaced it with `new Error(rawBody)`. That is why every error box
        // rendered a literal JSON blob and why error.code was always undefined.
        if (text) {
            try {
                const parsed = JSON.parse(text) as { code?: string; message?: string };
                code = parsed.code;
                message = parsed.message ?? '';
            } catch {
                message = text;
            }
        }

        const error = new Error(message || res.statusText || 'Request failed') as ApiError;
        error.code = code;
        error.status = res.status;

        // AUTH_REQUIRED means the session is gone; AUTH_FAILED is a rejected sign-in on the
        // login screen itself and must not trigger this.
        if (res.status === 401 && code === 'AUTH_REQUIRED') {
            setSessionId(null);
            onSessionExpired?.();
        }

        throw error;
    }

    if (!text) {
        // For an empty body, for example 204 No Content.
        return {} as T;
    }

    try {
        return JSON.parse(text) as T;
    } catch {
        // The backend returned a non-JSON payload; hand it back as text.
        return text as unknown as T;
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
