<script setup>
import { computed, ref, onMounted } from 'vue'
import { useTradeHistory } from '@/composables/useTradeHistory'
import { useItemMapping } from '@/composables/useItemMapping'
import { formatGp, formatGpExact, formatDateTime } from '@/composables/useFormat'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingSpinner from '@/components/LoadingSpinner.vue'
import ItemIcon from '@/components/ItemIcon.vue'

const props = defineProps({
  accountHash: { type: String, required: true },
})

const accountHashRef = computed(() => props.accountHash)
const { trades, loading, error, actionFilter, setActionFilter, clearFilters } = useTradeHistory(accountHashRef)
const { getName, getIconUrl, load: loadMapping } = useItemMapping()

onMounted(loadMapping)

const itemNameQuery = ref('')
const dateFrom = ref('')
const dateTo = ref('')
const sortKey = ref('timestampMillis')
const sortDir = ref('desc')
const page = ref(1)
const pageSize = 25

function toggleSort(key) {
  if (sortKey.value === key) {
    sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortKey.value = key
    sortDir.value = 'desc'
  }
  page.value = 1
}

const enrichedTrades = computed(() =>
  trades.value.map((t) => ({
    ...t,
    displayName: t.itemName || getName(t.itemId),
    icon: getIconUrl(t.itemId),
  })),
)

const filteredTrades = computed(() => {
  let rows = enrichedTrades.value

  if (itemNameQuery.value.trim()) {
    const q = itemNameQuery.value.trim().toLowerCase()
    rows = rows.filter((t) => t.displayName.toLowerCase().includes(q))
  }
  if (dateFrom.value) {
    const fromMs = new Date(dateFrom.value).getTime()
    rows = rows.filter((t) => t.timestampMillis >= fromMs)
  }
  if (dateTo.value) {
    const toMs = new Date(dateTo.value).getTime() + 86_400_000 // inclusive end-of-day
    rows = rows.filter((t) => t.timestampMillis <= toMs)
  }

  const sorted = [...rows].sort((a, b) => {
    const dir = sortDir.value === 'asc' ? 1 : -1
    const av = a[sortKey.value]
    const bv = b[sortKey.value]
    if (typeof av === 'string') return av.localeCompare(bv) * dir
    return (av - bv) * dir
  })
  return sorted
})

const totalPages = computed(() => Math.max(1, Math.ceil(filteredTrades.value.length / pageSize)))
const pagedTrades = computed(() => {
  const start = (page.value - 1) * pageSize
  return filteredTrades.value.slice(start, start + pageSize)
})

function resetToFirstPage() {
  page.value = 1
}

const summary = computed(() => {
  const buys = filteredTrades.value.filter((t) => t.action === 'BUY')
  const sells = filteredTrades.value.filter((t) => t.action === 'SELL')
  const netGp = filteredTrades.value.reduce((sum, t) => sum + (t.action === 'SELL' ? t.totalGp : -t.totalGp), 0)
  return { buyCount: buys.length, sellCount: sells.length, netGp }
})

const sortIndicator = (key) => (sortKey.value === key ? (sortDir.value === 'asc' ? '▲' : '▼') : '')
</script>

