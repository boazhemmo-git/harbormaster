import type { Alert } from './types'
import { ALERT_META } from './types'

interface Props {
  alerts: Alert[]
  onFocus: (alert: Alert) => void
}

function timeLabel(iso: string): string {
  const date = new Date(iso)
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

export default function AlertFeed({ alerts, onFocus }: Props) {
  return (
    <aside className="alert-feed">
      <header>
        <h2>Alerts</h2>
        <span className="alert-count">{alerts.length}</span>
      </header>
      {alerts.length === 0 && (
        <p className="alert-empty">Watching the fleet — anomalies will appear here.</p>
      )}
      <ul>
        {alerts.map(alert => (
          <li
            key={alert.id}
            className={`alert-item severity-${alert.severity.toLowerCase()}`}
            onClick={() => onFocus(alert)}
          >
            <div className="alert-item-head">
              <span className="alert-icon">{ALERT_META[alert.type]?.icon ?? '❗'}</span>
              <span className="alert-label">{ALERT_META[alert.type]?.label ?? alert.type}</span>
              <time>{timeLabel(alert.time)}</time>
            </div>
            <p>{alert.message}</p>
          </li>
        ))}
      </ul>
    </aside>
  )
}
