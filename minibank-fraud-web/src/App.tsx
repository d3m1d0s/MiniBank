import { useEffect, useState } from 'react';
import Login from './Login';
import FraudDesk from './FraudDesk';
import { logoutSession, setSessionExpiredHandler } from './api';

type Role = 'CUSTOMER' | 'FRAUD_ANALYST' | 'OPERATIONS' | 'MANAGEMENT';

interface AuthState {
    username: string;
    role: Role;
    customerId: number | null;
}

export default function App() {
    const [auth, setAuth] = useState<AuthState | null>(null);
    const [signedOutReason, setSignedOutReason] = useState<string | null>(null);

    // This app already had the "return to sign-in" mechanism, on the Logout buttons below.
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
                    setAuth(info);
                }}
            />
        );
    }

    if (auth.role !== 'FRAUD_ANALYST') {
        return (
            <div className="shell">
                <div className="window">
                    <div className="titlebar">
                        <div className="title">MiniBank Fraud</div>
                        <button className="btn" onClick={() => { logoutSession(); setAuth(null); }}>Logout</button>
                    </div>
                    <div className="content">
                        <div className="panel">
                            <h2>Access denied</h2>
                            <p>This UI is for FRAUD_ANALYST role.</p>
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
