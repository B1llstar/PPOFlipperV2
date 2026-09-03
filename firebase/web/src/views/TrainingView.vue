<script setup>
import { computed } from 'vue'
import { Line } from 'vue-chartjs'
import { useTrainingRuns } from '@/composables/useTrainingRuns'
import { useInterpolatedStep } from '@/composables/useInterpolatedStep'
import { CHART_COLORS } from '@/composables/useChartTheme'
import { formatGp, formatPercent, formatNumber, formatDateTime } from '@/composables/useFormat'
import StatCard from '@/components/StatCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingSpinner from '@/components/LoadingSpinner.vue'

// Project-wide, not scoped to the selected account - a training run isn't tied to any one
// RuneScape account, so this view deliberately never reads the accountHash prop the other views
// receive from App.vue's RouterView.
const { runs, checkpointsByRun, loading, error } = useTrainingRuns()

// Most recently updated run is what someone opening this page almost certainly wants to see -
// whether it's actively in progress right now or the latest one to finish.
const latestRun = computed(() => runs.value[0] ?? null)
const olderRuns = computed(() => runs.value.slice(1))

const latestCheckpoints = computed(() => checkpointsByRun.value[latestRun.value?.gitCommit] ?? [])

// Client-side interpolation between real checkpoint updates (every --checkpoint-freq steps, which
// for a real run is minutes apart) - see useInterpolatedStep's own doc for why: without this the
// step count/progress bar would sit dead-still between checkpoints, reading as "did this stall?"
// even while training is actively running. No extra Firestore reads - purely derived from data
// already in hand (the two most recent checkpoints already loaded).
const { interpolatedStep, interpolatedProgressPct, stepsPerSecond } = useInterpolatedStep(latestRun, latestCheckpoints)

const isInProgress = computed(() => {
  const run = latestRun.value
  if (!run) return false
  return (run.progressPct ?? 0) < 100
})

const shortCommit = (gitCommit) => (gitCommit ?? '').slice(0, 12)

// --- Charts for the latest run's checkpoint history ------------------------------------------
const rewardChartData = computed(() => ({
  datasets: [
    {
      label: 'Validation mean episode reward',
      data: latestCheckpoints.value.map((c) => ({ x: c.step, y: c.valMeanEpisodeReward })),
      borderColor: CHART_COLORS.accent,
      backgroundColor: `${CHART_COLORS.accent}22`,
      fill: true,
      tension: 0.25,
      pointRadius: 2,
      pointHoverRadius: 4,
      borderWidth: 2,
    },
  ],
}))

const rewardChartOptions = {
  responsive: true,
  maintainAspectRatio: false,
  scales: {
    x: {
      type: 'linear',
      title: { display: true, text: 'Training step', color: CHART_COLORS.text },
      grid: { color: CHART_COLORS.grid },
      ticks: { color: CHART_COLORS.text, callback: (v) => formatNumber(v) },
    },
    y: {
      grid: { color: CHART_COLORS.grid },
      ticks: { color: CHART_COLORS.text },
    },
  },
  plugins: {
    legend: { display: false },
    tooltip: {
      callbacks: {
        title: (items) => `Step ${formatNumber(items[0]?.parsed?.x)}`,
        label: (ctx) => `Val reward: ${ctx.parsed.y.toFixed(2)}`,
      },
    },
  },
}

const winRateChartData = computed(() => ({
  datasets: [
    {
      label: 'Validation win rate',
      data: latestCheckpoints.value.map((c) => ({ x: c.step, y: c.valMeanWinRate })),
      borderColor: CHART_COLORS.profit,
      backgroundColor: `${CHART_COLORS.profit}22`,
      fill: true,
      tension: 0.25,
      pointRadius: 2,
      pointHoverRadius: 4,
      borderWidth: 2,
    },
  ],
}))

const winRateChartOptions = {
  responsive: true,
  maintainAspectRatio: false,
  scales: {
    x: {
      type: 'linear',
      title: { display: true, text: 'Training step', color: CHART_COLORS.text },
      grid: { color: CHART_COLORS.grid },
      ticks: { color: CHART_COLORS.text, callback: (v) => formatNumber(v) },
    },
    y: {
      min: 0,
      max: 1,
      grid: { color: CHART_COLORS.grid },
      ticks: { color: CHART_COLORS.text, callback: (v) => formatPercent(v, 0) },
    },
  },
  plugins: {
    legend: { display: false },
    tooltip: {
      callbacks: {
        title: (items) => `Step ${formatNumber(items[0]?.parsed?.x)}`,
        label: (ctx) => `Win rate: ${formatPercent(ctx.parsed.y, 0)}`,
      },
    },
  },
}
</script>

