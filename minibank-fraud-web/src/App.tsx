import { useState } from 'react';
import Login from './Login';
import FraudDesk from './FraudDesk';
import { logoutSession } from './api';

type Role = 'CUSTOMER' | 'FRAUD_ANALYST' | 'OPERATIONS' | 'MANAGEMENT';

interface AuthState {
    username: string;
    role: Role;
    customerId: number | null;
}

export default function App() {
    const [auth, setAuth] = useState<AuthState | null>(null);

    if (!auth) {
        return <Login onLoggedIn={setAuth} />;
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
