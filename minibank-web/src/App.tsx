// src/App.tsx
import { useState } from 'react';
import './App.css';
import NewPaymentPage from './NewPaymentPage';
import {WaitingAuthorizationsPage} from './WaitingAuthorizationsPage';

export type View = 'new-payment' | 'waiting-auth';

function App() {
    const [view, setView] = useState<View>('new-payment');

    return (
        <>
            {view === 'new-payment' && <NewPaymentPage onNavigate={setView} />}
            {view === 'waiting-auth' && <WaitingAuthorizationsPage onNavigate={setView} />}
        </>
    );
}

export default App;
