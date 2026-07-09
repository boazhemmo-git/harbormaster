import { useMemo, useState } from 'react'
import MapView from './MapView'
import AlertFeed from './AlertFeed'
import StatsBar from './StatsBar'
import VesselCard from './VesselCard'
import { useLiveFeed } from './useLiveFeed'
import type { Alert } from './types'
import { SHIP_CATEGORY } from './types'

export default function App() {
  const { vessels, alerts, stats, connected, revision } = useLiveFeed()
  const [selectedMmsi, setSelectedMmsi] = useState<number | null>(null)
  const [focus, setFocus] = useState<{ lat: number; lon: number; key: number } | null>(null)

  const selected = selectedMmsi != null ? vessels.get(selectedMmsi) ?? null : null

  function focusAlert(alert: Alert) {
    setSelectedMmsi(alert.mmsi)
    setFocus({ lat: alert.lat, lon: alert.lon, key: Date.now() })
  }

  // revision keeps this in sync with map updates without re-deriving per render
  const vesselCount = useMemo(() => vessels.size, [vessels, revision])

  return (
    <div className="app">
      <StatsBar stats={stats} connected={connected} vesselCount={vesselCount} />
      <main>
        <MapView
          vessels={vessels}
          alerts={alerts}
          revision={revision}
          selectedMmsi={selectedMmsi}
          onSelect={setSelectedMmsi}
          focus={focus}
        />
        <AlertFeed alerts={alerts} onFocus={focusAlert} />
        {selected && <VesselCard vessel={selected} onClose={() => setSelectedMmsi(null)} />}
        <footer className="legend">
          {Object.entries(SHIP_CATEGORY).map(([key, { label, color: [r, g, b] }]) => (
            <span key={key}>
              <i style={{ background: `rgb(${r},${g},${b})` }} /> {label}
            </span>
          ))}
          <span className="legend-sep">·</span>
          <span><i className="legend-lost" /> Last seen before going dark</span>
        </footer>
      </main>
    </div>
  )
}
