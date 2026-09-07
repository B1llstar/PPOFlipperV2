<script setup>
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { formatGp } from '@/composables/useFormat'

// Fires a one-shot confetti burst + toast the moment a single trade's realized profit crosses
// `threshold` - a small reward moment tied to a genuinely real trading outcome, not a fake/random
// animation. Mounted once at the App shell level (not per-view) so it fires regardless of which
// page is currently open, matching where PresenceBadge/AccountPicker already live.
//
// Deliberately a hand-rolled canvas confetti burst rather than a new npm dependency - this is a
// few dozen particles for under two seconds, well within what a ~60-line canvas loop can do
// without pulling in a whole library for one effect.
const props = defineProps({
  trades: { type: Array, required: true }, // newest-first, from useTradeHistory
  threshold: { type: Number, default: 50_000 },
})

const active = ref(false)
const activeTrade = ref(null)
const canvasEl = ref(null)
let seenIds = new Set()
let animationFrame = null
let hideTimeout = null

function profitOf(trade) {
  // Only a SELL realizes profit against what was actually paid; a BUY's own totalGp is spend, not
  // profit, regardless of size - never celebrate a big purchase as if it were a win.
  return trade.action === 'SELL' ? trade.totalGp : 0
}

function launchConfetti() {
  const canvas = canvasEl.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  canvas.width = window.innerWidth
  canvas.height = window.innerHeight

  const colors = ['#e0a836', '#3ecf8e', '#5b9df2', '#f0bb52', '#f2685b']
  const particles = Array.from({ length: 90 }, () => ({
    x: canvas.width / 2 + (Math.random() - 0.5) * 200,
    y: canvas.height * 0.25,
    vx: (Math.random() - 0.5) * 12,
    vy: Math.random() * -10 - 4,
    size: Math.random() * 6 + 4,
    color: colors[Math.floor(Math.random() * colors.length)],
    rotation: Math.random() * 360,
    rotationSpeed: (Math.random() - 0.5) * 20,
    gravity: 0.35,
  }))

  const start = performance.now()
  function frame(now) {
    const elapsed = now - start
    ctx.clearRect(0, 0, canvas.width, canvas.height)
    for (const p of particles) {
      p.vy += p.gravity
      p.x += p.vx
      p.y += p.vy
      p.rotation += p.rotationSpeed
      ctx.save()
      ctx.translate(p.x, p.y)
      ctx.rotate((p.rotation * Math.PI) / 180)
      ctx.fillStyle = p.color
      ctx.globalAlpha = Math.max(0, 1 - elapsed / 2200)
      ctx.fillRect(-p.size / 2, -p.size / 2, p.size, p.size * 0.6)
      ctx.restore()
    }
    if (elapsed < 2200) {
      animationFrame = requestAnimationFrame(frame)
    } else {
      ctx.clearRect(0, 0, canvas.width, canvas.height)
    }
  }
  animationFrame = requestAnimationFrame(frame)
}

watch(
  () => props.trades,
  (rows) => {
    if (rows.length === 0) return
    // First pass (rows already seen) just primes the seen-set without celebrating - otherwise
    // every trade already in history would fire a burst the instant the dashboard loads.
    if (seenIds.size === 0) {
      seenIds = new Set(rows.map((t) => t.id))
      return
    }
    const winner = rows.find((t) => !seenIds.has(t.id) && profitOf(t) >= props.threshold)
    for (const t of rows) seenIds.add(t.id)
    if (!winner) return

    activeTrade.value = winner
    active.value = true
    launchConfetti()
    clearTimeout(hideTimeout)
    hideTimeout = setTimeout(() => {
      active.value = false
    }, 5000)
  },
  { deep: false },
)

onMounted(() => {
  window.addEventListener('resize', () => {
    if (canvasEl.value) {
      canvasEl.value.width = window.innerWidth
      canvasEl.value.height = window.innerHeight
    }
  })
})
onUnmounted(() => {
  if (animationFrame) cancelAnimationFrame(animationFrame)
  clearTimeout(hideTimeout)
})
</script>

<template>
  <canvas ref="canvasEl" class="fixed inset-0 pointer-events-none z-[100]" />
  <Transition name="celebration-toast">
    <div
      v-if="active && activeTrade"
      class="fixed top-20 left-1/2 -translate-x-1/2 z-[101] rounded-xl border border-[var(--color-accent)] bg-[var(--color-surface)] shadow-2xl px-6 py-4 flex items-center gap-3"
    >
      <span class="text-2xl">💰</span>
      <div class="flex flex-col">
        <span class="text-sm font-semibold text-[var(--color-accent-strong)]">Big win!</span>
        <span class="text-xs text-[var(--color-text-dim)]">
          Sold {{ activeTrade.quantity.toLocaleString() }}x {{ activeTrade.itemName }} for
          <span class="text-[var(--color-profit)] font-mono-nums font-semibold">+{{ formatGp(activeTrade.totalGp) }}</span>
        </span>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.celebration-toast-enter-active {
  transition: all 0.35s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.celebration-toast-leave-active {
  transition: all 0.3s ease-in;
}
.celebration-toast-enter-from {
  opacity: 0;
  transform: translate(-50%, -20px) scale(0.9);
}
.celebration-toast-leave-to {
  opacity: 0;
  transform: translate(-50%, -10px) scale(0.95);
}
</style>
