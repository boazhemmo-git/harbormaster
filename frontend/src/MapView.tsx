import { useEffect, useRef } from 'react'
import maplibregl from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { MapboxOverlay } from '@deck.gl/mapbox'
import { IconLayer, PathLayer, ScatterplotLayer } from '@deck.gl/layers'
import type { Alert, Vessel } from './types'
import { SHIP_CATEGORY, shipCategory } from './types'

const MAP_STYLE = 'https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json'

// A north-pointing chevron; deck.gl rotates it by heading/course.
const VESSEL_ICON_SVG = `<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24">
  <path d="M12 2 L19 20 L12 15.5 L5 20 Z" fill="#fff"/>
</svg>`
const VESSEL_ICON_URL = `data:image/svg+xml;base64,${btoa(VESSEL_ICON_SVG)}`

interface Props {
  vessels: Map<number, Vessel>
  alerts: Alert[]
  revision: number
  selectedMmsi: number | null
  onSelect: (mmsi: number | null) => void
  focus: { lat: number; lon: number; key: number } | null
}

export default function MapView({ vessels, alerts, revision, selectedMmsi, onSelect, focus }: Props) {
  const containerRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<maplibregl.Map | null>(null)
  const overlayRef = useRef<MapboxOverlay | null>(null)
  const didFitRef = useRef(false)

  useEffect(() => {
    // ?lat=&lon=&zoom= pins the camera (useful for recording demos); without
    // it the map frames the fleet automatically on first data.
    const params = new URLSearchParams(location.search)
    const fixedLat = params.get('lat')
    const fixedLon = params.get('lon')
    const fixedZoom = params.get('zoom')
    if (fixedLat && fixedLon) {
      didFitRef.current = true
    }
    const map = new maplibregl.Map({
      container: containerRef.current!,
      style: MAP_STYLE,
      center: [fixedLon ? Number(fixedLon) : 3.6, fixedLat ? Number(fixedLat) : 52.2],
      zoom: fixedZoom ? Number(fixedZoom) : 7,
      attributionControl: { compact: true },
    })
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'bottom-right')
    const overlay = new MapboxOverlay({ layers: [] })
    map.addControl(overlay)
    mapRef.current = map
    overlayRef.current = overlay
    return () => {
      map.remove()
      mapRef.current = null
    }
  }, [])

  // Fly to a vessel when an alert is clicked.
  useEffect(() => {
    if (focus && mapRef.current) {
      mapRef.current.flyTo({ center: [focus.lon, focus.lat], zoom: 11, duration: 1200 })
    }
  }, [focus])

  useEffect(() => {
    const overlay = overlayRef.current
    const map = mapRef.current
    if (!overlay || !map) return

    const list = [...vessels.values()].filter(v => v.state !== 'LOST')
    const lostList = [...vessels.values()].filter(v => v.state === 'LOST')

    // First data: frame the fleet once, then leave the camera to the user.
    if (!didFitRef.current && list.length > 10) {
      didFitRef.current = true
      const lons = list.map(v => v.lon)
      const lats = list.map(v => v.lat)
      map.fitBounds(
        [[Math.min(...lons), Math.min(...lats)], [Math.max(...lons), Math.max(...lats)]],
        { padding: 60, duration: 800, maxZoom: 9 },
      )
    }

    const alertedMmsi = new Set(alerts.slice(0, 25).map(a => a.mmsi))

    overlay.setProps({
      layers: [
        new PathLayer<Vessel>({
          id: 'trails',
          data: list.filter(v => (v.trail?.length ?? 0) > 1),
          getPath: v => (v.trail ?? []).map(([lat, lon]) => [lon, lat] as [number, number]),
          getColor: v => [...SHIP_CATEGORY[shipCategory(v.shipType)].color, 70] as [number, number, number, number],
          getWidth: 1.5,
          widthUnits: 'pixels',
          capRounded: true,
        }),
        // Last-known positions of vessels that went dark: hollow red markers.
        new ScatterplotLayer<Vessel>({
          id: 'lost-vessels',
          data: lostList,
          getPosition: v => [v.lon, v.lat],
          getRadius: 7,
          radiusUnits: 'pixels',
          stroked: true,
          filled: false,
          getLineColor: [248, 113, 113, 200],
          getLineWidth: 2,
          lineWidthUnits: 'pixels',
          pickable: true,
          onClick: info => onSelect(info.object ? (info.object as Vessel).mmsi : null),
        }),
        // Alert halo behind implicated vessels.
        new ScatterplotLayer<Vessel>({
          id: 'alert-halo',
          data: list.filter(v => alertedMmsi.has(v.mmsi)),
          getPosition: v => [v.lon, v.lat],
          getRadius: 14,
          radiusUnits: 'pixels',
          stroked: true,
          filled: true,
          getFillColor: [239, 68, 68, 35],
          getLineColor: [239, 68, 68, 180],
          getLineWidth: 1.5,
          lineWidthUnits: 'pixels',
        }),
        new IconLayer<Vessel>({
          id: 'vessels',
          data: list,
          getPosition: v => [v.lon, v.lat],
          getIcon: () => ({ url: VESSEL_ICON_URL, width: 24, height: 24, mask: true }),
          getSize: v => (v.mmsi === selectedMmsi ? 26 : 17),
          getAngle: v => 360 - (v.heading ?? v.cogDeg ?? 0),
          getColor: v => {
            const base = SHIP_CATEGORY[shipCategory(v.shipType)].color
            const dim = v.state === 'COASTING' ? 120 : 255
            return [...base, dim] as [number, number, number, number]
          },
          pickable: true,
          onClick: info => onSelect(info.object ? (info.object as Vessel).mmsi : null),
          updateTriggers: { getSize: selectedMmsi, getColor: revision },
        }),
      ],
    })
  }, [revision, vessels, alerts, selectedMmsi, onSelect])

  return <div ref={containerRef} className="map-container" onContextMenu={e => e.preventDefault()} />
}
