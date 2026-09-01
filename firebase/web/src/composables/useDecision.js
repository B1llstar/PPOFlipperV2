import { ref, watch, onUnmounted } from 'vue'
import { doc, onSnapshot } from 'firebase/firestore'
import { db } from '@/firebase/config'

/**
 * Live `accounts/{accountHash}/decision/request` and `.../decision/response` docs — the
 * model<->plugin transport (PROPOSAL.md §3.6/§4). Both are single, transient, overwritten-every-
 * tick documents, not append-only history, so a plain doc listener (not a collection query) is
 * all that's needed.
 *
 * request fields: tickId, items[] (DecisionRequestItem shape), writtenAt
 * response fields: tickId, actions[] ({itemId, action, quantity, price, confidence}), checkpointVersion, answeredAt
 */
export function useDecision(accountHashRef) {
  const request = ref(null)
  const response = ref(null)
  const loading = ref(true)
  const error = ref(null)

  let unsubRequest = null
  let unsubResponse = null

  function attach(accountHash) {
    unsubRequest?.()
    unsubResponse?.()
    unsubRequest = null
    unsubResponse = null

    if (!accountHash) {
      request.value = null
      response.value = null
      loading.value = false
      return
    }

    loading.value = true
    error.value = null

    unsubRequest = onSnapshot(
      doc(db, 'accounts', accountHash, 'decision', 'request'),
      (docSnap) => {
        request.value = docSnap.exists() ? docSnap.data() : null
        loading.value = false
      },
      (err) => {
        error.value = err
        loading.value = false
      },
    )

    unsubResponse = onSnapshot(
      doc(db, 'accounts', accountHash, 'decision', 'response'),
      (docSnap) => {
        response.value = docSnap.exists() ? docSnap.data() : null
        loading.value = false
      },
      (err) => {
        error.value = err
        loading.value = false
      },
    )
  }

  watch(accountHashRef, (newHash) => attach(newHash), { immediate: true })
  onUnmounted(() => {
    unsubRequest?.()
    unsubResponse?.()
  })

  return { request, response, loading, error }
}
