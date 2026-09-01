import { ref } from 'vue'

// OSRS Wiki's item mapping endpoint — same public API family as useWikiPrices.js's /latest.
// Gives us item name + icon filename + GE buy limit per item id, all of which the Firestore
// documents themselves don't carry in full (tradeHistory does store itemName, but
// portfolio/buyLimitLedger/watchlist only store a bare itemId) — used to render icons and to
// show "X / limit" buy-limit headroom without duplicating a static item table into this repo.
const MAPPING_URL = 'https://prices.runescape.wiki/api/v1/osrs/mapping'
const ICON_BASE_URL = 'https://oldschool.runescape.wiki/images/'

let cachedMapping = null
let inflightPromise = null

async function fetchMapping() {
  if (cachedMapping) return cachedMapping
  if (inflightPromise) return inflightPromise

  inflightPromise = fetch(MAPPING_URL)
    .then((res) => {
      if (!res.ok) throw new Error(`Wiki mapping API returned HTTP ${res.status}`)
      return res.json()
    })
    .then((list) => {
      const byId = new Map()
      for (const entry of list) {
        byId.set(entry.id, entry)
      }
      cachedMapping = byId
      return byId
    })
    .finally(() => {
      inflightPromise = null
    })

  return inflightPromise
}

export function iconUrlForName(name) {
  if (!name) return null
  return ICON_BASE_URL + encodeURIComponent(name.replace(/ /g, '_')) + '.png'
}

export function useItemMapping() {
  const mapping = ref(new Map()) // itemId (number) -> { id, name, icon, limit, ... }
  const loading = ref(true)
  const error = ref(null)

  async function load() {
    loading.value = true
    error.value = null
    try {
      mapping.value = await fetchMapping()
    } catch (err) {
      error.value = err
    } finally {
      loading.value = false
    }
  }

  function getName(itemId, fallback = null) {
    return mapping.value.get(Number(itemId))?.name ?? fallback ?? `Item ${itemId}`
  }

  function getIconUrl(itemId) {
    const entry = mapping.value.get(Number(itemId))
    return entry ? iconUrlForName(entry.icon.replace(/\.png$/i, '')) : null
  }

  function getBuyLimit(itemId) {
    return mapping.value.get(Number(itemId))?.limit ?? null
  }

  return { mapping, loading, error, load, getName, getIconUrl, getBuyLimit }
}
