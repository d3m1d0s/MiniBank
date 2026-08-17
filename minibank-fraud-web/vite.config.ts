import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react-swc'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@shared': fileURLToPath(new URL('../frontend-shared', import.meta.url)),
    },
  },
  server: {
    port: 5174,
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
})
