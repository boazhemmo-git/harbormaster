/** Wire types mirrored from the backend DTOs. */

export type TrackState = 'ACQUIRING' | 'ACTIVE' | 'COASTING' | 'LOST'

export interface Vessel {
  mmsi: number
  name: string | null
  callsign: string | null
  shipType: number
  lengthM: number
  beamM: number
  destination: string | null
  lat: number
  lon: number
  sogKn: number | null
  cogDeg: number | null
  heading: number | null
  navStatus: number
  state: TrackState
  lastSeen: string
  trail: [number, number][] | null
}

export type AlertType = 'AIS_GAP' | 'KINEMATIC_ANOMALY' | 'LOITERING' | 'RENDEZVOUS'
export type Severity = 'INFO' | 'WARNING' | 'CRITICAL'

export interface Alert {
  id: string
  time: string
  type: AlertType
  severity: Severity
  mmsi: number
  vesselName: string
  lat: number
  lon: number
  message: string
  details: Record<string, unknown>
}

export interface LiveStats {
  mode: string
  messagesPerSec: number
  decoded: number
  decodeErrors: number
  vessels: number
  latencyP99Micros: number
}

export const SHIP_CATEGORY: Record<string, { label: string; color: [number, number, number] }> = {
  cargo: { label: 'Cargo', color: [96, 165, 250] },
  tanker: { label: 'Tanker', color: [251, 146, 60] },
  passenger: { label: 'Passenger', color: [74, 222, 128] },
  fishing: { label: 'Fishing', color: [45, 212, 191] },
  service: { label: 'Tug / Pilot', color: [196, 181, 253] },
  other: { label: 'Other', color: [148, 163, 184] },
}

export function shipCategory(shipType: number): keyof typeof SHIP_CATEGORY {
  if (shipType >= 70 && shipType <= 79) return 'cargo'
  if (shipType >= 80 && shipType <= 89) return 'tanker'
  if (shipType >= 60 && shipType <= 69) return 'passenger'
  if (shipType === 30) return 'fishing'
  if (shipType >= 50 && shipType <= 56) return 'service'
  return 'other'
}

export const NAV_STATUS: Record<number, string> = {
  0: 'Under way (engine)',
  1: 'At anchor',
  2: 'Not under command',
  3: 'Restricted manoeuvrability',
  4: 'Constrained by draught',
  5: 'Moored',
  6: 'Aground',
  7: 'Fishing',
  8: 'Under way (sailing)',
  15: 'Undefined',
}

export const ALERT_META: Record<AlertType, { icon: string; label: string }> = {
  AIS_GAP: { icon: '🕳️', label: 'Went dark' },
  KINEMATIC_ANOMALY: { icon: '⚡', label: 'Position spoofing' },
  LOITERING: { icon: '🛑', label: 'Loitering' },
  RENDEZVOUS: { icon: '🔗', label: 'Rendezvous' },
}
