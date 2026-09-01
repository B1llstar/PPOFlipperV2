<script setup>
import { computed, onMounted } from 'vue'
import { Line, Bar } from 'vue-chartjs'
import { useTradeHistory } from '@/composables/useTradeHistory'
import { useItemMapping } from '@/composables/useItemMapping'
import { CHART_COLORS } from '@/composables/useChartTheme'
import { formatGp, formatPercent } from '@/composables/useFormat'
import StatCard from '@/components/StatCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingSpinner from '@/components/LoadingSpinner.vue'

const props = defineProps({
  accountHash: { type: String, required: true },
})

const accountHashRef = computed(() => props.accountHash)
// Wider window for performance charts than the history table's default cap.
const { trades, loading, error } = useTradeHistory(accountHashRef, { rowLimit: 2000 })
const { getName, load: loadMapping } = useItemMapping()

onMounted(loadMapping)

// Trades come back newest-first from useTradeHistory; charts want chronological order.
const chronological = computed(() => [...trades.value].sort((a, b) => a.timestampMillis - b.timestampMillis))

// --- Cumulative realized P&L over time -------------------------------------------------------
// Realized P&L per trade isn't stored directly on tradeHistory (only action/qty/price/totalGp),
// so it's derived the same way CostBasisEntry does: a SELL realizes (proceeds - cost-of-sold-
// portion) using a running weighted-average cost per item, rebuilt client-side by replaying every
// BUY/SELL fill in chronological order. BUYs don't realize anything themselves, but do change the
// running average cost future SELLs are measured against.
const cumulativePnl = computed(() => {
  const runningQty = new Map()
  const runningCost = new Map()
  let cumulative = 0
  const points = []

  for (const t of chronological.value) {
    const qty = runningQty.get(t.itemId) ?? 0
    const cost = runningCost.get(t.itemId) ?? 0

    if (t.action === 'BUY') {
      runningQty.set(t.itemId, qty + t.quantity)
      runningCost.set(t.itemId, cost + t.totalGp)
    } else if (t.action === 'SELL') {
      const soldFromTracked = Math.min(t.quantity, qty)
      const costOfSold = qty > 0 ? (soldFromTracked * cost) / qty : 0
      const realized = t.totalGp - costOfSold
      cumulative += realized
      runningQty.set(t.itemId, qty - soldFromTracked)
      runningCost.set(t.itemId, cost - costOfSold)
    }

    points.push({ x: t.timestampMillis, y: cumulative })
  }
  return points
})

const pnlChartData = computed(() => ({
  datasets: [
    {
      label: 'Cumulative realized P&L',
      data: cumulativePnl.value,
      borderColor: CHART_COLORS.accent,
      backgroundColor: `${CHART_COLORS.accent}22`,
      fill: true,
      tension: 0.25,
      pointRadius: 0,
      pointHoverRadius: 4,
      borderWidth: 2,
    },
  ],
}))

const pnlChartOptions = {
  responsive: true,
  maintainAspectRatio: false,
  interaction: { mode: 'index', intersect: false },
  scales: {
    x: {
      type: 'time',
      time: { unit: 'day' },
      grid: { color: CHART_COLORS.grid },
      ticks: { color: CHART_COLORS.text },
    },
    y: {
      grid: { color: CHART_COLORS.grid },
      ticks: {
        color: CHART_COLORS.text,
        callback: (v) => formatGp(v),
      },
    },
  },
  plugins: {
    legend: { display: false },
    tooltip: {
      callbacks: {
        label: (ctx) => `Cumulative: ${formatGp(ctx.parsed.y)}`,
      },
    },
  },
}

const finalPnl = computed(() => (cumulativePnl.value.length ? cumulativePnl.value.at(-1).y : 0))

// --- Win rate over time (rolling, per completed SELL) -----------------------------------------
const sellsWithRealized = computed(() => {
  const runningQty = new Map()
  const runningCost = new Map()
  const rows = []
  for (const t of chronological.value) {
    const qty = runningQty.get(t.itemId) ?? 0
    const cost = runningCost.get(t.itemId) ?? 0
    if (t.action === 'BUY') {
      runningQty.set(t.itemId, qty + t.quantity)
      runningCost.set(t.itemId, cost + t.totalGp)
    } else if (t.action === 'SELL') {
      const soldFromTracked = Math.min(t.quantity, qty)
      const costOfSold = qty > 0 ? (soldFromTracked * cost) / qty : 0
      const realized = t.totalGp - costOfSold
      runningQty.set(t.itemId, qty - soldFromTracked)
      runningCost.set(t.itemId, cost - costOfSold)
      rows.push({ timestampMillis: t.timestampMillis, realized })
    }
  }
  return rows
})

const overallWinRate = computed(() => {
  const sells = sellsWithRealized.value
  if (sells.length === 0) return null
  const wins = sells.filter((s) => s.realized > 0).length
  return wins / sells.length
})

