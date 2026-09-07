<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'

// A live "is the model actually ticking" pulse - the Dashboard's existing "Latest model decision"
// table already shows the most recent tick's content, but says nothing about cadence: is a new
// tick landing every few seconds like normal, or has it quietly stalled? This answers that at a
// glance, from data the Dashboard already subscribes to (useDecision's response doc) with no new
// Firestore reads.
const props = defineProps({
  // answeredAt is a Firestore Timestamp (has .toMillis()) once hydrated, or null before the first tick.
  answeredAtMillis: { type: Number, default: null },
})

// Rolling estimate of the gap between ticks, derived purely from consecutive answeredAt values
// seen so far - no assumption about the plugin's own decisionTickIntervalSeconds config, since
// this component has no access to it and the observed cadence is the more honest signal anyway.
const lastSeenAt = ref(props.answeredAtMillis)
const intervalEstimateMs = ref(null)
const pulseKey = ref(0)

watch(
  () => props.answeredAtMillis,
  (newVal) => {
    if (newVal == null || newVal === lastSeenAt.value) return
    if (lastSeenAt.value != null) {
      const gap = newVal - lastSeenAt.value
      // Ignore an obviously-stale jump (e.g. first load pulling in a tick from minutes ago) so one
      // outlier doesn't badly skew the rolling estimate used for the "overdue" countdown below.
      if (gap > 0 && gap < 5 * 60 * 1000) {
        intervalEstimateMs.value = intervalEstimateMs.value == null ? gap : intervalEstimateMs.value * 0.7 + gap * 0.3
      }
    }
    lastSeenAt.value = newVal
    pulseKey.value++ // remounts the pulse ring so its animation restarts on every new tick
  },
  { immediate: true },
)

const nowMs = ref(Date.now())
const tickHandle = setInterval(() => {
  nowMs.value = Date.now()
}, 1000)
onUnmounted(() => clearInterval(tickHandle))

const secondsSinceLastTick = computed(() => (lastSeenAt.value == null ? null : Math.max(0, Math.floor((nowMs.value - lastSeenAt.value) / 1000))))

const status = computed(() => {
  if (lastSeenAt.value == null) return 'unknown'
  if (intervalEstimateMs.value == null) return 'alive'
  const overdueFactor = (nowMs.value - lastSeenAt.value) / intervalEstimateMs.value
  if (overdueFactor > 4) return 'stalled'
  if (overdueFactor > 2) return 'late'
  return 'alive'
})

const statusColor = computed(() => ({ alive: 'bg-[var(--color-profit)]', late: 'bg-[var(--color-warn)]', stalled: 'bg-[var(--color-loss)]', unknown: 'bg-[var(--color-text-faint)]' })[status.value])
const statusLabel = computed(() => ({ alive: 'Model alive', late: 'Ticking slowly', stalled: 'Model stalled?', unknown: 'No ticks yet' })[status.value])
</script>

<template>
  <div class="flex items-center gap-2.5">
    <span class="relative flex h-2.5 w-2.5">
      <span
        :key="pulseKey"
        v-if="status !== 'unknown'"
        class="heartbeat-ping absolute inline-flex h-full w-full rounded-full opacity-75"
        :class="statusColor"
      />
      <span class="relative inline-flex rounded-full h-2.5 w-2.5" :class="statusColor" />
    </span>
    <div class="flex flex-col leading-tight">
      <span class="text-xs font-medium" :class="status === 'stalled' ? 'text-[var(--color-loss)]' : 'text-[var(--color-text)]'">{{ statusLabel }}</span>
      <span v-if="secondsSinceLastTick != null" class="text-[10px] text-[var(--color-text-faint)] font-mono-nums">
        last tick {{ secondsSinceLastTick }}s ago
      </span>
    </div>
  </div>
</template>

<style scoped>
.heartbeat-ping {
  animation: heartbeat-ping 1s cubic-bezier(0, 0, 0.2, 1) 1;
}
@keyframes heartbeat-ping {
  0% {
    transform: scale(1);
    opacity: 0.75;
  }
  75%,
  100% {
    transform: scale(2.5);
    opacity: 0;
  }
}
</style>
