// src/App.tsx
import { useEffect, useState } from 'react';
import './App.css';
import { setSessionExpiredHandler } from './api';
import NewPaymentPage from './NewPaymentPage';
import { WaitingAuthorizationsPage } from './WaitingAuthorizationsPage';
import FraudDeskPage from './FraudDeskPage';
import LoginDialog from './LoginDialog.tsx';

export type View = 'new-payment' | 'waiting-auth' | 'fraud-desk';
export type Role = 'CUSTOMER' | 'FRAUD_ANALYST' | 'OPERATIONS' | 'MANAGEMENT';

interface AuthState {
    username: string;
    role: Role;
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
                    setAuth(info);
                    // Initial view depends on the logged-in role
                    setView(info.role === 'FRAUD_ANALYST' ? 'fraud-desk' : 'new-payment');
                }}
            />
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

    return (
        <>
            {view === 'new-payment' && (
                <NewPaymentPage onNavigate={handleNavigate} />
            )}
            {view === 'waiting-auth' && (
                <WaitingAuthorizationsPage onNavigate={handleNavigate} />
            )}
            {view === 'fraud-desk' && (
                <FraudDeskPage />
            )}
        </>
    );
}

export default App;
