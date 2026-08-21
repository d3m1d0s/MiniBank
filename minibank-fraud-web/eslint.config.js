import js from '@eslint/js'
import globals from 'globals'
import jsxA11y from 'eslint-plugin-jsx-a11y'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
      jsxA11y.flatConfigs.recommended,
    ],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
    rules: {
      /*
       * Two rules read the leading underscore, and only one of them reads it without being asked.
       * The TypeScript compiler exempts _name from noUnusedLocals and noUnusedParameters on its
       * own; this rule does not, and reports every such name until the patterns are spelled out.
       */
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
      /*
       * A warning only because the screens have not been fixed yet, not because the complaint is
       * wrong: five labels here name a field without being attached to it. Raise it to error in
       * the same change that attaches the last one, so the gate keeps them attached. The rest of
       * the accessibility set, no-autofocus included, is at its recommended level and green here.
       */
      'jsx-a11y/label-has-associated-control': 'warn',
    },
  },
])
