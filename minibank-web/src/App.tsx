// src/App.tsx
import { useEffect, useState } from 'react';
import './App.css';
import { setSessionExpiredHandler, logoutSession, fetchMe, type Me } from './api';
import type { NavRole } from '@shared/navigation';
import {
    NO_SCREENS_TITLE,
    SIGNED_OUT_NOTICE,
    SIGN_IN_AS_SOMEONE_ELSE,
    noScreensNote,
} from '@shared/navigation';
import { roleLabel } from '@shared/glossary';
import NewPaymentPage from './NewPaymentPage';
import HistoryPage from './HistoryPage';
import { WaitingAuthorizationsPage } from './WaitingAuthorizationsPage';
import FraudDeskPage from './FraudDeskPage';
import LoginDialog from './LoginDialog.tsx';

export type View = 'new-payment' | 'history' | 'waiting-auth' | 'fraud-desk';

/**
 * The roles this client has screens for. The server's UserRole has four values; two of them
 * have nothing to show here, so they are named nowhere in this file except as the absence
 * below. NavRole already declares exactly these two, so they are not declared a second time.
 */
const SERVED_ROLES: readonly NavRole[] = ['CUSTOMER', 'FRAUD_ANALYST'];

/**
 * The role when this client has screens for it, null when it does not.
 *
 * The argument is a plain string, and that is the point: the sign-in response is cast rather
 * than validated, so typing it as the two served roles would let a third walk through every
 * comparison that looks exhaustive. An unknown role is parsed here and answered with a screen
 * instead of reaching a customer form and failing there on a 403.
 */
function servedRole(role: string): NavRole | null {
    return (SERVED_ROLES as readonly string[]).includes(role) ? (role as NavRole) : null;
}

interface AuthState {
    username: string;
    role: NavRole | null;
    customerId: number | null;
}

