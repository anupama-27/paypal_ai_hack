import { useEffect, useRef, useState } from 'react'
import { Calendar } from '@bryntum/calendar'
import '@bryntum/calendar/calendar.css'
import '@bryntum/calendar/stockholm-light.css'
import '@bryntum/calendar/fontawesome/css/fontawesome.css'
import '@bryntum/calendar/fontawesome/css/solid.css'
import { get, put, type TimelineEvent } from '../api'

const COLORS: Record<TimelineEvent['kind'], string> = {
  INVOICE_DUE: 'blue',
  PROMISE_DUE: 'green',
  FOLLOW_UP: 'orange',
}

/** End = start + 1 hour, as an ISO local date-time string. */
function addHour(date: string, time: string): string {
  const [h, m] = time.split(':').map(Number)
  return `${date}T${String(Math.min(h + 1, 23)).padStart(2, '0')}:${String(h + 1 > 23 ? 59 : m).padStart(2, '0')}:00`
}

/** Bryntum calendar: invoice due dates, promised payments and agent follow-ups. */
export default function TimelinePage() {
  const host = useRef<HTMLDivElement>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    if (!host.current) return
    const calendar = new Calendar({
      appendTo: host.current,
      features: {
        // Only what we can save: name, start date and start time.
        eventEdit: {
          items: {
            resourceField: false, // "Calendar"
            allDay: false,
            endDateField: false,
            endTimeField: false,
            recurrenceCombo: false, // "Repeat"
          },
        },
      },
      modes: {
        day: null,
        week: null,
        agenda: null,
        year: null,
      },
    })
    let cancelled = false
    const load = () =>
      get<TimelineEvent[]>('/api/timeline')
        .then((events) => {
          if (cancelled) return
          calendar.eventStore.data = events.map((e) => ({
            id: `${e.kind}-${e.sourceId}`,
            kind: e.kind,
            sourceId: e.sourceId,
            name: e.label,
            startDate: `${e.date}T${e.time}:00`,
            endDate: addHour(e.date, e.time),
            eventColor: COLORS[e.kind] ?? 'gray',
            resizable: false,
          }))
          calendar.date = calendar.date ?? new Date()
        })
        .catch((err) => setError(err.message))

    // Save any edit (title, date or time) so it survives a refresh; the date also shows on the Cases page.
    calendar.eventStore.on({
      // These events come from invoices, promises and cases, so they can't be deleted here.
      beforeRemove: () => false,
      update: ({ record, changes }: { record: any; changes: Record<string, unknown> }) => {
        if (!changes.name && !changes.startDate) return
        const d = record.startDate as Date
        const pad = (n: number) => String(n).padStart(2, '0')
        const body = {
          label: record.name,
          date: `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`,
          time: `${pad(d.getHours())}:${pad(d.getMinutes())}`,
        }
        setError('')
        put(`/api/timeline/${record.kind}/${record.sourceId}`, body).catch((err) => {
          setError(`Could not save your change: ${err.message}`)
          load()
        })
      },
    })
    load()
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
          <p className="muted">Upcoming invoice due dates, promised payments and agent follow-ups on one calendar.</p>
        </div>
      </header>
      {error && <div className="alert error">{error}</div>}
      <div className="legend-group" aria-label="Legend">
        <span className="legend legend-blue">Invoice due</span>
        <span className="legend legend-green">Promised payment</span>
        <span className="legend legend-orange">Agent follow-up</span>
      </div>
      <div className="card timeline-wrap" ref={host} />
    </div>
  )
}
