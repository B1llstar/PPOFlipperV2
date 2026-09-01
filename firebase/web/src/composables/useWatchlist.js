import { ref, watch, onUnmounted } from 'vue'
import { collection, onSnapshot } from 'firebase/firestore'
import { db } from '@/firebase/config'

/**
 * Live `accounts/{accountHash}/watchlist/{itemId}` collection — doc existence = membership
 * (PROPOSAL.md §4). Fields: itemId, addedAt.
 */
export function useWatchlist(accountHashRef) {
  const items = ref([]) // [{ itemId, addedAtMillis }]
  const loading = ref(true)
  const error = ref(null)

  let unsubscribe = null

  function attach(accountHash) {
    if (unsubscribe) {
      unsubscribe()
      unsubscribe = null
    }
    if (!accountHash) {
      items.value = []
      loading.value = false
      return
    }
    loading.value = true
    error.value = null
    unsubscribe = onSnapshot(
      collection(db, 'accounts', accountHash, 'watchlist'),
      (snapshot) => {
        items.value = snapshot.docs.map((docSnap) => {
          const data = docSnap.data()
          return {
            itemId: Number(docSnap.id),
            addedAtMillis: data.addedAt?.toMillis?.() ?? null,
          }
        })
        loading.value = false
      },
      (err) => {
        error.value = err
        loading.value = false
      },
    )
  }

  watch(accountHashRef, (newHash) => attach(newHash), { immediate: true })
  onUnmounted(() => unsubscribe?.())

  return { items, loading, error }
}
