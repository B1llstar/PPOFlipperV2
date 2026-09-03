import { ref, shallowRef } from 'vue'
import {
  signInWithPopup,
  signInWithEmailAndPassword,
  createUserWithEmailAndPassword,
  signOut as firebaseSignOut,
  onAuthStateChanged,
} from 'firebase/auth'
import { auth, googleProvider, ALLOWLISTED_EMAILS, ALLOWLISTED_EMAIL_SUBSTRINGS } from '@/firebase/config'

// Module-level (singleton) state, shared by every component that calls useAuth() — one auth
// listener for the whole app, not one per component instance.
const user = shallowRef(null)
const authReady = ref(false)
const authError = ref(null)
const signingIn = ref(false)

onAuthStateChanged(
  auth,
  (firebaseUser) => {
    user.value = firebaseUser
    authReady.value = true
  },
  (err) => {
    authError.value = err
    authReady.value = true
  },
)

async function signInWithGoogle() {
  authError.value = null
  signingIn.value = true
  try {
    await signInWithPopup(auth, googleProvider)
  } catch (err) {
    // A closed popup isn't a real error worth surfacing.
    if (err?.code !== 'auth/popup-closed-by-user' && err?.code !== 'auth/cancelled-popup-request') {
      authError.value = err
    }
  } finally {
    signingIn.value = false
  }
}

/**
 * Email/password sign-in, alongside the existing Google option. Any email can attempt sign-in or
 * sign-up here — Firebase Auth itself has no concept of the dashboard's allowlist — exactly like
 * signInWithGoogle above: the post-login allowlist screens (App.vue) are what actually deny access
 * for a non-allowlisted account, and firestore.rules is the real, server-side enforcement behind
 * that. This function just authenticates; it grants nothing on its own.
 */
async function signInWithEmail(email, password) {
  authError.value = null
  signingIn.value = true
  try {
    await signInWithEmailAndPassword(auth, email, password)
  } catch (err) {
    authError.value = err
  } finally {
    signingIn.value = false
  }
}

/** Creates a new email/password account and signs into it immediately - see signInWithEmail's doc on the allowlist not being enforced here. */
async function signUpWithEmail(email, password) {
  authError.value = null
  signingIn.value = true
  try {
    await createUserWithEmailAndPassword(auth, email, password)
  } catch (err) {
    authError.value = err
  } finally {
    signingIn.value = false
  }
}

async function signOut() {
  await firebaseSignOut(auth)
}

/**
 * Client-side convenience check ONLY (see config.js's ALLOWLISTED_EMAILS doc) — a quick, clear
 * "you don't have access" message without waiting on a Firestore permission-denied round trip.
 * Firestore's own rules are the real gate; this can never be relied on for security.
 *
 * Two paths, mirroring firestore.rules' isAllowlistedDashboardViewer(): an exact match against
 * ALLOWLISTED_EMAILS, or a case-insensitive substring match against ALLOWLISTED_EMAIL_SUBSTRINGS
 * (any domain) — see that constant's doc in config.js for the security tradeoff this accepts.
 */
function isLikelyAllowlisted(firebaseUser) {
  const email = firebaseUser?.email
  if (!email) return false
  if (ALLOWLISTED_EMAILS.includes(email)) return true
  const lowercaseEmail = email.toLowerCase()
  return ALLOWLISTED_EMAIL_SUBSTRINGS.some((substring) => lowercaseEmail.includes(substring.toLowerCase()))
}

export function useAuth() {
  return {
    user,
    authReady,
    authError,
    signingIn,
    signInWithGoogle,
    signInWithEmail,
    signUpWithEmail,
    signOut,
    isLikelyAllowlisted,
  }
}
