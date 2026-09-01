import { ref, computed } from 'vue'
import { collectionGroup, getDocs, query } from 'firebase/firestore'
import { db } from '@/firebase/config'

/**
 * Discovers every RuneScape account hash the signed-in user is allowed to see, by running a
 * Firestore collectionGroup query across every account's `presence` subcollection (each of which
 * holds exactly one fixed-id document, `heartbeat`) and reading the account hash back out of each
 * result's document path.
 *
 * IMPORTANT — why this groups on "presence" and not "heartbeat":
 * The natural-looking rule/query would group on "heartbeat" (the actual leaf document's
 * collection... except heartbeat is a DOCUMENT id, not a collection id — the collection is
 * `accounts/{hash}/presence`, and `heartbeat` is the fixed name of the one document inside it).
 * `collectionGroup(db, 'presence')` groups on the actual subcollection id, which is what a
 * Firestore collectionGroup query needs — this was verified empirically against the Firestore
 * emulator before shipping (see firebase/firestore.rules' comment on the matching security rule
 * for the full story: querying by the wrong identifier silently returns zero results instead of
 * erroring, which is exactly the kind of subtle bug this account-discovery approach could hide
 * without that verification).
 *
 * Requires firebase/firestore.rules' `match /{path=**}/presence/{doc}` collection-group rule to
 * be deployed — without it this query fails with permission-denied for every user, even an
 * allowlisted one, since collection-group queries are evaluated against that separate rule, not
 * the direct-path `accounts/{accountHash}/presence/heartbeat` rule.
 */
export function useAccounts() {
  const accounts = ref([]) // [{ accountHash: string, lastSeenMillis: number, pluginVersion: string }]
  const loading = ref(false)
  const error = ref(null)
  const selectedAccountHash = ref(null)

  const selectedAccount = computed(
    () => accounts.value.find((a) => a.accountHash === selectedAccountHash.value) ?? null,
  )

  async function discoverAccounts() {
    loading.value = true
    error.value = null
    try {
      const snapshot = await getDocs(query(collectionGroup(db, 'presence')))
      const discovered = snapshot.docs
        .filter((docSnap) => docSnap.id === 'heartbeat')
        .map((docSnap) => {
          // Path shape: accounts/{accountHash}/presence/heartbeat
          const segments = docSnap.ref.path.split('/')
          const accountHash = segments[1]
          const data = docSnap.data()
          return {
            accountHash,
            lastSeenMillis: typeof data.lastSeenMillis === 'number' ? data.lastSeenMillis : 0,
            pluginVersion: data.pluginVersion ?? 'unknown',
          }
        })

      discovered.sort((a, b) => b.lastSeenMillis - a.lastSeenMillis)
      accounts.value = discovered

      if (discovered.length > 0) {
        selectedAccountHash.value = discovered[0].accountHash
      }
    } catch (err) {
      error.value = err
    } finally {
      loading.value = false
    }
  }

  function selectAccount(accountHash) {
    selectedAccountHash.value = accountHash
  }

  return {
    accounts,
    loading,
    error,
    selectedAccountHash,
    selectedAccount,
    discoverAccounts,
    selectAccount,
  }
}
