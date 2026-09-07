<script setup>
import { computed, ref } from 'vue'
import { formatGp, formatGpExact, formatRelativeTime } from '@/composables/useFormat'
import ItemIcon from '@/components/ItemIcon.vue'
import EmptyState from '@/components/EmptyState.vue'

// One row per closed round-trip (a SELL matched FIFO against the BUY lot(s) that funded it) - see
// useMatchedTrades' own doc for why this is a deliberately different, more concrete answer than
// the existing "Profit by item" table's weighted-average totals: "which specific buy became which
// specific sell, and was that pair profitable" rather than a running blend across every purchase.
const props = defineProps({
  matches: { type: Array, required: true },
  getIconUrl: { type: Function, required: true },
  maxRows: { type: Number, default: 20 },
})

const filter = ref('all') // 'all' | 'profit' | 'loss'

const filteredMatches = computed(() => {
  if (filter.value === 'profit') return props.matches.filter((m) => m.profit != null && m.profit > 0)
  if (filter.value === 'loss') return props.matches.filter((m) => m.profit != null && m.profit < 0)
  return props.matches
})

const visibleMatches = computed(() => filteredMatches.value.slice(0, props.maxRows))
</script>

<template>
  <section class="rounded-xl border border-[var(--color-border)] bg-[var(--color-surface)] overflow-hidden">
    <div class="px-5 py-4 border-b border-[var(--color-border)] flex items-center justify-between flex-wrap gap-2">
      <div>
        <h2 class="text-sm font-semibold">Profit by trade</h2>
        <p class="text-xs text-[var(--color-text-faint)] mt-0.5">
          Each SELL matched FIFO against the exact buy(s) that funded it - was THIS specific round-trip profitable,
          net of GE tax. "Unmatched" means part of the sold stock predates the trade history window shown here
          (its real cost isn't known, so it's excluded from the profit figure rather than guessed).
        </p>
      </div>
      <div class="flex items-center gap-1 text-xs shrink-0">
        <button
          v-for="opt in [{ key: 'all', label: 'All' }, { key: 'profit', label: 'Profitable' }, { key: 'loss', label: 'Losses' }]"
          :key="opt.key"
          class="px-2 py-1 rounded"
          :class="filter === opt.key ? 'bg-[var(--color-accent)] text-white' : 'bg-[var(--color-surface-3)] text-[var(--color-text-dim)]'"
          @click="filter = opt.key"
        >
          {{ opt.label }}
        </button>
      </div>
    </div>
    <EmptyState
      v-if="visibleMatches.length === 0"
      title="No closed trades yet"
      message="No SELL has happened yet, or none match the current filter."
    />
    <div v-else class="overflow-x-auto">
      <table class="w-full text-sm">
        <thead>
          <tr class="text-left text-xs text-[var(--color-text-faint)] uppercase tracking-wide">
            <th class="px-5 py-2 font-medium">Item</th>
            <th class="px-3 py-2 font-medium text-right">Bought @</th>
            <th class="px-3 py-2 font-medium text-right">Sold @</th>
            <th class="px-3 py-2 font-medium text-right">Qty</th>
            <th class="px-5 py-2 font-medium text-right">Profit</th>
            <th class="px-3 py-2 font-medium text-right">When</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="m in visibleMatches"
            :key="m.id"
            class="border-t border-[var(--color-border)] hover:bg-[var(--color-surface-2)]"
          >
            <td class="px-5 py-2.5">
              <div class="flex items-center gap-2 min-w-0">
                <ItemIcon :src="getIconUrl(m.itemId)" :name="m.itemName" :size="20" />
                <span class="truncate">{{ m.itemName }}</span>
              </div>
            </td>
            <td class="px-3 py-2.5 text-right font-mono-nums text-[var(--color-text-dim)]">
              {{ m.avgBuyPrice != null ? formatGpExact(Math.round(m.avgBuyPrice)) : '—' }}
            </td>
            <td class="px-3 py-2.5 text-right font-mono-nums text-[var(--color-text-dim)]">{{ formatGpExact(m.sellPricePerUnit) }}</td>
            <td class="px-3 py-2.5 text-right font-mono-nums">
              {{ m.matchedQuantity.toLocaleString() }}
              <span v-if="m.unmatchedQuantity > 0" class="text-[var(--color-text-faint)]">
                (+{{ m.unmatchedQuantity.toLocaleString() }} unmatched)
              </span>
            </td>
            <td
              class="px-5 py-2.5 text-right font-mono-nums font-semibold"
              :class="m.profit == null ? 'text-[var(--color-text-faint)]' : m.profit > 0 ? 'text-[var(--color-profit)]' : m.profit < 0 ? 'text-[var(--color-loss)]' : 'text-[var(--color-text-faint)]'"
            >
              <template v-if="m.profit != null">{{ m.profit >= 0 ? '+' : '' }}{{ formatGp(m.profit) }}</template>
              <template v-else>unmatched</template>
            </td>
            <td class="px-3 py-2.5 text-right text-xs text-[var(--color-text-faint)] whitespace-nowrap">
              {{ formatRelativeTime(m.sellTimestampMillis) }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="filteredMatches.length > maxRows" class="px-5 py-3 border-t border-[var(--color-border)] text-xs text-[var(--color-text-faint)]">
      Showing the {{ maxRows }} most recent of {{ filteredMatches.length.toLocaleString() }} matching trades.
    </p>
  </section>
</template>
