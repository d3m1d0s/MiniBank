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

    // Пока пользователь не залогинен — показываем только диалог логина
    if (!auth) {
        return (
            <LoginDialog
                onLoggedIn={(info) => {
                    setAuth(info);
                    // стартовый экран зависит от роли
                    setView(info.role === 'FRAUD_ANALYST' ? 'fraud-desk' : 'new-payment');
                }}
            />
        );
    }

    // Централизованная навигация с учётом роли
    const handleNavigate = (next: View) => {
        // пример: аналитику запрещаем ходить на клиентские экраны
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
                <FraudDeskPage onNavigate={handleNavigate} />
            )}
        </>
    );
}

export default App;