// Rolling win rate in buckets of 10 sells, so the chart shows a trend rather than one flat number.
const winRateChartData = computed(() => {
  const sells = sellsWithRealized.value
  const bucketSize = 10
  const points = []
  for (let i = 0; i < sells.length; i += bucketSize) {
    const bucket = sells.slice(i, i + bucketSize)
    const wins = bucket.filter((s) => s.realized > 0).length
    points.push({ x: bucket.at(-1).timestampMillis, y: wins / bucket.length })
  }
  return {
    datasets: [
      {
        label: 'Win rate (per 10 sells)',
        data: points,
        borderColor: CHART_COLORS.info,
        backgroundColor: `${CHART_COLORS.info}22`,
        fill: true,
        tension: 0.3,
        pointRadius: 2,
        borderWidth: 2,
      },
    ],
  }
})

const winRateChartOptions = {
  responsive: true,
  maintainAspectRatio: false,
  scales: {
    x: { type: 'time', time: { unit: 'day' }, grid: { color: CHART_COLORS.grid }, ticks: { color: CHART_COLORS.text } },
    y: {
      min: 0,
      max: 1,
      grid: { color: CHART_COLORS.grid },
      ticks: { color: CHART_COLORS.text, callback: (v) => formatPercent(v, 0) },
    },
  },
  plugins: {
    legend: { display: false },
    tooltip: { callbacks: { label: (ctx) => `Win rate: ${formatPercent(ctx.parsed.y, 0)}` } },
  },
}

// --- Volume traded per item (top 10 by total GP) ------------------------------------------------
const volumeByItem = computed(() => {
  const totals = new Map()
  for (const t of trades.value) {
    const name = t.itemName || getName(t.itemId)
    totals.set(name, (totals.get(name) ?? 0) + t.totalGp)
  }
  return [...totals.entries()].sort((a, b) => b[1] - a[1]).slice(0, 10)
})

const volumeChartData = computed(() => ({
  labels: volumeByItem.value.map(([name]) => name),
  datasets: [
    {
      label: 'GP volume traded',
      data: volumeByItem.value.map(([, gp]) => gp),
      backgroundColor: CHART_COLORS.accent,
      borderRadius: 4,
      maxBarThickness: 28,
    },
  ],
}))

const volumeChartOptions = {
  responsive: true,
  maintainAspectRatio: false,
  indexAxis: 'y',
  scales: {
    x: {
      grid: { color: CHART_COLORS.grid },
      ticks: { color: CHART_COLORS.text, callback: (v) => formatGp(v) },
    },
    y: { grid: { display: false }, ticks: { color: CHART_COLORS.text } },
  },
  plugins: {
    legend: { display: false },
    tooltip: { callbacks: { label: (ctx) => formatGp(ctx.parsed.x) } },
  },
}
</script>

<template>
  <div class="flex flex-col gap-6">
    <h1 class="text-lg font-semibold">Performance</h1>

    <ErrorState
      v-if="error"
      :permission-denied="error.code === 'permission-denied'"
      title="Failed to load trade history"
      :message="error.message"
    />
    <LoadingSpinner v-else-if="loading" label="Crunching trade history…" />
    <EmptyState
      v-else-if="trades.length === 0"
      title="No trades yet"
      message="Performance charts need at least one completed trade to derive anything from."
    />
    <template v-else>
      <section class="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard
          label="Realized P&amp;L (charted range)"
          :value="formatGp(finalPnl)"
          :tone="finalPnl > 0 ? 'profit' : finalPnl < 0 ? 'loss' : 'neutral'"
        />
        <StatCard
          label="Win rate"
          :value="overallWinRate == null ? '—' : formatPercent(overallWinRate, 0)"
          sub="fraction of closed sells profitable after cost basis"
        />
        <StatCard label="Completed sells" :value="sellsWithRealized.length.toLocaleString()" />
        <StatCard label="Total fills" :value="trades.length.toLocaleString()" />
      </section>

      <section class="rounded-xl border border-[var(--color-border)] bg-[var(--color-surface)] p-5">
        <h2 class="text-sm font-semibold mb-1">Cumulative realized P&amp;L</h2>
        <p class="text-xs text-[var(--color-text-faint)] mb-4">
          Running total of realized profit/loss, derived from tradeHistory via weighted-average cost basis (same
          accounting CostBasisEntry uses server-side).
        </p>
        <div class="h-72">
          <Line :data="pnlChartData" :options="pnlChartOptions" />
        </div>
      </section>

      <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <section class="rounded-xl border border-[var(--color-border)] bg-[var(--color-surface)] p-5">
          <h2 class="text-sm font-semibold mb-1">Win rate over time</h2>
          <p class="text-xs text-[var(--color-text-faint)] mb-4">Rolling win rate, bucketed every 10 completed sells.</p>
          <div class="h-64">
            <Line :data="winRateChartData" :options="winRateChartOptions" />
          </div>
        </section>

        <section class="rounded-xl border border-[var(--color-border)] bg-[var(--color-surface)] p-5">
          <h2 class="text-sm font-semibold mb-1">Volume traded per item</h2>
          <p class="text-xs text-[var(--color-text-faint)] mb-4">Top 10 items by total GP moved (buys + sells).</p>
          <div class="h-64">
            <Bar :data="volumeChartData" :options="volumeChartOptions" />
          </div>
        </section>
      </div>
    </template>
  </div>
</template>
