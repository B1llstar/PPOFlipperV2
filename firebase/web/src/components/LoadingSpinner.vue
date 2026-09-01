<script setup>
defineProps({
  label: { type: String, default: 'Loading…' },
})
</script>

<template>
  <div class="flex flex-col items-center justify-center gap-3 py-16 text-[var(--color-text-dim)]">
    <span
      class="w-6 h-6 rounded-full border-2 border-[var(--color-border-strong)] border-t-[var(--color-accent)] animate-spin"
    />
    <span class="text-xs">{{ label }}</span>
  </div>
</template>
