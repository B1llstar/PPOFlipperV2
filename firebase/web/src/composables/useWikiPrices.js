import { ref } from 'vue'

// OSRS Wiki real-time prices API — the same public, no-auth endpoint the Java plugin's
// WikiPriceClient calls server-side (see PROPOSAL.md §2.2/§2.3: "must call the OSRS Wiki's
// real-time API directly, never [a third-party aggregator]"). Public, read-only, CORS-enabled —
// confirmed reachable directly from a browser (no API key, no proxy needed) by fetching it live
// during development of this dashboard.
const LATEST_PRICES_URL = 'https://prices.runescape.wiki/api/v1/osrs/latest'

// The wiki API asks integrations to identify themselves via User-Agent, but browser fetch()
// cannot set a custom User-Agent header (the browser controls that header) — this is a known,
// accepted limitation of calling this API directly from client-side JS rather than through a
// backend, and the endpoint remains usable without one for reasonable request volumes.

let cachedPrices = null
let cachedAt = 0
let inflightPromise = null
const CACHE_TTL_MILLIS = 60 * 1000 // wiki data itself only updates every ~60s server-side

/**
 * Fetches (with a shared 60s cache across every caller) a map of itemId -> { high, highTime, low, lowTime }
 * from the OSRS Wiki's /latest endpoint. `high`/`low` are the most recent insta-buy/insta-sell
 * prices respectively (wiki API terminology: "high" = price paid to insta-buy, "low" = price
 * received to insta-sell) — same semantics PortfolioManager#getTotalUnrealizedProfit expects
 * (valuing a held position at what could actually be recovered by selling now, i.e. the `low`
 * price).
 */
async function fetchLatestPrices() {
  const now = Date.now()
  if (cachedPrices && now - cachedAt < CACHE_TTL_MILLIS) {
    return cachedPrices
  }
  if (inflightPromise) {
    return inflightPromise
  }

  inflightPromise = fetch(LATEST_PRICES_URL)
    .then((res) => {
      if (!res.ok) throw new Error(`Wiki prices API returned HTTP ${res.status}`)
      return res.json()
    })
    .then((json) => {
      cachedPrices = json.data ?? {}
      cachedAt = Date.now()
      return cachedPrices
    })
    .finally(() => {
      inflightPromise = null
    })

  return inflightPromise
}

export function useWikiPrices() {
  const prices = ref({}) // itemId (string) -> { high, highTime, low, lowTime }
  const loading = ref(true)
  const error = ref(null)

  async function load() {
    loading.value = true
    error.value = null
    try {
      prices.value = await fetchLatestPrices()
    } catch (err) {
      error.value = err
    } finally {
      loading.value = false
    }
  }

  /** Best-available current price for an item: insta-sell (`low`) preferred for valuing a held position, falling back to insta-buy (`high`), then null if the wiki has no data for this item at all. */
  function getSellPrice(itemId) {
    const entry = prices.value[String(itemId)]
    if (!entry) return null
    return entry.low ?? entry.high ?? null
  }

  return { prices, loading, error, load, getSellPrice }
}
