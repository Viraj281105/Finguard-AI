# FinGuard AI — frontend

React 19 · TypeScript 5.9 · Vite 7 · Tailwind CSS 3 · React Router 7 · axios · Vitest + Testing Library.
The app only talks to the Spring Boot API ([docs/API.md](../docs/API.md)); it holds no business data of its own.

## Commands

```powershell
npm install
npm run dev          # http://localhost:5173
npm run typecheck    # tsc -b
npm run lint         # eslint
npm test             # vitest run   (49 tests; npm run test:watch for watch mode)
npm run build        # typecheck + production build into dist/
```

Configuration: `VITE_API_BASE_URL` (default `http://localhost:8080/api`) — copy `.env.example` to `.env.local`.

## Structure

```
src/
  app/         App (routes) · PrivateRoute (token present and not expired)
  shared/      api/ (axios client, 401 handling) · auth/ (token storage) · hooks/ (useResource)
               lib/ (formatCurrency, formatDate) · ui/ (spinner, error banner, modal parts, accent styles)
  features/    auth · transactions · investments · loans · statement-import · dashboard
               (each: api.ts, types.ts, components/pages, tests)
  test/        Vitest setup
```
Conventions and the planned changes (server-side summary, charts, category review) are in
[docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) and [docs/ROADMAP.md](../docs/ROADMAP.md).

## Notes
- The session token is stored in `localStorage` under the key `token` (planned to change — [docs/DECISIONS.md](../docs/DECISIONS.md) D-01).
- Dashboard totals are currently computed in the browser (`features/dashboard/summary.ts`); this moves to the backend in v0.6.0.
- Tested with jsdom only; layout, responsiveness and accessibility have not been checked in a real browser.
