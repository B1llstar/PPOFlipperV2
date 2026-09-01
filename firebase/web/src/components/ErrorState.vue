<script setup>
defineProps({
  title: { type: String, default: 'Something went wrong' },
  message: { type: String, default: '' },
  permissionDenied: { type: Boolean, default: false },
})
</script>

<template>
  <div
    class="flex flex-col items-center justify-center gap-2 py-16 px-6 text-center rounded-xl border border-[var(--color-loss-dim)] bg-[var(--color-loss-dim)]/20"
  >
    <span class="text-3xl">{{ permissionDenied ? '🔒' : '⚠' }}</span>
    <p class="text-sm font-semibold text-[var(--color-loss)]">
      {{ permissionDenied ? "You don't have access" : title }}
    </p>
    <p class="text-xs text-[var(--color-text-dim)] max-w-md">
      <template v-if="permissionDenied">
        Your Google account isn't on the PPOFlipperStar dashboard allowlist. Ask the owner to add your email to
        <code class="font-mono-nums text-[var(--color-text)]">firestore.rules</code>, or sign in with a different
        account.
      </template>
      <template v-else>{{ message }}</template>
    </p>
  </div>
</template>
