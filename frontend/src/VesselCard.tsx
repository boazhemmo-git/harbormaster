import type { Vessel } from './types'
import { NAV_STATUS, SHIP_CATEGORY, shipCategory } from './types'

interface Props {
  vessel: Vessel
  onClose: () => void
}

export default function VesselCard({ vessel, onClose }: Props) {
  const category = SHIP_CATEGORY[shipCategory(vessel.shipType)]
  const [r, g, b] = category.color
  return (
    <div className="vessel-card">
      <header>
        <span className="vessel-type-dot" style={{ background: `rgb(${r},${g},${b})` }} />
        <h3>{vessel.name ?? `MMSI ${vessel.mmsi}`}</h3>
        <button onClick={onClose} aria-label="Close">×</button>
      </header>
      <dl>
        <div><dt>MMSI</dt><dd>{vessel.mmsi}</dd></div>
        {vessel.callsign && <div><dt>Callsign</dt><dd>{vessel.callsign}</dd></div>}
        <div><dt>Type</dt><dd>{category.label}</dd></div>
        {vessel.lengthM > 0 && <div><dt>Size</dt><dd>{vessel.lengthM} × {vessel.beamM} m</dd></div>}
        <div><dt>Speed</dt><dd>{vessel.sogKn != null ? `${vessel.sogKn.toFixed(1)} kn` : '—'}</dd></div>
        <div><dt>Course</dt><dd>{vessel.cogDeg != null ? `${Math.round(vessel.cogDeg)}°` : '—'}</dd></div>
        <div><dt>Status</dt><dd>{NAV_STATUS[vessel.navStatus] ?? '—'}</dd></div>
        {vessel.destination && <div><dt>Destination</dt><dd>{vessel.destination}</dd></div>}
        <div><dt>Track</dt><dd className={`track-state ${vessel.state.toLowerCase()}`}>{vessel.state}</dd></div>
      </dl>
    </div>
  )
}
