import { useCallback, useEffect, useRef, useState } from 'react'
import { del, get, post, when, type TenantView } from '../api'

const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/

/** Merchants of this organization. A person can be a member of several organizations. */
export default function UsersPage() {
  const [tenant, setTenant] = useState<TenantView | null>(null)
  const [message, setMessage] = useState('')
  const [member, setMember] = useState({ email: '', name: '' })
  const [formError, setFormError] = useState('')
  const dialog = useRef<HTMLDialogElement>(null)

  const load = useCallback(() => {
    get<TenantView>('/api/admin/tenant').then(setTenant).catch((e) => setMessage(e.message))
  }, [])
  useEffect(load, [load])

  const invite = async () => {
    setFormError('')
    if (!EMAIL.test(member.email)) return setFormError('Enter a valid email address')
    try {
      await post('/api/admin/members', member)
      dialog.current?.close()
      setMessage(`${member.email} can now work in ${tenant?.name}`)
      setMember({ email: '', name: '' })
      load()
    } catch (e) {
      setFormError((e as Error).message)
    }
  }

  const remove = async (id: number, email: string) => {
    if (!window.confirm(`Remove ${email} from ${tenant?.name}? They lose access immediately.`)) return
    try {
      await del(`/api/admin/members/${id}`)
      setMessage(`${email} removed`)
      load()
    } catch (e) {
      setMessage((e as Error).message)
    }
  }

  return (
    <div>
      <header className="page-header">
        <div>
          <h1>Members</h1>
          <p className="muted">Merchants who can work in {tenant?.name ?? 'this organization'}. Customers need no invite: they sign in and see invoices billed to their email.</p>
        </div>
        <button className="primary" onClick={() => dialog.current?.showModal()}>Add member</button>
      </header>
      {message && <div className="alert info">{message}</div>}

      <section className="card">
        <table>
          <thead>
            <tr><th>Email</th><th>Name</th><th>Added</th><th /></tr>
          </thead>
          <tbody>
            {tenant?.members.map((m) => (
              <tr key={m.id}>
                <td>{m.email}</td>
                <td>{m.name ?? ''}</td>
                <td>{when(m.createdAt)}</td>
                <td><button className="link" onClick={() => remove(m.id, m.email)}>Remove</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <dialog ref={dialog} className="modal" onClick={(e) => { if (e.target === dialog.current) dialog.current?.close() }}>
        <h2>Add member</h2>
        {formError && <div className="alert error">{formError}</div>}
        <div className="form">
          <div className="modal-fields">
            <label>Google email *
              <input type="email" placeholder="teammate@company.com" value={member.email}
                     onChange={(e) => setMember({ ...member, email: e.target.value })} /></label>
            <label>Name
              <input placeholder="Jane Doe" value={member.name}
                     onChange={(e) => setMember({ ...member, name: e.target.value })} /></label>
          </div>
          <div className="row">
            <button className="primary" onClick={invite} disabled={!member.email.trim()}>Add</button>
            <button onClick={() => dialog.current?.close()}>Cancel</button>
          </div>
        </div>
      </dialog>
    </div>
  )
}
