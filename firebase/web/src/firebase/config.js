// Firebase client SDK initialization for the PPOFlipperStar dashboard.
//
// ─────────────────────────────────────────────────────────────────────────────────────────────
// MANUAL SETUP REQUIRED — read this before deploying
// ─────────────────────────────────────────────────────────────────────────────────────────────
// The values below are filled in from the EXISTING web app config already registered against
// the `ppoflipperopus` Firebase project (found in the old firebase/public/index.html, which this
// dashboard replaces) — apiKey/authDomain/projectId/storageBucket/messagingSenderId are
// project-scoped, not secret, and not tied to any specific registered "app", so reusing them here
// is valid and this dashboard will talk to the correct Firestore project/database out of the box.
//
// The one thing that IS specific to a single registered Web App is `appId`. It has been left
// pointed at the same `appId` as the old page for now, which will work (Firebase does not
// actually enforce that an appId's usage matches its registered platform/name for basic
// Auth+Firestore usage — it is mainly used for Analytics/Performance Monitoring attribution) —
// but the clean/correct thing to do is register a SEPARATE Web App for this dashboard so it has
// its own name in the console and its own Analytics stream:
//   Firebase console → ppoflipperopus project → ⚙ Project Settings → General tab →
//   "Your apps" section → "Add app" → Web (</>) → name it e.g. "PPOFlipperStar Dashboard" →
//   copy the resulting `firebaseConfig` object's `appId` in below.
//
// You ALSO must do these one-time console steps before sign-in will work at all:
//   1. Authentication → Sign-in method → enable the "Google" provider (it is not enabled by
//      default on a project that only had email/password auth, which is what the old page used).
//   2. Authentication → Settings → Authorized domains → make sure the Hosting domain this app
//      deploys to (ppoflipperopus.web.app / ppoflipperopus.firebaseapp.com, and any custom domain)
//      is present — Hosting's own default domains are added automatically, but double-check if
//      you use a custom domain.
//   3. Deploy the updated firestore.rules in this same change (`firebase deploy --only
//      firestore:rules`) — without it, sign-in will succeed but every Firestore read will fail
//      with permission-denied for EVERYONE, allowlisted or not, since the rules is what actually
//      grants dashboard read access.
// ─────────────────────────────────────────────────────────────────────────────────────────────
import { initializeApp } from 'firebase/app'
import { getAuth, GoogleAuthProvider } from 'firebase/auth'
import { getFirestore } from 'firebase/firestore'

const firebaseConfig = {
  apiKey: 'AIzaSyAC3KY6nPtjlhVy3XER1JN2gIgX3e7VZcs',
  authDomain: 'ppoflipperopus.firebaseapp.com',
  projectId: 'ppoflipperopus',
  storageBucket: 'ppoflipperopus.firebasestorage.app',
  messagingSenderId: '631485979464',
  // See the setup note above: this reuses the old page's registered Web App id. Replace with a
  // freshly-registered Web App's appId for a cleaner separation if/when convenient — not required
  // for the dashboard to function correctly.
  appId: '1:631485979464:web:b1696588c9ebc5eb7df17d',
}

export const firebaseApp = initializeApp(firebaseConfig)
export const auth = getAuth(firebaseApp)
export const db = getFirestore(firebaseApp)
export const googleProvider = new GoogleAuthProvider()

/**
 * Emails allowed to read PPOFlipperStar's account data through this dashboard. This is a
 * client-side convenience list ONLY (used to short-circuit an obviously-denied sign-in with a
 * clear message before ever hitting Firestore) — it is NOT what actually enforces access. The
 * real enforcement is the `isAllowlistedDashboardViewer()` check in firebase/firestore.rules,
 * which must be updated (and redeployed) if this list ever changes, or a signed-in-but-denied
 * user will just see permission-denied errors on every read instead of any actual data leaking.
 */
export const ALLOWLISTED_EMAILS = ['billborkowski7@gmail.com', 'crigne4lyfe@gmail.com']

/**
 * Second, looser allowlist path mirroring firestore.rules' matchesAllowlistedSubstring(): any
 * signed-in email CONTAINING one of these substrings (case-insensitive), in ANY domain, is
 * treated as allowlisted — not an exact-match list. This is a deliberate, weaker-security
 * tradeoff versus ALLOWLISTED_EMAILS above: since Google email local-parts are entirely
 * self-chosen, anyone who can create an account containing this substring gets full read access
 * to every account's portfolio/trade history/decision state. Keep entries here distinctive
 * enough that an accidental/unintended match is implausible. Must stay in sync with
 * firestore.rules' own substring list (same reasoning as ALLOWLISTED_EMAILS above — this is only
 * a client-side convenience check, Firestore's rule is the real gate).
 */
export const ALLOWLISTED_EMAIL_SUBSTRINGS = ['cyanidebyte']
