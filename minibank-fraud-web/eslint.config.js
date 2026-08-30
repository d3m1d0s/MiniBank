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
       * At error, which is what the note that stood here asked for: it was a warning only while
       * labels in these screens still named a field without being attached to it, and it said to
       * raise it in the same change that attached the last one. That change has happened, every
       * control on the desk and in the sign in box carries its own name, and the gate now keeps
       * them attached. The one word that names two boxes, the amount filter, is not a label at
       * all: the two boxes carry the two names the customer application prints above its own.
       *
       * The rest of the accessibility set, no-autofocus included, is at its recommended level and
       * green. The sign in box does put the cursor in the username field, deliberately, and does
       * it in an effect rather than with the attribute the rule forbids; see Login.tsx for why
       * that is the exception the rule is not written for rather than a way around it.
       */
      'jsx-a11y/label-has-associated-control': 'error',
    },
  },
])
