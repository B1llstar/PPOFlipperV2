import { ref, computed, onUnmounted, watch } from 'vue'

// Smoothly interpolates a training run's step counter BETWEEN real Firestore checkpoint updates,
// so the dashboard's progress bar/step count visibly ticks forward in real time without any extra
// Firestore reads - every checkpoint arrives every --checkpoint-freq steps (minutes apart for a
// real run), and without this the UI would sit dead-still between them, which reads as "did this
// stall?" even while training is actively running on the GPU.
//
// The rate (steps/second) is derived from the two most recent checkpoints already in hand (no new
// data fetched) - a real, measured rate for this specific run, not a guess. Interpolation is
// clamped at the next checkpoint's own step (or totalTimesteps, whichever is smaller) so a slower
// checkpoint than the last interval never makes the estimate visibly overshoot past what will
// actually be confirmed - it just holds at that ceiling until the real update lands and a fresh
// rate takes over.
export function useInterpolatedStep(runRef, checkpointsRef) {
  const nowMillis = ref(Date.now())
  let ticker = null

  function startTicking() {
    if (ticker) return
    // 1s cadence - fast enough to feel alive, far too infrequent to be a performance concern for
    // a single setInterval driving a few reactive numbers.
    ticker = setInterval(() => {
      nowMillis.value = Date.now()
    }, 1000)
  }
  function stopTicking() {
    clearInterval(ticker)
    ticker = null
  }

  // Only ticks while there's an in-progress run to interpolate for - no point burning a timer on
  // a finished run's dashboard view, or before any run exists yet.
  watch(
    () => runRef.value && (runRef.value.progressPct ?? 0) < 100,
    (shouldTick) => (shouldTick ? startTicking() : stopTicking()),
    { immediate: true },
  )
  onUnmounted(stopTicking)

  const stepsPerSecond = computed(() => {
    const checkpoints = checkpointsRef.value
    if (!checkpoints || checkpoints.length < 2) return 0
    const a = checkpoints[checkpoints.length - 2]
    const b = checkpoints[checkpoints.length - 1]
    const stepDelta = b.step - a.step
    const timeDeltaSeconds = ((b.recordedAt?.toMillis?.() ?? 0) - (a.recordedAt?.toMillis?.() ?? 0)) / 1000
    if (stepDelta <= 0 || timeDeltaSeconds <= 0) return 0
    return stepDelta / timeDeltaSeconds
  })

  const interpolatedStep = computed(() => {
    const run = runRef.value
    if (!run) return 0
    const latestStep = run.latestStep ?? 0
    const rate = stepsPerSecond.value
    if (rate <= 0) return latestStep

    const anchorMillis = run.updatedAt?.toMillis?.() ?? nowMillis.value
    const elapsedSeconds = Math.max(0, (nowMillis.value - anchorMillis) / 1000)
    const estimated = latestStep + rate * elapsedSeconds

    // Never estimate past the run's own declared total - once the real next checkpoint lands,
    // latestStep/updatedAt/the rate all refresh together and this ceiling moves with them.
    const ceiling = run.totalTimesteps ?? Infinity
    return Math.min(estimated, ceiling)
  })

  const interpolatedProgressPct = computed(() => {
    const run = runRef.value
    if (!run || !run.totalTimesteps) return run?.progressPct ?? 0
    return Math.min(100, (100 * interpolatedStep.value) / run.totalTimesteps)
  })

  return { interpolatedStep, interpolatedProgressPct, stepsPerSecond }
}
