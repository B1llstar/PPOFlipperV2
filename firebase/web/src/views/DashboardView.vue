<script setup>
import { computed, ref, watch, onMounted } from 'vue'
import { usePortfolio } from '@/composables/usePortfolio'
import { useBuyLimitLedger } from '@/composables/useBuyLimitLedger'
import { useWatchlist } from '@/composables/useWatchlist'
import { useDecision } from '@/composables/useDecision'
import { useWikiPrices } from '@/composables/useWikiPrices'
import { useItemMapping } from '@/composables/useItemMapping'
import { useTradeHistory } from '@/composables/useTradeHistory'
import { formatGp, formatGpExact, formatPercent, formatDurationShort, formatRelativeTime } from '@/composables/useFormat'
import StatCard from '@/components/StatCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingSpinner from '@/components/LoadingSpinner.vue'
import ItemIcon from '@/components/ItemIcon.vue'
import ActivityTicker from '@/components/ActivityTicker.vue'
import PnlSparkline from '@/components/PnlSparkline.vue'
import DecisionHeartbeat from '@/components/DecisionHeartbeat.vue'
import MatchedTradesTable from '@/components/MatchedTradesTable.vue'
import { useMatchedTrades } from '@/composables/useMatchedTrades'

const props = defineProps({
  accountHash: { type: String, required: true },
})

const accountHashRef = computed(() => props.accountHash)

const { positions, loading: portfolioLoading, error: portfolioError } = usePortfolio(accountHashRef)
const { entries: buyLimitEntries, loading: buyLimitLoading } = useBuyLimitLedger(accountHashRef)
const { items: watchlistItems, loading: watchlistLoading } = useWatchlist(accountHashRef)
const { request: decisionRequest, response: decisionResponse } = useDecision(accountHashRef)
const { prices, loading: pricesLoading, load: loadPrices, getSellPrice } = useWikiPrices()
const { mapping, loading: mappingLoading, load: loadMapping, getName, getIconUrl, getBuyLimit } = useItemMapping()
const { trades: recentTrades } = useTradeHistory(accountHashRef, { rowLimit: 30 })
// A much larger window than recentTrades above (which only backs the live ticker/sparkline) - FIFO
// matching needs enough history to actually find the BUY lot(s) behind an older SELL, not just the
// last handful of fills. Still bounded (matches useTradeHistory's own default) rather than
// unbounded, per that composable's own reasoning for capping it at all.
const { trades: matchingTrades } = useTradeHistory(accountHashRef, { rowLimit: 500 })
const { matches: matchedTrades } = useMatchedTrades(matchingTrades)

const latestAnsweredAtMillis = computed(() => {
  const answeredAt = decisionResponse.value?.answeredAt
  return answeredAt?.toMillis ? answeredAt.toMillis() : null
})

onMounted(() => {
  loadPrices()
  loadMapping()
  const interval = setInterval(loadPrices, 60_000)
  return () => clearInterval(interval)
})

const enrichedPositions = computed(() =>
  positions.value
    .filter((p) => p.quantityHeld > 0)
    .map((p) => {
      const currentPrice = getSellPrice(p.itemId)
      const currentValue = currentPrice != null ? currentPrice * p.quantityHeld : null
      const unrealized = currentPrice != null ? currentValue - p.totalCostBasis : null
      const unrealizedPct = unrealized != null && p.totalCostBasis > 0 ? unrealized / p.totalCostBasis : null
      return {
        ...p,
        name: getName(p.itemId),
        icon: getIconUrl(p.itemId),
        currentPrice,
        currentValue,
        unrealized,
        unrealizedPct,
      }
    })
    .sort((a, b) => (b.currentValue ?? b.totalCostBasis) - (a.currentValue ?? a.totalCostBasis)),
)

