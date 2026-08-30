# PPOFlipperOpus web backend

Firebase project (`ppoflipperopus`) backing the web UI for GE Star V2: lets
you queue Grand Exchange buy/sell orders from a browser, which
`GeStarFirestoreSync` (in `plugins/ge-star-v2/`) picks up and executes
in-game. See that plugin's docs README for how the sync works from the
plugin side.

## Layout

- `functions/` — TypeScript Cloud Functions (`createOrder`, `cancelOrder`,
  `listOrders`), callable from the web UI. The only write path into the
  `orders` Firestore collection - Firestore rules deny direct client
  writes, so every order is validated server-side here before the plugin
  ever sees it.
- `public/index.html` — the web UI. Single static page, Firebase JS SDK
  loaded from CDN, email/password auth. No build step.
- `firestore.rules` — signed-in users can read only their own orders
  (`ownerUid == request.auth.uid`); all writes are denied (functions use
  the Admin SDK, which bypasses rules).
- `firestore.indexes.json` — composite indexes for the `ownerUid`+`createdAt`
  and `status`+`createdAt` queries the functions and plugin sync use.

## One-time project setup (do this before anything works end-to-end)

1. **Upgrade to the Blaze plan.** Cloud Functions requires it; Firestore
   rules/indexes and Hosting work on the free Spark plan, but function
   deploys fail with a billing error until this is done. Firebase console
   → Usage and billing → Modify plan.
2. **Enable Email/Password sign-in.** Firebase console → Authentication →
   Sign-in method → Email/Password → Enable. The web UI's sign-in form
   won't work until this is on (confirmed by testing against the live
   project - `OPERATION_NOT_ALLOWED` until enabled).
3. **Never commit the service account key**
   (`ppoflipperopus-firebase-adminsdk-*.json` at the repo root). It's
   already gitignored (`*firebase-adminsdk*.json` in the root
   `.gitignore`) - keep it that way. It grants full admin access to the
   entire Firebase project, not just this collection.

## Deploying

```bash
# From this directory:
firebase deploy --only firestore:rules,firestore:indexes --project ppoflipperopus
firebase deploy --only hosting --project ppoflipperopus
firebase deploy --only functions --project ppoflipperopus   # needs Blaze, see above
```

**Known Firebase CLI quirk on this machine:** the bundled/"firepit" npm
wrapper the CLI shells out to for the functions `predeploy` build step can
fail with `Cannot read properties of undefined (reading 'stdin')`. If that
happens, build manually first and redeploy - `npm run build` inside
`functions/` works fine standalone, it's just the CLI's own npm shell
invocation that's flaky:

```bash
cd functions && npm run build && cd ..
firebase deploy --only functions --project ppoflipperopus
```

## Local development

```bash
cd functions && npm install && npm run build
cd ..
firebase emulators:start --only functions,firestore,auth,hosting
```

Point `public/index.html`'s Firebase config at the emulator, or use the
Emulator UI (prints a local URL on start) to poke Firestore/Auth directly
while iterating.

## Schema contract

The `orders` collection schema is shared between three places that must
stay in sync:

- `functions/src/orders.ts` — the TypeScript types and validation
- `public/index.html` — reads/renders the same fields
- `plugins/ge-star-v2/.../GeStarFirestoreClient.java` and `GeStarOrder.java`
  — the plugin-side mirror

Fields: `ownerUid`, `action` (`BUY`/`SELL`), `itemName`, `quantity`,
`price`, `status` (`QUEUED`/`SUBMITTED`/`DONE`/`SKIPPED`/`FAILED`),
`quantityFilled`, `statusDetail`, `createdAt`, `updatedAt`. Changing any of
these requires updating all three places.
