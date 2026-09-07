<script setup>
import { computed } from 'vue'
import { Line } from 'vue-chartjs'
import { CHART_COLORS } from '@/composables/useChartTheme'
import { formatGp } from '@/composables/useFormat'

// A compact, LIVE-feeling companion to PerformanceView's full all-time cumulative P&L chart -
// deliberately a different thing, not a smaller copy of it: this only plots the recent trading
// window (default last 3 hours) from whatever `trades` the Dashboard already has live via
// useTradeHistory's onSnapshot listener, so it visibly grows/steps the instant a new fill lands,
// answering "is it making money right now" rather than "what's the all-time track record."
const props = defineProps({
  trades: { type: Array, required: true }, // newest-first, per useTradeHistory's own ordering
  windowMs: { type: Number, default: 3 * 60 * 60 * 1000 },
})

const points = computed(() => {
  const cutoff = Date.now() - props.windowMs
  // trades are newest-first; walk oldest-to-newest within the window to build a running total.
  const inWindow = props.trades.filter((t) => t.timestampMillis >= cutoff).slice().reverse()
  let running = 0
  const series = [{ x: cutoff, y: 0 }]
  for (const t of inWindow) {
    running += t.action === 'SELL' ? t.totalGp : -t.totalGp
    series.push({ x: t.timestampMillis, y: running })
  }
  // Extend the line to "now" so the chart doesn't visibly end mid-air if the last fill was a
  // while ago - it should read as "flat since then," not "data stopped."
  series.push({ x: Date.now(), y: running })
  return series
})

const netOverWindow = computed(() => (points.value.length > 0 ? points.value[points.value.length - 1].y : 0))
const isUp = computed(() => netOverWindow.value >= 0)

const chartData = computed(() => ({
  datasets: [
    {
      data: points.value,
      borderColor: isUp.value ? CHART_COLORS.profit : CHART_COLORS.loss,
      backgroundColor: isUp.value ? `${CHART_COLORS.profit}22` : `${CHART_COLORS.loss}22`,
      fill: true,
      tension: 0.15,
      stepped: 'before',
      pointRadius: 0,
      pointHoverRadius: 3,
      borderWidth: 2,
    },
  ],
}))

const chartOptions = {
  responsive: true,
  maintainAspectRatio: false,
  animation: { duration: 300 },
  interaction: { mode: 'index', intersect: false },
  scales: {
    x: { type: 'time', display: false },
    y: { display: false },
  },
  plugins: {
    legend: { display: false },
    tooltip: {
      callbacks: {
        label: (ctx) => `Net: ${formatGp(ctx.parsed.y)}`,
        title: (items) => new Date(items[0].parsed.x).toLocaleTimeString(),
      },
    },
  },
}
</script>

<template>
  <div class="rounded-xl border border-[var(--color-border)] bg-[var(--color-surface)] px-5 py-4 flex flex-col gap-2">
    <div class="flex items-center justify-between">
      <span class="text-xs font-medium uppercase tracking-wider text-[var(--color-text-faint)]">Net P&amp;L (last 3h)</span>
      <span class="text-lg font-semibold font-mono-nums" :class="isUp ? 'text-[var(--color-profit)]' : 'text-[var(--color-loss)]'">
        {{ netOverWindow >= 0 ? '+' : '' }}{{ formatGp(netOverWindow) }}
      </span>
    </div>
    <div class="h-16">
      <Line :data="chartData" :options="chartOptions" />
    </div>
  </div>
</template>
