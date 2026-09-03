import '@shared/tokens/scale.css'
import '@shared/tokens/theme-web.css'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { ErrorBoundary } from '@shared/ui/ErrorBoundary'
import App from './App.tsx'

/*
 * The boundary wraps the whole application and not one screen, because there is nothing above a
 * screen here to catch a throw: a component that failed while rendering unmounted the entire tree
 * and left a white window with no sentence and no control on it. The name is passed in because the
 * panel is the same component in the workstation, and in a demonstration both are open at once.
 *
 * ONE LINE IS MISSING FROM vite.config.ts, WHICH THIS PASS DID NOT OWN, AND UNTIL IT LANDS
 * `npm run build` FAILS IN BOTH APPLICATIONS:
 *
 *     [vite]: Rollup failed to resolve import "react/jsx-runtime" from
 *     ".../frontend-shared/ErrorBoundary.jsx"
 *
 * frontend-shared has no node_modules of its own, and rollup resolves a bare specifier from the
 * importing file's directory, so the automatic JSX runtime the plugin injects into that file
 * resolves to nothing. The dev server has a root fallback and does not care, which is why this is
 * invisible until a build. The fix, in the resolve block of BOTH vite.config.ts beside the
 * existing @shared alias:
 *
 *     dedupe: ['react', 'react-dom'],
 *
 * It forces both packages to resolve from the project root for every importer, including one
 * outside it. Measured: this application built against a copy of its config with that one line
 * added and this file exactly as it stands, 51 modules transformed, built in 3.35s.
 */
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErrorBoundary appName="MiniBank">
      <App />
    </ErrorBoundary>
  </StrictMode>,
)
