import { ref, watch, onUnmounted } from 'vue'
import { collection, onSnapshot } from 'firebase/firestore'
import { db } from '@/firebase/config'

/**
 * Live `accounts/{accountHash}/portfolio/{itemId}` collection — mirrors
 * PPOFlipperStarFirestoreClient.RemotePortfolioEntry / PortfolioManager's CostBasisEntry exactly:
 * quantityHeld, averageCost, totalCostBasis, realizedProfit, weightedAcquisitionTimestampMillis.
 *
 * accountHashRef may be a ref that changes (account picker) — the snapshot listener is torn down
 * and re-attached whenever it does.
 */
export function usePortfolio(accountHashRef) {
  const positions = ref([]) // [{ itemId, quantityHeld, averageCost, totalCostBasis, realizedProfit, weightedAcquisitionTimestampMillis }]
  const loading = ref(true)
  const error = ref(null)

  let unsubscribe = null

  function attach(accountHash) {
    if (unsubscribe) {
      unsubscribe()
      unsubscribe = null
    }
    if (!accountHash) {
      positions.value = []
      loading.value = false
      return
    }
    loading.value = true
    error.value = null
    unsubscribe = onSnapshot(
      collection(db, 'accounts', accountHash, 'portfolio'),
      (snapshot) => {
        positions.value = snapshot.docs.map((docSnap) => {
          const data = docSnap.data()
          return {
            itemId: Number(docSnap.id),
            quantityHeld: data.quantityHeld ?? 0,
            averageCost: data.averageCost ?? 0,
            totalCostBasis: data.totalCostBasis ?? 0,
            realizedProfit: data.realizedProfit ?? 0,
            weightedAcquisitionTimestampMillis: data.weightedAcquisitionTimestampMillis ?? 0,
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

  return { positions, loading, error }
}
