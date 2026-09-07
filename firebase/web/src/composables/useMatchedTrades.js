import { computed } from 'vue'
import { netSellProceeds } from '@/composables/useGeTax'

/**
 * Turns a flat list of BUY/SELL fills (from useTradeHistory) into one row per closed round-trip,
 * matched FIFO per item: each SELL consumes the OLDEST not-yet-consumed BUY lot(s) for that same
 * item first, splitting a lot across multiple sells (or a sell across multiple lots) exactly as
 * real FIFO accounting would. This is a genuinely different, more concrete answer than
 * PortfolioManager's weighted-average-cost ledger (what the Dashboard's "Profit by item" table
 * already shows) - "which specific buy became which specific sell, and was THAT pair profitable"
 * rather than a running average blended across every purchase of an item ever.
 *
 * Deliberately a pure client-side computation over data useTradeHistory already has live - no new
 * Firestore collection, no plugin-side change. Note the accuracy tradeoff this inherits from
 * useTradeHistory's own rowLimit cap (default 500, newest-first): a SELL whose funding BUY(s)
 * fell outside that window has no matching lot to attribute to, and is surfaced as "unmatched"
 * (cost basis unknown) rather than guessed at - same "don't fabricate what isn't known" principle
 * CostBasisEntry itself already follows for pre-ledger holdings.
 */
export function useMatchedTrades(tradesRef) {
  const matches = computed(() => {
    const trades = tradesRef.value
    if (!trades || trades.length === 0) return []

    // Oldest-first processing order is required for FIFO to consume lots correctly, regardless
    // of whatever order the source list itself was in (useTradeHistory returns newest-first).
    const chronological = [...trades].sort((a, b) => a.timestampMillis - b.timestampMillis)

    // itemId -> array of { remainingQuantity, pricePerUnit, timestampMillis }, oldest lot first.
    const openLots = new Map()
    const rows = []

    for (const trade of chronological) {
      if (trade.action === 'BUY') {
        const lots = openLots.get(trade.itemId) ?? []
        lots.push({ remainingQuantity: trade.quantity, pricePerUnit: trade.pricePerUnit, timestampMillis: trade.timestampMillis })
        openLots.set(trade.itemId, lots)
        continue
      }

      // SELL: consume oldest lot(s) first until this sell's quantity is fully accounted for.
      const lots = openLots.get(trade.itemId) ?? []
      let remainingToMatch = trade.quantity
      let matchedQuantity = 0
      let totalCostOfMatched = 0
      const consumedFrom = []

      while (remainingToMatch > 0 && lots.length > 0) {
        const lot = lots[0]
        const takeFromLot = Math.min(lot.remainingQuantity, remainingToMatch)
        matchedQuantity += takeFromLot
        totalCostOfMatched += takeFromLot * lot.pricePerUnit
        consumedFrom.push({ quantity: takeFromLot, pricePerUnit: lot.pricePerUnit, timestampMillis: lot.timestampMillis })
        lot.remainingQuantity -= takeFromLot
        remainingToMatch -= takeFromLot
        if (lot.remainingQuantity <= 0) lots.shift()
      }

      const unmatchedQuantity = trade.quantity - matchedQuantity
      const netProceeds = netSellProceeds(trade.pricePerUnit, trade.quantity)
      // Unmatched portion (stock predating this trade history window, or held before this ledger
      // ever started tracking it) contributes proceeds with no known cost - excluded from the
      // profit figure entirely rather than assuming a cost of 0, which would overstate profit.
      const matchedProceeds = trade.quantity > 0 ? netProceeds * (matchedQuantity / trade.quantity) : 0
      const profit = matchedQuantity > 0 ? matchedProceeds - totalCostOfMatched : null
      const avgBuyPrice = matchedQuantity > 0 ? totalCostOfMatched / matchedQuantity : null

      rows.push({
        id: trade.id,
        itemId: trade.itemId,
        itemName: trade.itemName,
        sellQuantity: trade.quantity,
        sellPricePerUnit: trade.pricePerUnit,
        sellTimestampMillis: trade.timestampMillis,
        matchedQuantity,
        unmatchedQuantity,
        avgBuyPrice,
        profit,
        consumedFrom,
      })
    }

    // Newest-first for display, matching every other table in this dashboard.
    return rows.sort((a, b) => b.sellTimestampMillis - a.sellTimestampMillis)
  })

  return { matches }
}
