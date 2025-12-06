// src/App.tsx
import { useState } from 'react';
import './App.css';
import NewPaymentPage from './NewPaymentPage';
import { WaitingAuthorizationsPage } from './WaitingAuthorizationsPage';
import FraudDeskPage from './FraudDeskPage';

export type View = 'new-payment' | 'waiting-auth' | 'fraud-desk';

function App() {
    const [view, setView] = useState<View>('new-payment');

    return (
        <>
            {view === 'new-payment' && (
                <NewPaymentPage onNavigate={setView} />
            )}
            {view === 'waiting-auth' && (
                <WaitingAuthorizationsPage onNavigate={setView} />
            )}
            {view === 'fraud-desk' && (
                <FraudDeskPage onNavigate={setView} />
            )}
        </>
    );
}

export default App;
