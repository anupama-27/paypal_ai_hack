export interface FilterOption {
  value: string
  label: string
  count?: number
}

interface Props {
  search: string
  onSearch: (value: string) => void
  placeholder: string
  options: FilterOption[]
  filter: string
  onFilter: (value: string) => void
  shown: number
  total: number
  noun: string
}

export function TableToolbar({ search, onSearch, placeholder, options, filter, onFilter, shown, total, noun }: Props) {
  return (
    <div className="table-toolbar">
      <div className="search-box">
        <svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true">
          <circle cx="8.5" cy="8.5" r="5.5" fill="none" stroke="currentColor" strokeWidth="1.8" />
          <path d="M13 13l4 4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
        </svg>
        <input type="search" value={search} placeholder={placeholder} aria-label={placeholder}
               onChange={(e) => onSearch(e.target.value)} />
        {search && <button className="clear" aria-label="Clear search" onClick={() => onSearch('')}>×</button>}
      </div>
      <div className="chips toolbar-chips">
        {options.map((o) => (
          <button key={o.value} className={`chip ${filter === o.value ? 'active' : ''}`} onClick={() => onFilter(o.value)}>
            {o.label}{o.count != null && <span className="chip-count">{o.count}</span>}
          </button>
        ))}
      </div>
      <span className="result-count muted">
        {shown === total ? `${total} ${noun}` : `${shown} of ${total} ${noun}`}
      </span>
    </div>
  )
}
