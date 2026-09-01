import { ref, watch, onUnmounted } from 'vue'
import { collection, onSnapshot } from 'firebase/firestore'
import { db } from '@/firebase/config'

const WINDOW_MILLIS = 4 * 60 * 60 * 1000 // BuyLimitLedger.WINDOW_MILLIS — rolling 4h GE buy-limit window

/**
 * Live `accounts/{accountHash}/buyLimitLedger/{itemId}` collection — mirrors
 * PPOFlipperStarFirestoreClient.RemoteBuyLimitEntry: parallel `eventQuantities` /
 * `eventTimestampsMillis` arrays of raw purchase events (see BuyLimitLedger.java's javadoc for
 * why it's a per-fill event log, not an aggregate). Derives "quantity bought in the trailing 4h
 * window" client-side the same way BuyLimitLedger#quantityBoughtInWindow does server-side.
 */
export function useBuyLimitLedger(accountHashRef) {
  const entries = ref([]) // [{ itemId, quantityInWindow, events: [{quantity, timestampMillis}] }]
  const loading = ref(true)
  const error = ref(null)

  let unsubscribe = null

  function attach(accountHash) {
    if (unsubscribe) {
      unsubscribe()
      unsubscribe = null
    }
    if (!accountHash) {
      entries.value = []
      loading.value = false
      return
    }
    loading.value = true
    error.value = null
    unsubscribe = onSnapshot(
      collection(db, 'accounts', accountHash, 'buyLimitLedger'),
      (snapshot) => {
        const now = Date.now()
        entries.value = snapshot.docs.map((docSnap) => {
          const data = docSnap.data()
          const quantities = data.eventQuantities ?? []
          const timestamps = data.eventTimestampsMillis ?? []
          const events = quantities.map((quantity, i) => ({
            quantity,
            timestampMillis: timestamps[i] ?? 0,
          }))
          const quantityInWindow = events
            .filter((e) => now - e.timestampMillis < WINDOW_MILLIS)
            .reduce((sum, e) => sum + e.quantity, 0)

          return { itemId: Number(docSnap.id), quantityInWindow, events }
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

  return { entries, loading, error }
}
