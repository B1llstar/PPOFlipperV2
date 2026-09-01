<script setup>
import { formatRelativeTime } from '@/composables/useFormat'

defineProps({
  isOnline: { type: Boolean, default: false },
  lastSeenMillis: { type: Number, default: null },
})
</script>

<template>
  <div class="flex items-center gap-2 text-xs">
    <span class="relative flex w-2 h-2">
      <span
        v-if="isOnline"
        class="absolute inline-flex h-full w-full rounded-full bg-[var(--color-profit)] opacity-60 animate-ping"
      />
      <span
        class="relative inline-flex rounded-full w-2 h-2"
        :class="isOnline ? 'bg-[var(--color-profit)]' : 'bg-[var(--color-text-faint)]'"
      />
    </span>
    <span :class="isOnline ? 'text-[var(--color-profit)]' : 'text-[var(--color-text-faint)]'">
      {{ isOnline ? 'Plugin online' : `Offline · last seen ${formatRelativeTime(lastSeenMillis)}` }}
    </span>
  </div>
</template>
