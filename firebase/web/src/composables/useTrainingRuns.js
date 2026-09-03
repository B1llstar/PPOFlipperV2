import { ref, onUnmounted } from 'vue'
import { collection, collectionGroup, onSnapshot, orderBy, query } from 'firebase/firestore'
import { db } from '@/firebase/config'

// Live view of data/ppo/train.py's push_training_progress_to_firestore output -
// trainingRuns/{gitCommit} (one doc per training run, updated on every checkpoint) and each run's
// trainingRuns/{gitCommit}/checkpoints subcollection (one doc per checkpoint, the full history for
// a progress chart). Project-wide, not scoped to any account - a training run isn't tied to a
// specific RuneScape account.
export function useTrainingRuns() {
  const runs = ref([])
  const checkpointsByRun = ref({})
  const loading = ref(true)
  const error = ref(null)

  let unsubscribeRuns = null
  let unsubscribeCheckpoints = null

  function attach() {
    unsubscribeRuns = onSnapshot(
      collection(db, 'trainingRuns'),
      (snap) => {
        runs.value = snap.docs
          .map((d) => ({ gitCommit: d.id, ...d.data() }))
          // Most recently updated run first - the one someone watching right now almost
          // certainly cares about, whether it's still in progress or the latest to finish.
          .sort((a, b) => (b.updatedAt?.toMillis?.() ?? 0) - (a.updatedAt?.toMillis?.() ?? 0))
        loading.value = false
      },
      (err) => {
        error.value = err
        loading.value = false
      },
    )

    // A single collection-group listener across every run's checkpoints subcollection, rather
    // than one listener per run - simpler lifecycle, and the number of runs is small enough
    // (a handful, not hundreds) that filtering client-side by parent run id is cheap.
    unsubscribeCheckpoints = onSnapshot(
      query(collectionGroup(db, 'checkpoints'), orderBy('step', 'asc')),
      (snap) => {
        const byRun = {}
        for (const d of snap.docs) {
          // Path shape: trainingRuns/{gitCommit}/checkpoints/{step}
          const gitCommit = d.ref.parent.parent?.id
          if (!gitCommit) continue
          if (!byRun[gitCommit]) byRun[gitCommit] = []
          byRun[gitCommit].push({ step: Number(d.id), ...d.data() })
        }
        checkpointsByRun.value = byRun
      },
      (err) => {
        error.value = err
      },
    )
  }

  attach()
  onUnmounted(() => {
    unsubscribeRuns?.()
    unsubscribeCheckpoints?.()
  })

  return { runs, checkpointsByRun, loading, error }
}
