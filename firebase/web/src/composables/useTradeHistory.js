import { ref, watch, onUnmounted } from 'vue'
import { collection, onSnapshot, orderBy, query as fsQuery, where, limit as fsLimit } from 'firebase/firestore'
import { db } from '@/firebase/config'

/**
 * Live `accounts/{accountHash}/tradeHistory/{autoId}` collection — the immutable, append-only
 * fill log (PROPOSAL.md §4). Fields exactly as written by
 * PPOFlipperStarFirestoreClient#appendTradeHistory: action, itemId, itemName, quantity,
 * pricePerUnit, totalGp, timestampMillis, recordedAt.
 *
 * Supports optional itemId/action filters (server-side, via Firestore `where` — see
 * firestore.indexes.json's composite indexes for tradeHistory) and a row cap so a long-lived
 * account's history doesn't try to pull down an unbounded collection into the browser at once.
 * Filtering/sorting beyond that (date-range, column sort) is done client-side over the capped
 * result set in the Trade History view, which is simpler than a matrix of composite indexes for
 * every filter combination and plenty fast at this collection's realistic size.
 */
export function useTradeHistory(accountHashRef, options = {}) {
  const { rowLimit = 500 } = options

  const trades = ref([])
  const loading = ref(true)
  const error = ref(null)

  const itemIdFilter = ref(null) // number | null
  const actionFilter = ref(null) // 'BUY' | 'SELL' | null

  let unsubscribe = null

  function buildQuery(accountHash) {
    const col = collection(db, 'accounts', accountHash, 'tradeHistory')
    const constraints = []
    if (itemIdFilter.value != null) {
      constraints.push(where('itemId', '==', itemIdFilter.value))
    } else if (actionFilter.value) {
      constraints.push(where('action', '==', actionFilter.value))
    }
    constraints.push(orderBy('timestampMillis', 'desc'))
    constraints.push(fsLimit(rowLimit))
    return fsQuery(col, ...constraints)
  }

  function attach() {
    unsubscribe?.()
    unsubscribe = null

    const accountHash = accountHashRef.value
    if (!accountHash) {
      trades.value = []
      loading.value = false
      return
    }

    loading.value = true
    error.value = null
    unsubscribe = onSnapshot(
      buildQuery(accountHash),
      (snapshot) => {
        trades.value = snapshot.docs.map((docSnap) => {
          const data = docSnap.data()
          return {
            id: docSnap.id,
            action: data.action,
            itemId: data.itemId,
            itemName: data.itemName,
            quantity: data.quantity,
            pricePerUnit: data.pricePerUnit,
            totalGp: data.totalGp,
            timestampMillis: data.timestampMillis,
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

  function setItemIdFilter(itemId) {
    itemIdFilter.value = itemId
    actionFilter.value = null // Firestore can't combine two different-field equality filters here without another composite index; UI only offers one at a time.
    attach()
  }

  function setActionFilter(action) {
    actionFilter.value = action
    itemIdFilter.value = null
    attach()
  }

  function clearFilters() {
    itemIdFilter.value = null
    actionFilter.value = null
    attach()
  }

  watch(accountHashRef, () => attach(), { immediate: true })
  onUnmounted(() => unsubscribe?.())

  return {
    trades,
    loading,
    error,
    itemIdFilter,
    actionFilter,
    setItemIdFilter,
    setActionFilter,
    clearFilters,
  }
}