<template>
  <div class="flex flex-col gap-5">
    <div class="flex items-center justify-between flex-wrap gap-3">
      <div>
        <h1 class="text-lg font-semibold">Trade History</h1>
        <p class="text-xs text-[var(--color-text-faint)]">
          {{ filteredTrades.length.toLocaleString() }} fills · {{ summary.buyCount }} buys, {{ summary.sellCount }} sells · net
          <span :class="summary.netGp >= 0 ? 'text-[var(--color-profit)]' : 'text-[var(--color-loss)]'">{{
            formatGp(summary.netGp)
          }}</span>
        </p>
      </div>
    </div>

    <!-- Filters -->
    <div class="flex flex-wrap items-end gap-3 rounded-xl border border-[var(--color-border)] bg-[var(--color-surface)] p-4">
      <div class="flex flex-col gap-1">
        <label class="text-xs text-[var(--color-text-faint)]">Item name</label>
        <input
          v-model="itemNameQuery"
          type="text"
          placeholder="Search item…"
          class="bg-[var(--color-surface-2)] border border-[var(--color-border)] rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:border-[var(--color-accent)] w-48"
          @input="resetToFirstPage"
        />
      </div>
      <div class="flex flex-col gap-1">
        <label class="text-xs text-[var(--color-text-faint)]">Action</label>
        <div class="flex gap-1">
          <button
            v-for="opt in ['ALL', 'BUY', 'SELL']"
            :key="opt"
            class="px-3 py-1.5 rounded-lg text-sm border transition-colors"
            :class="
              (opt === 'ALL' && !actionFilter) || actionFilter === opt
                ? 'bg-[var(--color-surface-3)] border-[var(--color-border-strong)] text-[var(--color-text)]'
                : 'border-[var(--color-border)] text-[var(--color-text-dim)] hover:text-[var(--color-text)]'
            "
            @click="
              () => {
                opt === 'ALL' ? clearFilters() : setActionFilter(opt)
                resetToFirstPage()
              }
            "
          >
            {{ opt }}
          </button>
        </div>
      </div>
      <div class="flex flex-col gap-1">
        <label class="text-xs text-[var(--color-text-faint)]">From</label>
        <input
          v-model="dateFrom"
          type="date"
          class="bg-[var(--color-surface-2)] border border-[var(--color-border)] rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:border-[var(--color-accent)]"
          @change="resetToFirstPage"
        />
      </div>
      <div class="flex flex-col gap-1">
        <label class="text-xs text-[var(--color-text-faint)]">To</label>
        <input
          v-model="dateTo"
          type="date"
          class="bg-[var(--color-surface-2)] border border-[var(--color-border)] rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:border-[var(--color-accent)]"
          @change="resetToFirstPage"
        />
      </div>
      <button
        v-if="itemNameQuery || dateFrom || dateTo || actionFilter"
        class="text-xs text-[var(--color-text-dim)] hover:text-[var(--color-text)] underline underline-offset-2 mb-2"
        @click="
          () => {
            itemNameQuery = ''
            dateFrom = ''
            dateTo = ''
            clearFilters()
            resetToFirstPage()
          }
        "
      >
        Clear filters
      </button>
    </div>

    <ErrorState
      v-if="error"
      :permission-denied="error.code === 'permission-denied'"
      title="Failed to load trade history"
      :message="error.message"
    />
    <LoadingSpinner v-else-if="loading" label="Loading trade history…" />
    <EmptyState
      v-else-if="filteredTrades.length === 0"
      title="No trades yet"
      message="No completed buy/sell fills match the current filters, or none have happened yet."
    />
    <div v-else class="rounded-xl border border-[var(--color-border)] bg-[var(--color-surface)] overflow-hidden">
      <div class="overflow-x-auto">
        <table class="w-full text-sm">
          <thead>
            <tr class="text-left text-xs text-[var(--color-text-faint)] uppercase tracking-wide select-none">
              <th class="px-5 py-2 font-medium cursor-pointer" @click="toggleSort('timestampMillis')">
                Time {{ sortIndicator('timestampMillis') }}
              </th>
              <th class="px-3 py-2 font-medium cursor-pointer" @click="toggleSort('action')">
                Action {{ sortIndicator('action') }}
              </th>
              <th class="px-3 py-2 font-medium">Item</th>
              <th class="px-3 py-2 font-medium text-right cursor-pointer" @click="toggleSort('quantity')">
                Qty {{ sortIndicator('quantity') }}
              </th>
              <th class="px-3 py-2 font-medium text-right cursor-pointer" @click="toggleSort('pricePerUnit')">
                Price ea. {{ sortIndicator('pricePerUnit') }}
              </th>
              <th class="px-5 py-2 font-medium text-right cursor-pointer" @click="toggleSort('totalGp')">
                Total GP {{ sortIndicator('totalGp') }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="t in pagedTrades"
              :key="t.id"
              class="border-t border-[var(--color-border)] hover:bg-[var(--color-surface-2)]"
            >
              <td class="px-5 py-2.5 text-[var(--color-text-dim)] whitespace-nowrap">{{ formatDateTime(t.timestampMillis) }}</td>
              <td class="px-3 py-2.5">
                <span
                  class="px-2 py-0.5 rounded text-xs font-semibold"
                  :class="t.action === 'BUY' ? 'bg-[var(--color-profit-dim)] text-[var(--color-profit)]' : 'bg-[var(--color-loss-dim)] text-[var(--color-loss)]'"
                >
                  {{ t.action }}
                </span>
              </td>
              <td class="px-3 py-2.5">
                <div class="flex items-center gap-2 min-w-0">
                  <ItemIcon :src="t.icon" :name="t.displayName" :size="18" />
                  <span class="truncate">{{ t.displayName }}</span>
                </div>
              </td>
              <td class="px-3 py-2.5 text-right font-mono-nums">{{ t.quantity.toLocaleString() }}</td>
              <td class="px-3 py-2.5 text-right font-mono-nums text-[var(--color-text-dim)]">{{ formatGpExact(t.pricePerUnit) }}</td>
              <td class="px-5 py-2.5 text-right font-mono-nums">{{ formatGpExact(t.totalGp) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="flex items-center justify-between px-5 py-3 border-t border-[var(--color-border)] text-xs text-[var(--color-text-dim)]">
        <span>Page {{ page }} of {{ totalPages }} ({{ filteredTrades.length.toLocaleString() }} rows)</span>
        <div class="flex gap-2">
          <button
            class="px-3 py-1 rounded-lg border border-[var(--color-border)] disabled:opacity-40 disabled:cursor-not-allowed hover:border-[var(--color-border-strong)]"
            :disabled="page <= 1"
            @click="page--"
          >
            Prev
          </button>
          <button
            class="px-3 py-1 rounded-lg border border-[var(--color-border)] disabled:opacity-40 disabled:cursor-not-allowed hover:border-[var(--color-border-strong)]"
            :disabled="page >= totalPages"
            @click="page++"
          >
            Next
          </button>
        </div>
      </div>
    </div>

    <p class="text-xs text-[var(--color-text-faint)]">
      Showing up to the most recent 500 fills for this account. Item-name search, date range, and column sorting are
      applied client-side over that set.
    </p>
  </div>
</template>
