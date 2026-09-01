<script setup>
defineProps({
  accounts: { type: Array, required: true }, // [{accountHash, lastSeenMillis, pluginVersion}]
  selectedAccountHash: { type: String, default: null },
})
const emit = defineEmits(['select'])
</script>

<template>
  <select
    v-if="accounts.length > 1"
    :value="selectedAccountHash"
    class="text-sm bg-[var(--color-surface-2)] border border-[var(--color-border)] rounded-lg px-3 py-1.5 text-[var(--color-text)] focus:outline-none focus:border-[var(--color-accent)] cursor-pointer"
    @change="emit('select', $event.target.value)"
  >
    <option v-for="acc in accounts" :key="acc.accountHash" :value="acc.accountHash">
      Account …{{ acc.accountHash.slice(-6) }}
    </option>
  </select>
  <span
    v-else-if="accounts.length === 1"
    class="text-sm text-[var(--color-text-dim)] font-mono-nums"
  >
    Account …{{ accounts[0].accountHash.slice(-6) }}
  </span>
</template>
