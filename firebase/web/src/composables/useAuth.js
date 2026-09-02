import { ref, shallowRef } from 'vue'
import { signInWithPopup, signOut as firebaseSignOut, onAuthStateChanged } from 'firebase/auth'
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
    signOut,
    isLikelyAllowlisted,
  }
}
