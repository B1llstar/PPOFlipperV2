<script setup>
import { computed, ref, watch } from 'vue'
import { formatGpExact, formatRelativeTime } from '@/composables/useFormat'
import ItemIcon from '@/components/ItemIcon.vue'

// Live feed of fills as they land — reads the same `trades` array useTradeHistory already keeps
// current via a realtime onSnapshot listener (see that composable's own doc), sorted newest-first.
// This component's only job is presentation: cap to a handful of the most recent rows and animate
// new ones in, rather than re-fetching or filtering anything itself.
const props = defineProps({
  trades: { type: Array, required: true },
  getName: { type: Function, required: true },
  getIconUrl: { type: Function, required: true },
  maxRows: { type: Number, default: 6 },
})

const recent = computed(() => props.trades.slice(0, props.maxRows))

// Tracks which trade ids have already been rendered once, so only a GENUINELY new fill (one that
// arrived after this component mounted, or since the last render) gets the slide-in/highlight
// animation - without this every re-render (e.g. a prop identity change with the same data) would
// replay the animation on rows that have already been sitting there for minutes.
const seenIds = ref(new Set())
const freshIds = ref(new Set())

watch(
  recent,
  (rows) => {
    const nowFresh = new Set()
    for (const t of rows) {
      if (!seenIds.value.has(t.id)) {
        nowFresh.add(t.id)
        seenIds.value.add(t.id)
      }
    }
    if (nowFresh.size > 0) {
      freshIds.value = nowFresh
      // Drop the "fresh" flag after the animation has had time to play, so the CSS transition
      // only ever fires once per trade rather than replaying on every subsequent re-render.
      setTimeout(() => {
        freshIds.value = new Set()
      }, 1200)
    }
  },
  { immediate: true, deep: false },
)

function rowLabel(t) {
  const name = t.itemName || props.getName(t.itemId)
  return `${t.action === 'BUY' ? 'Bought' : 'Sold'} ${t.quantity.toLocaleString()}x ${name}`
}
</script>

<template>
  <div class="rounded-xl border border-[var(--color-border)] bg-[var(--color-surface)] overflow-hidden">
    <div class="px-5 py-3 border-b border-[var(--color-border)] flex items-center gap-2">
      <span class="relative flex h-2 w-2">
        <span
          v-if="recent.length > 0"
          class="animate-ping absolute inline-flex h-full w-full rounded-full bg-[var(--color-profit)] opacity-75"
        />
        <span class="relative inline-flex rounded-full h-2 w-2" :class="recent.length > 0 ? 'bg-[var(--color-profit)]' : 'bg-[var(--color-text-faint)]'" />
      </span>
      <h2 class="text-sm font-semibold">Live activity</h2>
    </div>

    <TransitionGroup
      v-if="recent.length > 0"
      tag="ul"
      name="ticker-row"
      class="divide-y divide-[var(--color-border)]"
    >
      <li
        v-for="t in recent"
        :key="t.id"
        class="px-5 py-2.5 flex items-center gap-3 text-sm transition-colors duration-1000"
        :class="freshIds.has(t.id) ? (t.action === 'BUY' ? 'bg-[var(--color-profit-dim)]/40' : 'bg-[var(--color-loss-dim)]/40') : ''"
      >
        <span
          class="w-1.5 h-1.5 rounded-full shrink-0"
          :class="t.action === 'BUY' ? 'bg-[var(--color-profit)]' : 'bg-[var(--color-loss)]'"
        />
        <ItemIcon :src="getIconUrl(t.itemId)" :name="rowLabel(t)" :size="18" />
        <span class="truncate flex-1 min-w-0">{{ rowLabel(t) }}</span>
        <span class="font-mono-nums text-[var(--color-text-dim)] shrink-0">@{{ formatGpExact(t.pricePerUnit) }}</span>
        <span class="font-mono-nums font-semibold shrink-0" :class="t.action === 'BUY' ? 'text-[var(--color-loss)]' : 'text-[var(--color-profit)]'">
          {{ t.action === 'BUY' ? '-' : '+' }}{{ formatGpExact(t.totalGp) }}
        </span>
        <span class="text-xs text-[var(--color-text-faint)] shrink-0 w-14 text-right">{{ formatRelativeTime(t.timestampMillis) }}</span>
      </li>
    </TransitionGroup>
    <p v-else class="px-5 py-6 text-center text-sm text-[var(--color-text-faint)]">No fills yet — waiting for the first trade.</p>
  </div>
</template>

<style scoped>
.ticker-row-enter-active {
  transition: all 0.4s ease-out;
}
.ticker-row-enter-from {
  opacity: 0;
  transform: translateY(-8px);
}
.ticker-row-move {
  transition: transform 0.4s ease;
}
</style>
