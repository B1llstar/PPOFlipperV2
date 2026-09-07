// Mirrors plugins/ppo-flipper-star/.../GeTax.java exactly (same rate, cap, per-unit exemption
// floor) - see that class's own javadoc for why: 2% of gross sale proceeds, floored to a whole
// gp, capped at 5,000,000gp per sale, waived entirely when the item's per-unit price is below
// 50gp. Needed here (not just server-side) so the dashboard's matched-trade profit figures match
// what the plugin itself actually realizes, not a naive gross-proceeds difference.
const RATE = 0.02
const CAP = 5_000_000
const EXEMPT_BELOW_UNIT_PRICE = 50

export function computeGeTax(unitPrice, quantity) {
  if (unitPrice < EXEMPT_BELOW_UNIT_PRICE || quantity <= 0) return 0
  const gross = unitPrice * quantity
  const tax = Math.floor(gross * RATE)
  return Math.min(tax, CAP)
}

export function netSellProceeds(unitPrice, quantity) {
  return unitPrice * quantity - computeGeTax(unitPrice, quantity)
}
