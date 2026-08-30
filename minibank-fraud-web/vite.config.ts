import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
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
    port: 5174,
    /*
     * The port is the address, not a preference. Without this vite answers an occupied 5174 by
     * taking 5175 and saying so in one line of its own output, and everything that names this
     * application by number then points at whatever else took the port: the api proxy below, the
     * screenshots, and the second window an analyst opens beside the customer one. Refusing to
     * start is the honest answer, because the thing that has to be fixed is the process already
     * holding the port.
     */
    strictPort: true,
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
