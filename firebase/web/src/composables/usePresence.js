import { ref, watch, onUnmounted, computed } from 'vue'
import { doc, onSnapshot } from 'firebase/firestore'
import { db } from '@/firebase/config'

// If no heartbeat has been written in this long, treat the plugin as offline. The plugin
// refreshes presence "periodically" (PPOFlipperStarFirestoreSync) — a generous multiple of any
// reasonable refresh interval avoids flickering "offline" between beats.
const STALE_AFTER_MILLIS = 3 * 60 * 1000

/** Live `accounts/{accountHash}/presence/heartbeat` doc — lastSeenMillis, pluginVersion. */
export function usePresence(accountHashRef) {
  const lastSeenMillis = ref(null)
  const pluginVersion = ref(null)
  const loading = ref(true)
  const error = ref(null)
  const nowMillis = ref(Date.now())

  const tick = setInterval(() => {
    nowMillis.value = Date.now()
  }, 5000)

  const isOnline = computed(
    () => lastSeenMillis.value != null && nowMillis.value - lastSeenMillis.value < STALE_AFTER_MILLIS,
  )

  let unsubscribe = null

  function attach(accountHash) {
    if (unsubscribe) {
      unsubscribe()
      unsubscribe = null
    }
    if (!accountHash) {
      lastSeenMillis.value = null
      pluginVersion.value = null
      loading.value = false
      return
    }
    loading.value = true
    error.value = null
    unsubscribe = onSnapshot(
      doc(db, 'accounts', accountHash, 'presence', 'heartbeat'),
      (docSnap) => {
        const data = docSnap.data()
        lastSeenMillis.value = data?.lastSeenMillis ?? null
        pluginVersion.value = data?.pluginVersion ?? null
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
    unsubscribe?.()
    clearInterval(tick)
  })

  return { lastSeenMillis, pluginVersion, isOnline, loading, error }
}
