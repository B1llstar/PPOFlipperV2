<script setup>
defineProps({
  label: { type: String, required: true },
  value: { type: String, required: true },
  sub: { type: String, default: '' },
  tone: { type: String, default: 'neutral' }, // 'neutral' | 'profit' | 'loss' | 'accent'
})

const toneClasses = {
  neutral: 'text-[var(--color-text)]',
  profit: 'text-[var(--color-profit)]',
  loss: 'text-[var(--color-loss)]',
  accent: 'text-[var(--color-accent)]',
}
</script>

<template>
  <div
    class="rounded-xl border border-[var(--color-border)] bg-[var(--color-surface)] px-5 py-4 flex flex-col gap-1.5 min-w-0"
  >
    <span class="text-xs font-medium uppercase tracking-wider text-[var(--color-text-faint)]">{{ label }}</span>
    <span class="text-2xl font-semibold font-mono-nums truncate" :class="toneClasses[tone] ?? toneClasses.neutral">
      {{ value }}
    </span>
    <span v-if="sub" class="text-xs text-[var(--color-text-dim)] truncate">{{ sub }}</span>
  </div>
</template>
