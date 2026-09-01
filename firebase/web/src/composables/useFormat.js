// Shared GP/number/date formatting helpers used across every view — kept in one place so a
// "1.2m gp" style formatter reads identically everywhere in the dashboard.

export function formatGp(value) {
  if (value == null || Number.isNaN(value)) return '—'
  const n = Number(value)
  const sign = n < 0 ? '-' : ''
  const abs = Math.abs(n)
  if (abs >= 1_000_000_000) return `${sign}${(abs / 1_000_000_000).toFixed(2)}b`
  if (abs >= 1_000_000) return `${sign}${(abs / 1_000_000).toFixed(2)}m`
  if (abs >= 10_000) return `${sign}${(abs / 1_000).toFixed(1)}k`
  return `${sign}${abs.toLocaleString()}`
}

export function formatGpExact(value) {
  if (value == null || Number.isNaN(value)) return '—'
  return Number(value).toLocaleString()
}

export function formatNumber(value) {
  if (value == null || Number.isNaN(value)) return '—'
  return Number(value).toLocaleString()
}

export function formatPercent(value, digits = 1) {
  if (value == null || Number.isNaN(value)) return '—'
  return `${(Number(value) * 100).toFixed(digits)}%`
}

export function formatRelativeTime(millis) {
  if (millis == null) return 'never'
  const diffMs = Date.now() - millis
  const diffSec = Math.round(diffMs / 1000)
  if (diffSec < 5) return 'just now'
  if (diffSec < 60) return `${diffSec}s ago`
  const diffMin = Math.round(diffSec / 60)
  if (diffMin < 60) return `${diffMin}m ago`
  const diffHr = Math.round(diffMin / 60)
  if (diffHr < 24) return `${diffHr}h ago`
  const diffDay = Math.round(diffHr / 24)
  return `${diffDay}d ago`
}

export function formatDateTime(millis) {
  if (millis == null) return '—'
  return new Date(millis).toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

export function formatDurationShort(ms) {
  if (ms == null || ms < 0) return '—'
  const seconds = Math.floor(ms / 1000)
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  if (days > 0) return `${days}d ${hours}h`
  if (hours > 0) return `${hours}h ${minutes}m`
  if (minutes > 0) return `${minutes}m`
  return `${seconds}s`
}
