// src/App.tsx
import { useState } from 'react';
import './App.css';
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

    // While the user is not logged in, show only the login dialog
    if (!auth) {
        return (
            <LoginDialog
                onLoggedIn={(info) => {
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
