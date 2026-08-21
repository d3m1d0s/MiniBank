import js from '@eslint/js'
import globals from 'globals'
import jsxA11y from 'eslint-plugin-jsx-a11y'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

/*
 * The rules, written once and given to both trees below.
 *
 * There is one set for the application and the shared directory and not two, because the shared
 * directory is where the two applications agree with each other: a rule that holds in src/ and
 * not in frontend-shared/ would be a rule the code both platforms read is exempt from.
 */
const rules = {
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
   * Both at error, which is what the note that stood here asked for. They were warnings only
   * while the screens still had sites to repair, and the note said to raise them in the same
   * change that repaired the last one. That change has happened at both: every label in this
   * application is tied to the control it names, and the sign in box puts the cursor in the
   * username field from an effect rather than from the attribute this rule forbids. See
   * LoginDialog.tsx for why that one screen is the case the rule is not written for. The
   * workstation holds both at the same level, and a rule at error on one platform and at
   * warning on the other is one platform's screens quietly exempt.
   */
  'jsx-a11y/label-has-associated-control': 'error',
  'jsx-a11y/no-autofocus': 'error',
}

const extend = [
  js.configs.recommended,
  tseslint.configs.recommended,
  reactHooks.configs.flat.recommended,
  reactRefresh.configs.vite,
  jsxA11y.flatConfigs.recommended,
]

const languageOptions = {
  ecmaVersion: 2020,
  globals: globals.browser,
}

/*
 * One block, and it is written to serve two invocations rather than two directories.
 *
 * The shared directory beside this one, the field lists, the glossary, the error table, the money
 * and date formatters and the error boundary, was linted by nothing at all. A flat configuration
 * speaks for its own directory and below: `eslint .` here stopped at the edge of this application
 * and `eslint ../frontend-shared` was refused outright, as being outside the base path. The code
 * the two platforms are supposed to agree through was the only code neither gate held, which is
 * the wrong way round.
 *
 * A config object cannot reach upward out of that base path, `basePath` narrows and never
 * escapes, so the reach is bought by moving the invocation instead of the configuration: the
 * `lint` script runs ESLint a second time from the directory above with this file passed as
 * `--config`, which puts the base path at the worktree root and brings the shared directory
 * inside it. Same rules, same plugins, one file: what the application is held to is what the code
 * it imports is held to, and there is no second set to drift.
 *
 * `js` and `jsx` are in the pattern for that second run, since ErrorBoundary is written in JSX.
 * In the first run they add this file and nothing else.
 */
export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx,js,jsx}'],
    extends: extend,
    languageOptions,
    rules,
  },
])