<template>
  <div class="flex flex-col gap-6">
    <h1 class="text-lg font-semibold">Training</h1>

    <ErrorState
      v-if="error"
      :permission-denied="error.code === 'permission-denied'"
      title="Failed to load training runs"
      :message="error.message"
    />
    <LoadingSpinner v-else-if="loading" label="Loading training runs…" />
    <EmptyState
      v-else-if="!latestRun"
      title="No training runs yet"
      message="Run data/ppo/train.py to see live progress here - each checkpoint pushes its metrics to Firestore automatically."
    />
    <template v-else>
      <section class="rounded-xl border border-[var(--color-border)] bg-[var(--color-surface)] p-5 flex flex-col gap-4">
        <div class="flex items-center justify-between flex-wrap gap-2">
          <div class="flex items-center gap-3">
            <span
              class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium"
              :class="
                isInProgress
                  ? 'bg-[var(--color-accent)]/15 text-[var(--color-accent)]'
                  : 'bg-[var(--color-profit)]/15 text-[var(--color-profit)]'
              "
            >
              <span
                class="w-1.5 h-1.5 rounded-full"
                :class="isInProgress ? 'bg-[var(--color-accent)] animate-pulse' : 'bg-[var(--color-profit)]'"
              />
              {{ isInProgress ? 'In progress' : 'Complete' }}
            </span>
            <span class="font-mono text-sm text-[var(--color-text-dim)]">{{ shortCommit(latestRun.gitCommit) }}</span>
          </div>
          <span class="text-xs text-[var(--color-text-faint)]">
            Updated {{ formatDateTime(latestRun.updatedAt?.toMillis?.()) }}
          </span>
        </div>

        <div class="flex flex-col gap-1.5">
          <div class="flex items-center justify-between text-xs text-[var(--color-text-dim)]">
            <span class="font-mono-nums">
              {{ formatNumber(Math.round(interpolatedStep)) }} / {{ formatNumber(latestRun.totalTimesteps) }} steps
            </span>
            <span class="flex items-center gap-2">
              <span v-if="isInProgress && stepsPerSecond > 0" class="text-[var(--color-text-faint)]">
                ~{{ formatNumber(Math.round(stepsPerSecond)) }} steps/s
              </span>
              <span class="font-mono-nums">{{ formatPercent(interpolatedProgressPct / 100, 1) }}</span>
            </span>
          </div>
          <div class="h-2 rounded-full bg-[var(--color-surface-3)] overflow-hidden">
            <div
              class="h-full rounded-full bg-[var(--color-accent)] transition-[width] duration-1000 ease-linear"
              :style="{ width: `${Math.min(100, interpolatedProgressPct)}%` }"
            />
          </div>
        </div>
      </section>

      <section class="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard
          label="Latest validation reward"
          :value="latestRun.latestValMeanEpisodeReward?.toFixed(2) ?? '—'"
        />
        <StatCard
          label="Latest realized P&amp;L (val)"
          :value="formatGp(latestRun.latestValMeanRealizedPnl)"
          :tone="latestRun.latestValMeanRealizedPnl > 0 ? 'profit' : latestRun.latestValMeanRealizedPnl < 0 ? 'loss' : 'neutral'"
        />
        <StatCard
          label="Latest win rate (val)"
          :value="formatPercent(latestRun.latestValMeanWinRate, 0)"
        />
        <StatCard
          label="Guardrail violations (val)"
          :value="formatNumber(latestRun.latestValMeanGuardrailViolations)"
          sub="mean per validation episode"
        />
      </section>

      <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <section class="rounded-xl border border-[var(--color-border)] bg-[var(--color-surface)] p-5">
          <h2 class="text-sm font-semibold mb-1">Validation reward over training</h2>
          <p class="text-xs text-[var(--color-text-faint)] mb-4">One point per checkpoint - noisy step-to-step is normal for PPO.</p>
          <div class="h-64">
            <Line v-if="latestCheckpoints.length" :data="rewardChartData" :options="rewardChartOptions" />
            <p v-else class="text-xs text-[var(--color-text-faint)]">Waiting for the first checkpoint…</p>
          </div>
        </section>

        <section class="rounded-xl border border-[var(--color-border)] bg-[var(--color-surface)] p-5">
          <h2 class="text-sm font-semibold mb-1">Validation win rate over training</h2>
          <p class="text-xs text-[var(--color-text-faint)] mb-4">Fraction of validation-episode sells that closed profitable.</p>
          <div class="h-64">
            <Line v-if="latestCheckpoints.length" :data="winRateChartData" :options="winRateChartOptions" />
            <p v-else class="text-xs text-[var(--color-text-faint)]">Waiting for the first checkpoint…</p>
          </div>
        </section>
      </div>

      <section v-if="olderRuns.length" class="rounded-xl border border-[var(--color-border)] bg-[var(--color-surface)] p-5">
        <h2 class="text-sm font-semibold mb-3">Earlier runs</h2>
        <div class="flex flex-col divide-y divide-[var(--color-border)]">
          <div
            v-for="run in olderRuns"
            :key="run.gitCommit"
            class="flex items-center justify-between gap-3 py-2.5 text-sm"
          >
            <span class="font-mono text-xs text-[var(--color-text-dim)]">{{ shortCommit(run.gitCommit) }}</span>
            <span class="text-xs text-[var(--color-text-faint)]">
              {{ formatNumber(run.latestStep) }} / {{ formatNumber(run.totalTimesteps) }} steps
            </span>
            <span class="text-xs">{{ formatPercent(run.latestValMeanWinRate, 0) }} win rate</span>
            <span :class="run.latestValMeanRealizedPnl > 0 ? 'text-[var(--color-profit)]' : 'text-[var(--color-loss)]'" class="text-xs">
              {{ formatGp(run.latestValMeanRealizedPnl) }}
            </span>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>
