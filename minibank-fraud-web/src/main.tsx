import '@shared/tokens/scale.css'
import '@shared/tokens/theme-desktop.css'
import React from 'react'
import ReactDOM from 'react-dom/client'
import { ErrorBoundary } from '@shared/ui/ErrorBoundary'
import App from './App'
import './styles/fraud.css'

/*
 * The boundary is outside App and inside StrictMode, which is where it can do its job: a throw
 * during render unmounted this whole tree and left a white window with no sentence and no control
 * on it, and the only way on was the browser's own reload. The name is passed because the panel is
 * the same component in both applications and a reader with both open has to know which one
 * stopped.
 */
ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
        <ErrorBoundary appName="Fraud workstation">
            <App />
        </ErrorBoundary>
    </React.StrictMode>,
)
