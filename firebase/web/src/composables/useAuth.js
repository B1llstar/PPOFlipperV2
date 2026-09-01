import { ref, shallowRef } from 'vue'
import { signInWithPopup, signOut as firebaseSignOut, onAuthStateChanged } from 'firebase/auth'
import { auth, googleProvider, ALLOWLISTED_EMAILS } from '@/firebase/config'

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
 */
function isLikelyAllowlisted(firebaseUser) {
  return !!firebaseUser?.email && ALLOWLISTED_EMAILS.includes(firebaseUser.email)
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