function App() {
    const [view, setView] = useState<View>('new-payment');
    const [auth, setAuth] = useState<AuthState | null>(null);
    const [signedOutReason, setSignedOutReason] = useState<string | null>(null);

    /**
     * The person behind the login, or null until the answer arrives.
     *
     * The sign-in response carries the string that was typed into the box and nothing else, so
     * the header band greeted a customer whose record says Alice Novakova as `alice`. This is the
     * one call in the application whose failure changes nothing: the band falls back to the login
     * it already has, so there is no error state and no loading state for it.
     */
    const [me, setMe] = useState<Me | null>(null);

    // A 401 AUTH_REQUIRED means the server no longer accepts the session this app is
    // holding: it has gone idle, hit its ceiling, been closed, or the user behind it has
    // been removed or replaced - and the server deliberately does not say which. Until now
    // there was no path back to the sign-in screen at all: setAuth was called exactly once
    // and this app has no logout control, so recovery was an undiscoverable F5. The reason
    // is carried across so the user is not simply ejected mid-payment with no explanation,
    // and it names none of the four causes, because "expired" would be a false statement
    // about three of them. The hook sits above the early return below because the rules of
    // hooks require it.
    useEffect(() => {
        setSessionExpiredHandler(() => {
            setAuth(null);
            // A name left standing after the session has gone would greet whoever reaches this
            // browser next by the person who was ejected from it.
            setMe(null);
            setSignedOutReason(SIGNED_OUT_NOTICE);
        });
        return () => setSessionExpiredHandler(null);
    }, []);

    // Asked once per session. The name is dropped where the session is dropped rather than here,
    // both because an effect that sets state on the way in is a render that could have been
    // avoided, and because the two places that end a session are the two that know it has ended.
    // The hook sits above the early return below because the rules of hooks require it.
    useEffect(() => {
        if (!auth) return;

        let cancelled = false;

        void (async () => {
            try {
                const who = await fetchMe();
                if (!cancelled) setMe(who);
            } catch {
                // Nothing on any screen depends on the name; the band keeps the login.
            }
        })();

        return () => {
            cancelled = true;
        };
    }, [auth]);

    // While the user is not logged in, show only the login dialog
    if (!auth) {
        return (
            <LoginDialog
                notice={signedOutReason}
                onLoggedIn={(info) => {
                    setSignedOutReason(null);
                    const role = servedRole(info.role);
                    setAuth({ username: info.username, role, customerId: info.customerId });
                    // Initial view depends on the logged-in role
                    setView(role === 'FRAUD_ANALYST' ? 'fraud-desk' : 'new-payment');
                }}
            />
        );
    }

    // Closes the session at both ends before dropping back to the dialog. Without the call the
    // server would keep a fully usable session for a user who believes they have left.
    const handleSignOut = () => {
        logoutSession();
        setSignedOutReason(null);
        setAuth(null);
        setMe(null);
    };

    // Ahead of the view switch, so a role with no screens never reaches the payment form and
    // never fires the 403 that form used to answer with.
    if (!auth.role) {
        return (
            <div className="app-shell">
                <div className="card">
                    <header className="card-header">
                        <h1>MiniBank</h1>
                    </header>
                    <div className="card-body">
                        <main className="form-panel">
                            <h2>{NO_SCREENS_TITLE}</h2>
                            <p className="helper-text">{noScreensNote(auth.username)}</p>
                            <div className="actions">
                                <button
                                    type="button"
                                    className="btn-primary"
                                    onClick={handleSignOut}
                                >
                                    {SIGN_IN_AS_SOMEONE_ELSE}
                                </button>
                            </div>
                        </main>
                    </div>
                </div>
            </div>
        );
    }

    // Centralized navigation that applies role-based restrictions
    const handleNavigate = (next: View) => {
        // Example: fraud analyst cannot go to customer-facing views
        if (auth.role === 'FRAUD_ANALYST' && next !== 'fraud-desk') {
            return;
        }
        setView(next);
    };

    /**
     * The name the band greets, which is the person's own where the server knows one.
     *
     * The login is the fallback rather than the default, and it is reached twice: before the
     * answer arrives, and for a login with no customer record behind it, which is every analyst.
     * The answer is checked against the login it was asked for, so a name fetched for the
     * previous session cannot be printed over the current one.
     */
    const signedInAs =
        me && me.username === auth.username && me.name ? me.name : auth.username;

    /**
     * Who is signed in and the way out, built once here and handed to every screen as one
     * opaque piece of furniture, so no screen has to learn what a role or a session is.
     * The person is named because this application serves two roles from one sign-in and
     * gives each a different set of screens, and the role label is the only thing on screen
     * that explains why the screens differ.
     */
    const identity = (
        <div className="identity">
            <span className="identity-who">
                {signedInAs}
                <span className="identity-role">{roleLabel(auth.role)}</span>
            </span>
            <button type="button" className="identity-exit" onClick={handleSignOut}>
                Sign out
            </button>
        </div>
    );

    /**
     * The mark and the name of the application, built once here and handed to every screen the
     * same way identity is. The band across the top says which application this is; the screen
     * says its own name at the top of the working area, where the work begins.
     */
    const brand = (
        <div className="brand">
            <span className="brand-mark" aria-hidden="true" />
            <h1 className="brand-name">MiniBank</h1>
        </div>
    );

    return (
        <>
            {view === 'new-payment' && (
                <NewPaymentPage
                    role={auth.role}
                    brand={brand}
                    identity={identity}
                    onNavigate={handleNavigate}
                />
            )}
            {view === 'history' && (
                <HistoryPage
                    role={auth.role}
                    brand={brand}
                    identity={identity}
                    onNavigate={handleNavigate}
                />
            )}
            {view === 'waiting-auth' && (
                <WaitingAuthorizationsPage
                    role={auth.role}
                    brand={brand}
                    identity={identity}
                    onNavigate={handleNavigate}
                />
            )}
            {view === 'fraud-desk' && (
                <FraudDeskPage
                    role={auth.role}
                    /* The login and not the name: the assignee column holds what was typed at
                       sign-in, so the queue's mine filter is a match against that string. */
                    username={auth.username}
                    brand={brand}
                    identity={identity}
                    onNavigate={handleNavigate}
                />
            )}
        </>
    );
}

export default App;