// Per-item profit, INCLUDING fully closed-out positions (quantityHeld === 0) that
// enrichedPositions above deliberately excludes (that table is "what am I holding right now").
// PortfolioManager/CostBasisEntry never deletes an item's ledger doc after it's fully sold - the
// Firestore doc for a closed-out item persists with quantityHeld: 0 and its accumulated
// realizedProfit intact - so this is a pure read of data that was already being synced, just never
// surfaced once a position closed out completely.
const itemProfitSortKey = ref('totalProfit')
const itemProfitRows = computed(() => {
  const currentPriceFor = (itemId) => getSellPrice(itemId)
  const rows = positions.value.map((p) => {
    const currentPrice = currentPriceFor(p.itemId)
    const unrealized =
      p.quantityHeld > 0 && currentPrice != null ? currentPrice * p.quantityHeld - p.totalCostBasis : 0
    return {
      itemId: p.itemId,
      name: getName(p.itemId),
      icon: getIconUrl(p.itemId),
      quantityHeld: p.quantityHeld,
      realizedProfit: p.realizedProfit ?? 0,
      unrealizedProfit: unrealized,
      totalProfit: (p.realizedProfit ?? 0) + unrealized,
      closed: p.quantityHeld === 0,
    }
  })
  // Hide items with no trading history at all (never bought/sold, never held) - a watchlist item
  // with a portfolio doc but zero realized profit and nothing held isn't a "profit" row.
  const withHistory = rows.filter((r) => r.realizedProfit !== 0 || r.quantityHeld > 0)
  return withHistory.sort((a, b) => b[itemProfitSortKey.value] - a[itemProfitSortKey.value])
})

const totalCostBasis = computed(() => positions.value.reduce((sum, p) => sum + (p.totalCostBasis ?? 0), 0))
const totalRealizedProfit = computed(() => positions.value.reduce((sum, p) => sum + (p.realizedProfit ?? 0), 0))
// Distinct from totalRealizedProfit above on purpose: that figure sums the ledger's
// realizedProfit, which includes profit/loss computed against a SEEDED cost basis for stock
// already held before this bot ever tracked it (PortfolioManager.reconcileFromFirestore can
// adopt a remote quantityHeld/totalCostBasis with no real recorded BUY behind it at all) - mixed
// in indistinguishably with genuine bot-driven buy-then-sell flips, which was reported as making
// it hard to tell "how much has the bot actually made" from the headline number. useMatchedTrades
// already solves this correctly per-trade (FIFO-matches each SELL only against BUYs it can
// actually trace within the trade-history window, leaving the rest as profit: null/"unmatched"
// rather than guessing) - this sums only the matched (traceable) portion of that same computation,
// so it's a second, narrower, more trustworthy "bot-driven only" figure alongside the existing
// broader one, not a replacement for it.
const totalBotDrivenRealizedProfit = computed(() =>
  matchedTrades.value.reduce((sum, m) => sum + (m.profit ?? 0), 0),
)
// Count of trades actually feeding totalBotDrivenRealizedProfit above (profit !== null) - shown
// alongside it so the figure's own sample size is visible at a glance, since a couple of large
// trades can otherwise make a thin sample look more conclusive than it is.
const matchedTradeCount = computed(() => matchedTrades.value.filter((m) => m.profit != null).length)
const totalUnrealizedProfit = computed(() => {
  const withPrice = enrichedPositions.value.filter((p) => p.unrealized != null)
  if (withPrice.length === 0) return null
  return withPrice.reduce((sum, p) => sum + p.unrealized, 0)
})
const portfolioValue = computed(() => {
  return enrichedPositions.value.reduce((sum, p) => sum + (p.currentValue ?? p.totalCostBasis), 0)
})

const buyLimitRows = computed(() =>
  buyLimitEntries.value
    .filter((e) => e.quantityInWindow > 0)
    .map((e) => {
      const limit = getBuyLimit(e.itemId)
      return {
        ...e,
        name: getName(e.itemId),
        icon: getIconUrl(e.itemId),
        limit,
        pct: limit ? Math.min(1, e.quantityInWindow / limit) : null,
      }
    })
    .sort((a, b) => (b.pct ?? 0) - (a.pct ?? 0)),
)

const watchlistRows = computed(() =>
  watchlistItems.value
    .map((w) => ({ ...w, name: getName(w.itemId), icon: getIconUrl(w.itemId) }))
    .sort((a, b) => a.name.localeCompare(b.name)),
)

const decisionRows = computed(() => {
  const requestItems = decisionRequest.value?.items ?? []
  const actions = decisionResponse.value?.actions ?? []
  const actionsByItemId = new Map(actions.map((a) => [a.itemId, a]))
  return requestItems.map((item) => {
    const action = actionsByItemId.get(item.itemId)
    return {
      itemId: item.itemId,
      name: getName(item.itemId),
      icon: getIconUrl(item.itemId),
      midPrice: item.midPrice,
      heldQuantity: item.heldQuantity,
      unrealizedPct: item.unrealizedPct,
      action: action?.action ?? null,
      quantity: action?.quantity ?? null,
      price: action?.price ?? null,
      confidence: action?.confidence ?? null,
    }
  })
})

