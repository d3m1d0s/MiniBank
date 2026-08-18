import { useEffect, useState } from 'react';
import Login from './Login';
import FraudDesk from './FraudDesk';
import { logoutSession, setSessionExpiredHandler } from './api';
import type { NavRole } from '@shared/navigation';

/**
 * The roles MiniBank has screens for. The server's UserRole has four values and two of them
 * have nothing to show on either platform, so those two are named nowhere in this application:
 * they arrive as a string and leave as the absence below. NavRole already declares exactly this
 * pair, so it is not declared a second time.
 */
const SERVED_ROLES: readonly NavRole[] = ['CUSTOMER', 'FRAUD_ANALYST'];

/**
 * The role when MiniBank has screens for it, null when it does not.
 *
 * The argument is a plain string, and that is the point: the sign-in response is cast rather
 * than validated, so typing it as the two roles above would let a third walk through every
 * comparison that looks exhaustive to the reader.
 */
function servedRole(role: string): NavRole | null {
    return (SERVED_ROLES as readonly string[]).includes(role) ? (role as NavRole) : null;
}

interface AuthState {
    username: string;
    role: NavRole | null;
    customerId: number | null;
}

export default function App() {
    const [auth, setAuth] = useState<AuthState | null>(null);
    const [signedOutReason, setSignedOutReason] = useState<string | null>(null);

    // This app already had the "return to sign-in" mechanism, on the sign-out controls below.
    // Nothing in any error path ever invoked it, so a session the server had forgotten
    // left the analyst on a queue where every request failed. Sessions now go idle, hit a
    // ceiling and end when the analyst's own row changes, so this fires on a schedule rather
    // than only after a restart - and the notice names no cause, because the server
    // deliberately answers all of them the same way.
    useEffect(() => {
        setSessionExpiredHandler(() => {
            setAuth(null);
            setSignedOutReason('You have been signed out. Please sign in again.');
        });
        return () => setSessionExpiredHandler(null);
    }, []);

    if (!auth) {
        return (
            <Login
                notice={signedOutReason}
                onLoggedIn={(info) => {
                    setSignedOutReason(null);
                    setAuth({
                        username: info.username,
                        role: servedRole(info.role),
                        customerId: info.customerId,
                    });
                }}
            />
        );
    }

    if (auth.role !== 'FRAUD_ANALYST') {
        /*
         * Two different truths, and one sentence cannot carry both. A customer has screens in
         * MiniBank and simply not in this workstation yet; a role this client cannot name has
         * none anywhere. Neither line prints the role constant: for a customer the fact is
         * stated in prose, and for the other case the client has narrowed its type on purpose
         * so that it has no name to print, and inventing one would promise screens that do not
         * exist. The window is the sign-in window, because this person is at the door and not
         * inside: a full working window put two short lines in the corner of 700px of white.
         */
        const customer = auth.role === 'CUSTOMER';
        return (
            <div className="shell">
                <div className="window window--login">
                    <div className="titlebar">
                        <div className="title">MiniBank</div>
                    </div>
                    <div className="content">
                        <div className="panel">
                            <h2 className="panel-title">
                                {customer
                                    ? 'This workstation is for fraud analysts'
                                    : 'There are no screens for this account'}
                            </h2>
                            <p>
                                {customer
                                    ? `You are signed in as ${auth.username}, a customer account. Customer screens are not part of this workstation.`
                                    : `You are signed in as ${auth.username}. MiniBank has screens for customers and for fraud analysts, and this account is neither.`}
                            </p>
                            {/*
                              * The only control on the screen, so it may be the primary, and it
                              * is on the panel rather than in the chrome: a title bar exit here
                              * would be a second way out doing the same job. "Logout" was the
                              * old label and it is wrong for somebody who never signed in to
                              * this application; what pressing this buys is a different session.
                              */}
                            <div className="actions">
                                <button
                                    className="btn btn--primary"
                                    onClick={() => { logoutSession(); setAuth(null); }}
                                >
                                    Sign in as someone else
                                </button>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        );
    }

    return (
        <FraudDesk
            username={auth.username}
            onLogout={() => {
                logoutSession();
                setAuth(null);
            }}
        />
    );
}
