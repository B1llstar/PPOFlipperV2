<script setup>
defineProps({
  icon: { type: String, default: '◇' },
  title: { type: String, required: true },
  message: { type: String, default: '' },
})
</script>

<template>
  <div
    class="flex flex-col items-center justify-center gap-2 py-16 px-6 text-center rounded-xl border border-dashed border-[var(--color-border)]"
  >
    <span class="text-3xl text-[var(--color-text-faint)]">{{ icon }}</span>
    <p class="text-sm font-medium text-[var(--color-text-dim)]">{{ title }}</p>
    <p v-if="message" class="text-xs text-[var(--color-text-faint)] max-w-sm">{{ message }}</p>
  </div>
</template>
