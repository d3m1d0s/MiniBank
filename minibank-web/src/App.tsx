// src/App.tsx
import { useEffect, useState } from 'react';
import './App.css';
import { setSessionExpiredHandler, logoutSession } from './api';
import type { NavRole } from '@shared/navigation';
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

/** What each of them is called on screen. The server's values are not a reader's words. */
const ROLE_LABEL: Record<NavRole, string> = {
    CUSTOMER: 'Customer',
    FRAUD_ANALYST: 'Fraud analyst',
};

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
            setSignedOutReason('You have been signed out. Please sign in again.');
        });
        return () => setSessionExpiredHandler(null);
    }, []);

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
                            <h2>There are no screens for this account</h2>
                            <p className="helper-text">
                                You are signed in as {auth.username}. MiniBank has screens for
                                customers and for fraud analysts, and this account is neither.
                            </p>
                            <div className="actions">
                                <button
                                    type="button"
                                    className="btn-primary"
                                    onClick={handleSignOut}
                                >
                                    Sign in as someone else
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
     * Who is signed in and the way out, built once here and handed to every screen as one
     * opaque piece of furniture, so no screen has to learn what a role or a session is.
     * The username is shown because this application serves two roles from one sign-in and
     * gives each a different set of screens, and the role label is the only thing on screen
     * that explains why the screens differ.
     */
    const identity = (
        <div className="identity">
            <span className="identity-who">
                {auth.username}
                <span className="identity-role">{ROLE_LABEL[auth.role]}</span>
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
                    brand={brand}
                    identity={identity}
                    onNavigate={handleNavigate}
                />
            )}
        </>
    );
}

export default App;