const tickIdsMatch = computed(
  () => decisionRequest.value && decisionResponse.value && decisionRequest.value.tickId === decisionResponse.value.tickId,
)

const actionTone = (action) => {
  if (action === 'BUY') return 'text-[var(--color-profit)] bg-[var(--color-profit-dim)]'
  if (action === 'SELL') return 'text-[var(--color-loss)] bg-[var(--color-loss-dim)]'
  return 'text-[var(--color-text-dim)] bg-[var(--color-surface-3)]'
}
</script>

<template>
  <div class="flex flex-col gap-6">
    <ErrorState
      v-if="portfolioError"
      :permission-denied="portfolioError.code === 'permission-denied'"
      title="Failed to load portfolio"
      :message="portfolioError.message"
    />

    <template v-else>
      <!-- Live activity + recent P&L -->
      <div class="grid grid-cols-1 lg:grid-cols-3 gap-4">
        <ActivityTicker class="lg:col-span-2" :trades="recentTrades" :get-name="getName" :get-icon-url="getIconUrl" />
        <PnlSparkline :trades="recentTrades" />
      </div>

      <!-- Top-line stats -->
      <section class="grid grid-cols-2 lg:grid-cols-6 gap-4">
        <StatCard
          label="Portfolio value"
          :value="portfolioLoading || pricesLoading ? '…' : formatGp(portfolioValue)"
          sub="at current wiki insta-sell price"
        />
        <StatCard
          label="Unrealized P&amp;L"
          :value="portfolioLoading || pricesLoading ? '…' : totalUnrealizedProfit == null ? '—' : formatGp(totalUnrealizedProfit)"
          :tone="totalUnrealizedProfit > 0 ? 'profit' : totalUnrealizedProfit < 0 ? 'loss' : 'neutral'"
          sub="across open positions"
        />
        <StatCard
          label="Realized P&amp;L (all-time)"
          :value="portfolioLoading ? '…' : formatGp(totalRealizedProfit)"
          :tone="totalRealizedProfit > 0 ? 'profit' : totalRealizedProfit < 0 ? 'loss' : 'neutral'"
          sub="from closed trades, per Firestore ledger — includes pre-existing stock"
        />
        <StatCard
          label="Realized P&amp;L (bot-driven only)"
          :value="formatGp(totalBotDrivenRealizedProfit)"
          :tone="totalBotDrivenRealizedProfit > 0 ? 'profit' : totalBotDrivenRealizedProfit < 0 ? 'loss' : 'neutral'"
          sub="FIFO-matched buy→sell pairs only, excludes pre-existing/unmatched stock"
        />
        <StatCard
          label="Matched trades"
          :value="matchedTradeCount.toLocaleString()"
          sub="sample size behind the bot-driven figure — small samples can be misleading"
        />
        <StatCard
          label="Cost basis held"
          :value="portfolioLoading ? '…' : formatGp(totalCostBasis)"
          sub="gp tied up in open positions"
        />
      </section>

      <p class="text-xs text-[var(--color-text-faint)] -mt-3">
        Note: session gold-on-hand / net-worth isn't tracked here — <code class="font-mono-nums">GoldManager</code>'s
        live coin count is local to the running RuneLite client and never synced to Firestore. "Realized P&amp;L
        (all-time)" is reconstructed from the portfolio cost-basis ledger and can include stock held before the bot
        started tracking it; "bot-driven only" instead sums just the trades in "Profit by trade" below that could be
        FIFO-matched to an actual tracked buy, so it won't include a sell whose funding buy fell outside the trade
        history window.
      </p>

      <!-- Portfolio + presence-adjacent panels -->
      <div class="grid grid-cols-1 xl:grid-cols-3 gap-6">
        <section class="xl:col-span-2 rounded-xl border border-[var(--color-border)] bg-[var(--color-surface)] overflow-hidden">
          <div class="px-5 py-4 border-b border-[var(--color-border)] flex items-center justify-between">
            <h2 class="text-sm font-semibold">Portfolio holdings</h2>
            <span class="text-xs text-[var(--color-text-faint)]">{{ enrichedPositions.length }} positions</span>
          </div>
          <LoadingSpinner v-if="portfolioLoading" />
          <EmptyState
            v-else-if="enrichedPositions.length === 0"
            title="No open positions"
            message="Nothing currently held in inventory or bank, per the portfolio ledger."
          />
          <div v-else class="overflow-x-auto">
            <table class="w-full text-sm">
              <thead>
                <tr class="text-left text-xs text-[var(--color-text-faint)] uppercase tracking-wide">
                  <th class="px-5 py-2 font-medium">Item</th>
                  <th class="px-3 py-2 font-medium text-right">Qty</th>
                  <th class="px-3 py-2 font-medium text-right">Avg cost</th>
                  <th class="px-3 py-2 font-medium text-right">Current</th>
                  <th class="px-3 py-2 font-medium text-right">Value</th>
                  <th class="px-5 py-2 font-medium text-right">Unrealized</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="p in enrichedPositions"
                  :key="p.itemId"
                  class="border-t border-[var(--color-border)] hover:bg-[var(--color-surface-2)]"
                >
                  <td class="px-5 py-2.5">
                    <div class="flex items-center gap-2 min-w-0">
                      <ItemIcon :src="p.icon" :name="p.name" :size="20" />
                      <span class="truncate">{{ p.name }}</span>
                    </div>
                  </td>
                  <td class="px-3 py-2.5 text-right font-mono-nums">{{ p.quantityHeld.toLocaleString() }}</td>
                  <td class="px-3 py-2.5 text-right font-mono-nums text-[var(--color-text-dim)]">{{ formatGpExact(p.averageCost) }}</td>
                  <td class="px-3 py-2.5 text-right font-mono-nums text-[var(--color-text-dim)]">
                    {{ p.currentPrice != null ? formatGpExact(p.currentPrice) : '—' }}
                  </td>
                  <td class="px-3 py-2.5 text-right font-mono-nums">{{ formatGp(p.currentValue ?? p.totalCostBasis) }}</td>
                  <td
                    class="px-5 py-2.5 text-right font-mono-nums"
                    :class="p.unrealized > 0 ? 'text-[var(--color-profit)]' : p.unrealized < 0 ? 'text-[var(--color-loss)]' : 'text-[var(--color-text-faint)]'"
                  >
                    <template v-if="p.unrealized != null">
                      {{ p.unrealized >= 0 ? '+' : '' }}{{ formatGp(p.unrealized) }}
                      <span class="text-xs opacity-70">({{ formatPercent(p.unrealizedPct) }})</span>
                    </template>
                    <template v-else>—</template>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <!-- Watchlist -->
        <section class="rounded-xl border border-[var(--color-border)] bg-[var(--color-surface)] overflow-hidden flex flex-col">
          <div class="px-5 py-4 border-b border-[var(--color-border)] flex items-center justify-between">
            <h2 class="text-sm font-semibold">Watchlist</h2>
            <span class="text-xs text-[var(--color-text-faint)]">{{ watchlistRows.length }} items</span>
          </div>
          <LoadingSpinner v-if="watchlistLoading" />
          <EmptyState
            v-else-if="watchlistRows.length === 0"
            title="Watchlist is empty"
            message="No items are currently flagged for autonomous management."
          />
          <ul v-else class="divide-y divide-[var(--color-border)] overflow-y-auto max-h-96">
            <li v-for="w in watchlistRows" :key="w.itemId" class="px-5 py-2.5 flex items-center gap-2 text-sm">
              <ItemIcon :src="w.icon" :name="w.name" :size="18" />
              <span class="truncate">{{ w.name }}</span>
            </li>
          </ul>
        </section>
      </div>

      <!-- Per-item profit, including fully closed-out positions -->
      <section class="rounded-xl border border-[var(--color-border)] bg-[var(--color-surface)] overflow-hidden">
        <div class="px-5 py-4 border-b border-[var(--color-border)] flex items-center justify-between flex-wrap gap-2">
          <div>
            <h2 class="text-sm font-semibold">Profit by item</h2>
            <p class="text-xs text-[var(--color-text-faint)] mt-0.5">
              Realized profit is all-time, from closed trades. Unrealized is only priced for items still held.
              Includes items fully sold off (0 held) — they're excluded from Portfolio holdings above.
            </p>
          </div>
          <div class="flex items-center gap-1 text-xs shrink-0">
            <span class="text-[var(--color-text-faint)] mr-1">Sort by</span>
            <button
              v-for="opt in [{ key: 'totalProfit', label: 'Total' }, { key: 'realizedProfit', label: 'Realized' }, { key: 'unrealizedProfit', label: 'Unrealized' }]"
              :key="opt.key"
              class="px-2 py-1 rounded"
              :class="itemProfitSortKey === opt.key ? 'bg-[var(--color-accent)] text-white' : 'bg-[var(--color-surface-3)] text-[var(--color-text-dim)]'"
              @click="itemProfitSortKey = opt.key"
            >
              {{ opt.label }}
            </button>
          </div>
        </div>
        <LoadingSpinner v-if="portfolioLoading" />
        <EmptyState
          v-else-if="itemProfitRows.length === 0"
          title="No trading history yet"
          message="No item has been bought or sold, per the portfolio ledger."
        />
        <div v-else class="overflow-x-auto">
          <table class="w-full text-sm">
            <thead>
              <tr class="text-left text-xs text-[var(--color-text-faint)] uppercase tracking-wide">
                <th class="px-5 py-2 font-medium">Item</th>
                <th class="px-3 py-2 font-medium text-right">Held</th>
                <th class="px-3 py-2 font-medium text-right">Realized</th>
                <th class="px-3 py-2 font-medium text-right">Unrealized</th>
                <th class="px-5 py-2 font-medium text-right">Total</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in itemProfitRows"
                :key="row.itemId"
                class="border-t border-[var(--color-border)] hover:bg-[var(--color-surface-2)]"
              >
                <td class="px-5 py-2.5">
                  <div class="flex items-center gap-2 min-w-0">
                    <ItemIcon :src="row.icon" :name="row.name" :size="20" />
                    <span class="truncate">{{ row.name }}</span>
                    <span v-if="row.closed" class="text-xs text-[var(--color-text-faint)] shrink-0">(closed)</span>
                  </div>
                </td>
                <td class="px-3 py-2.5 text-right font-mono-nums text-[var(--color-text-dim)]">
                  {{ row.quantityHeld > 0 ? row.quantityHeld.toLocaleString() : '—' }}
                </td>
                <td
                  class="px-3 py-2.5 text-right font-mono-nums"
                  :class="row.realizedProfit > 0 ? 'text-[var(--color-profit)]' : row.realizedProfit < 0 ? 'text-[var(--color-loss)]' : 'text-[var(--color-text-faint)]'"
                >
                  {{ row.realizedProfit >= 0 ? '+' : '' }}{{ formatGp(row.realizedProfit) }}
                </td>
                <td
                  class="px-3 py-2.5 text-right font-mono-nums"
                  :class="row.unrealizedProfit > 0 ? 'text-[var(--color-profit)]' : row.unrealizedProfit < 0 ? 'text-[var(--color-loss)]' : 'text-[var(--color-text-faint)]'"
                >
                  <template v-if="row.quantityHeld > 0">{{ row.unrealizedProfit >= 0 ? '+' : '' }}{{ formatGp(row.unrealizedProfit) }}</template>
                  <template v-else>—</template>
                </td>
                <td
                  class="px-5 py-2.5 text-right font-mono-nums font-semibold"
                  :class="row.totalProfit > 0 ? 'text-[var(--color-profit)]' : row.totalProfit < 0 ? 'text-[var(--color-loss)]' : 'text-[var(--color-text-faint)]'"
                >
                  {{ row.totalProfit >= 0 ? '+' : '' }}{{ formatGp(row.totalProfit) }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- Per-trade FIFO-matched profit -->
      <MatchedTradesTable :matches="matchedTrades" :get-icon-url="getIconUrl" />

      <!-- Buy-limit ledger -->
      <section class="rounded-xl border border-[var(--color-border)] bg-[var(--color-surface)] overflow-hidden">
        <div class="px-5 py-4 border-b border-[var(--color-border)] flex items-center justify-between">
          <h2 class="text-sm font-semibold">Buy-limit headroom (rolling 4h)</h2>
          <span class="text-xs text-[var(--color-text-faint)]">{{ buyLimitRows.length }} items with active limits</span>
        </div>
        <LoadingSpinner v-if="buyLimitLoading" />
        <EmptyState
          v-else-if="buyLimitRows.length === 0"
          title="No active buy-limit usage"
          message="Nothing has been bought in the trailing 4-hour window."
        />
        <div v-else class="p-5 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          <div v-for="row in buyLimitRows" :key="row.itemId" class="flex flex-col gap-1.5">
            <div class="flex items-center justify-between text-sm gap-2">
              <div class="flex items-center gap-2 min-w-0">
                <ItemIcon :src="row.icon" :name="row.name" :size="18" />
                <span class="truncate">{{ row.name }}</span>
              </div>
              <span class="text-xs font-mono-nums text-[var(--color-text-dim)] shrink-0">
                {{ row.quantityInWindow.toLocaleString() }}<template v-if="row.limit">/{{ row.limit.toLocaleString() }}</template>
              </span>
            </div>
            <div class="h-1.5 rounded-full bg-[var(--color-surface-3)] overflow-hidden">
              <div
                class="h-full rounded-full"
                :class="row.pct == null ? 'bg-[var(--color-text-faint)]' : row.pct >= 1 ? 'bg-[var(--color-loss)]' : row.pct >= 0.75 ? 'bg-[var(--color-warn)]' : 'bg-[var(--color-accent)]'"
                :style="{ width: `${(row.pct ?? 0) * 100}%` }"
              />
            </div>
          </div>
        </div>
      </section>

      <!-- Model decision handshake -->
      <section class="rounded-xl border border-[var(--color-border)] bg-[var(--color-surface)] overflow-hidden">
        <div class="px-5 py-4 border-b border-[var(--color-border)] flex items-center justify-between flex-wrap gap-2">
          <div>
            <h2 class="text-sm font-semibold">Latest model decision</h2>
            <p class="text-xs text-[var(--color-text-faint)] mt-0.5">
              Read-only view of the PPO handshake — what the model was shown, and what it suggested. No action can be
              confirmed or executed from this dashboard.
            </p>
          </div>
          <div class="flex items-center gap-4 text-xs text-[var(--color-text-dim)] shrink-0">
            <DecisionHeartbeat :answered-at-millis="latestAnsweredAtMillis" />
            <span v-if="decisionResponse?.checkpointVersion" class="font-mono-nums">
              checkpoint: {{ decisionResponse.checkpointVersion }}
            </span>
            <span
              v-if="decisionRequest || decisionResponse"
              class="px-2 py-0.5 rounded-full text-xs"
              :class="tickIdsMatch ? 'bg-[var(--color-profit-dim)] text-[var(--color-profit)]' : 'bg-[var(--color-warn)]/20 text-[var(--color-warn)]'"
            >
              {{ tickIdsMatch ? 'in sync' : 'stale response' }}
            </span>
          </div>
        </div>
        <EmptyState
          v-if="!decisionRequest && !decisionResponse"
          title="No decision tick yet"
          message="The plugin hasn't written a decision/request document for this account, or the inference worker hasn't answered one yet."
        />
        <div v-else class="overflow-x-auto">
          <table class="w-full text-sm">
            <thead>
              <tr class="text-left text-xs text-[var(--color-text-faint)] uppercase tracking-wide">
                <th class="px-5 py-2 font-medium">Item</th>
                <th class="px-3 py-2 font-medium text-right">Mid price</th>
                <th class="px-3 py-2 font-medium text-right">Held</th>
                <th class="px-3 py-2 font-medium text-right">Unrealized</th>
                <th class="px-3 py-2 font-medium">Suggested action</th>
                <th class="px-3 py-2 font-medium text-right">Qty / price</th>
                <th class="px-5 py-2 font-medium text-right">Confidence</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in decisionRows"
                :key="row.itemId"
                class="border-t border-[var(--color-border)] hover:bg-[var(--color-surface-2)]"
              >
                <td class="px-5 py-2.5">
                  <div class="flex items-center gap-2 min-w-0">
                    <ItemIcon :src="row.icon" :name="row.name" :size="18" />
                    <span class="truncate">{{ row.name }}</span>
                  </div>
                </td>
                <td class="px-3 py-2.5 text-right font-mono-nums text-[var(--color-text-dim)]">{{ formatGpExact(row.midPrice) }}</td>
                <td class="px-3 py-2.5 text-right font-mono-nums">{{ row.heldQuantity.toLocaleString() }}</td>
                <td class="px-3 py-2.5 text-right font-mono-nums text-[var(--color-text-dim)]">{{ formatPercent(row.unrealizedPct) }}</td>
                <td class="px-3 py-2.5">
                  <span v-if="row.action" class="px-2 py-0.5 rounded text-xs font-semibold" :class="actionTone(row.action)">
                    {{ row.action }}
                  </span>
                  <span v-else class="text-xs text-[var(--color-text-faint)]">awaiting response</span>
                </td>
                <td class="px-3 py-2.5 text-right font-mono-nums text-[var(--color-text-dim)]">
                  <template v-if="row.quantity">{{ row.quantity.toLocaleString() }} @ {{ formatGpExact(row.price) }}</template>
                  <template v-else>—</template>
                </td>
                <td class="px-5 py-2.5 text-right font-mono-nums text-[var(--color-text-dim)]">
                  {{ row.confidence != null ? formatPercent(row.confidence) : '—' }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
  </div>
</template>
