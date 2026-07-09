import type { LiveStats } from './types'

interface Props {
  stats: LiveStats | null
  connected: boolean
  vesselCount: number
}

export default function StatsBar({ stats, connected, vesselCount }: Props) {
  return (
    <header className="stats-bar">
      <div className="brand">
        <span className="brand-mark">⚓</span>
        <h1>Harbormaster</h1>
        <span className="brand-sub">maritime traffic intelligence</span>
      </div>
      <div className="stats-chips">
        <span className={`chip mode ${connected ? 'ok' : 'down'}`}>
          {connected ? (stats?.mode ?? 'CONNECTED') : 'RECONNECTING…'}
        </span>
        <span className="chip">
          <strong>{vesselCount}</strong> vessels
        </span>
        <span className="chip">
          <strong>{stats?.messagesPerSec ?? '—'}</strong> msg/s
        </span>
        <span className="chip">
          <strong>{stats ? stats.decoded.toLocaleString() : '—'}</strong> decoded
        </span>
        <span className="chip" title="Pipeline p99: raw line received → track updated">
          p99 <strong>{stats ? formatMicros(stats.latencyP99Micros) : '—'}</strong>
        </span>
      </div>
    </header>
  )
}

function formatMicros(micros: number): string {
  if (micros < 1000) return `${micros} µs`
  return `${(micros / 1000).toFixed(1)} ms`
}
