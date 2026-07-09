import { useEffect, useRef, useState } from 'react'
import type { Alert, LiveStats, Vessel } from './types'

interface LiveFeed {
  vessels: Map<number, Vessel>
  alerts: Alert[]
  stats: LiveStats | null
  connected: boolean
  /** Bumped on every positions frame so memoized layers know to refresh. */
  revision: number
}

const MAX_ALERTS = 200

/**
 * Owns the live state: fetches the initial REST snapshot, then applies
 * WebSocket delta frames. Reconnects with capped backoff — the demo
 * should survive a backend restart without a page reload.
 */
export function useLiveFeed(): LiveFeed {
  const vesselsRef = useRef<Map<number, Vessel>>(new Map())
  const [alerts, setAlerts] = useState<Alert[]>([])
  const [stats, setStats] = useState<LiveStats | null>(null)
  const [connected, setConnected] = useState(false)
  const [revision, setRevision] = useState(0)

  useEffect(() => {
    let disposed = false
    let socket: WebSocket | null = null
    let backoffMs = 1000

    async function loadSnapshot() {
      const [vesselsRes, alertsRes] = await Promise.all([
        fetch('/api/vessels'),
        fetch('/api/alerts?limit=100'),
      ])
      if (!vesselsRes.ok || !alertsRes.ok) return
      const vessels: Vessel[] = await vesselsRes.json()
      const recentAlerts: Alert[] = await alertsRes.json()
      if (disposed) return
      vesselsRef.current = new Map(vessels.map(v => [v.mmsi, v]))
      setAlerts(recentAlerts)
      setRevision(r => r + 1)
    }

    function connect() {
      if (disposed) return
      const protocol = location.protocol === 'https:' ? 'wss' : 'ws'
      socket = new WebSocket(`${protocol}://${location.host}/ws/live`)

      socket.onopen = () => {
        setConnected(true)
        backoffMs = 1000
        // Re-sync after reconnect: deltas alone can't fill a gap.
        void loadSnapshot()
      }

      socket.onmessage = event => {
        const frame = JSON.parse(event.data)
        if (frame.type === 'positions') {
          for (const vessel of frame.updates as Vessel[]) {
            vesselsRef.current.set(vessel.mmsi, vessel)
          }
          setRevision(r => r + 1)
        } else if (frame.type === 'alert') {
          setAlerts(prev => [frame.alert as Alert, ...prev].slice(0, MAX_ALERTS))
        } else if (frame.type === 'stats') {
          setStats(frame.stats as LiveStats)
        }
      }

      socket.onclose = () => {
        setConnected(false)
        if (!disposed) {
          setTimeout(connect, backoffMs)
          backoffMs = Math.min(backoffMs * 2, 15000)
        }
      }
      socket.onerror = () => socket?.close()
    }

    void loadSnapshot()
    connect()
    return () => {
      disposed = true
      socket?.close()
    }
  }, [])

  return { vessels: vesselsRef.current, alerts, stats, connected, revision }
}
