import { useEffect, useRef, useState } from 'react'
import { Calendar } from '@bryntum/calendar'
import '@bryntum/calendar/calendar.css'
import '@bryntum/calendar/stockholm-light.css'
import '@bryntum/calendar/fontawesome/css/fontawesome.css'
import '@bryntum/calendar/fontawesome/css/solid.css'
import { get, type TimelineEvent } from '../api'

const COLORS: Record<TimelineEvent['kind'], string> = {
  INVOICE_DUE: 'blue',
  PROMISE_DUE: 'green',
  FOLLOW_UP: 'orange',
}

/** Bryntum calendar: invoice due dates, promised payments and agent follow-ups. */
export default function TimelinePage() {
  const host = useRef<HTMLDivElement>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    if (!host.current) return
    const calendar = new Calendar({
      appendTo: host.current,
      modes: {
        day: null,
        week: null,
        agenda: null,
        year: null,
      },
    })
    let cancelled = false
    get<TimelineEvent[]>('/api/timeline')
      .then((events) => {
        if (cancelled) return
        calendar.eventStore.data = events.map((e) => ({
          name: e.label,
          startDate: `${e.date}T09:00:00`,
          endDate: `${e.date}T10:00:00`,
          eventColor: COLORS[e.kind] ?? 'gray',
        }))
        calendar.date = new Date()
      })
      .catch((e) => setError(e.message))
    return () => {
      cancelled = true
      calendar.destroy()
    }
  }, [])

  return (
    <div className="timeline-page">
      <header className="page-header">
        <div>
          <h1>Timeline</h1>
          <p className="muted">
            <span className="legend legend-blue">Invoice due</span>
            <span className="legend legend-green">Promised payment</span>
            <span className="legend legend-orange">Agent follow-up</span>
          </p>
        </div>
      </header>
      {error && <div className="alert error">{error}</div>}
      <div className="card timeline-wrap" ref={host} />
    </div>
  )
}
