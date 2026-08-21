import { useEffect, useState } from 'react';
import Login from './Login';
import FraudDesk from './FraudDesk';
import { fetchMe, logoutSession, setSessionExpiredHandler, type Me } from './api';
import {
    NO_SCREENS_TITLE,
    SIGNED_OUT_NOTICE,
    SIGN_IN_AS_SOMEONE_ELSE,
    noScreensNote,
    type NavRole,
} from '@shared/navigation';

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
    const [me, setMe] = useState<Me | null>(null);

    // This app already had the "return to sign-in" mechanism, on the sign-out controls below.
    // Nothing in any error path ever invoked it, so a session the server had forgotten
    // left the analyst on a queue where every request failed. Sessions now go idle, hit a
    // ceiling and end when the analyst's own row changes, so this fires on a schedule rather
    // than only after a restart - and the notice names no cause, because the server
    // deliberately answers all of them the same way.
    useEffect(() => {
        setSessionExpiredHandler(() => {
            setAuth(null);
            // A name left standing after the session has gone would put the ejected analyst's
            // name over whoever reaches this workstation next.
            setMe(null);
            setSignedOutReason(SIGNED_OUT_NOTICE);
        });
        return () => setSessionExpiredHandler(null);
    }, []);

    // Asked once per session, and by the route the customer application asks. The name is dropped
    // where the session is dropped rather than here, both because an effect that sets state on the
    // way in is a render that could have been avoided, and because the two places that end a
    // session are the two that know it has ended. The hook sits above the early returns below
    // because the rules of hooks require it.
    useEffect(() => {
        if (!auth) return;

        let cancelled = false;

        void (async () => {
            try {
                const who = await fetchMe();
                if (!cancelled) setMe(who);
            } catch {
                // Nothing on any screen depends on the name; the title bar keeps the login.
            }
        })();

        return () => {
            cancelled = true;
        };
    }, [auth]);

    // Closes the session at both ends before dropping back to the dialog, and drops the name with
    // it. Written once here because both ways out of this window press the same thing.
    const handleSignOut = () => {
        logoutSession();
        setAuth(null);
        setMe(null);
    };

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
                            {/*
                              * The second branch is both applications' sentence and is read from
                              * the shared module. The first is this window's alone: a signed in
                              * customer at the analyst's workstation has screens in MiniBank and
                              * simply not here, which is not what the other branch says.
                              */}
                            <h2 className="panel-title">
                                {customer
                                    ? 'This workstation is for fraud analysts'
                                    : NO_SCREENS_TITLE}
                            </h2>
                            <p>
                                {customer
                                    ? `You are signed in as ${auth.username}, a customer account. Customer screens are not part of this workstation.`
                                    : noScreensNote(auth.username)}
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
                                    onClick={handleSignOut}
                                >
                                    {SIGN_IN_AS_SOMEONE_ELSE}
                                </button>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        );
    }

    /**
     * The name the title bar prints, which is the person's own where the server knows one.
     *
     * The login is the fallback rather than the default, and it is reached twice: before the
     * answer arrives, and for a login with no customer record behind it, which is every analyst
     * today. The answer is checked against the login it was asked for, so a name fetched for the
     * previous session cannot be printed over the current one.
     */
    const signedInAs =
        me && me.username === auth.username && me.name ? me.name : auth.username;

    return (
        <FraudDesk
            username={auth.username}
            signedInAs={signedInAs}
            /* Narrowed one line above, so this is FRAUD_ANALYST and the header can say so. */
            role={auth.role}
            onLogout={handleSignOut}
        />
    );
}
