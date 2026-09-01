<script setup>
import { ref, watch } from 'vue'

const props = defineProps({
  src: { type: String, default: null },
  name: { type: String, default: '' },
  size: { type: Number, default: 24 },
})

const failed = ref(false)
watch(
  () => props.src,
  () => {
    failed.value = false
  },
)
</script>

<template>
  <img
    v-if="src && !failed"
    :src="src"
    :alt="name"
    :width="size"
    :height="size"
    class="object-contain shrink-0"
    :style="{ imageRendering: 'pixelated' }"
    loading="lazy"
    @error="failed = true"
  />
  <span
    v-else
    class="inline-flex items-center justify-center rounded bg-[var(--color-surface-3)] text-[var(--color-text-faint)] shrink-0"
    :style="{ width: size + 'px', height: size + 'px', fontSize: size * 0.5 + 'px' }"
    >?</span
  >
</template>
