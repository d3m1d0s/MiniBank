// vite.config.ts
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react-swc'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@shared': fileURLToPath(new URL('../frontend-shared', import.meta.url)),
    },
    /*
     * ../frontend-shared has no node_modules and there is none above it either, so rollup, which
     * resolves from the importing file's own directory, cannot find react/jsx-runtime for the one
     * component in the shared layer that renders. Without this the bundle step fails and only the
     * dev server works, because vite serves that file through this package's graph.
     */
    dedupe: ['react', 'react-dom'],
  },
  server: {
    port: 5173,
    /*
     * The dev server refuses to serve a file outside the project root. A module
     * already in the graph slips through, but a stylesheet imported for the first
     * time from ../frontend-shared answers 403, so that directory has to be named.
     */
    fs: {
      allow: [
        fileURLToPath(new URL('.', import.meta.url)),
        fileURLToPath(new URL('../frontend-shared', import.meta.url)),
      ],
    },
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  /*
   * The runner is declared once, here, and it is the only vitest in the tree. The workstation is
   * named as a project so that its cases run through this install instead of a second one; the
   * shared layer is named in include because the default glob is rooted in this package and a
   * pattern that does not spell out ../frontend-shared can never leave it.
   */
  test: {
    projects: ['.', '../minibank-fraud-web'],
    include: [
      'src/**/*.test.{ts,tsx}',
      '../frontend-shared/**/*.test.{ts,tsx}',
    ],
  },
})
