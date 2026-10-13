import { useState } from 'react'
import { api } from '../api'
export default function Auth({ onAuth }) {
  const [register, setRegister] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function submit(event) {
    event.preventDefault(); setBusy(true); setError('')
    const input = Object.fromEntries(new FormData(event.currentTarget))
    try { onAuth(await api(`/auth/${register ? 'register' : 'login'}`, { method: 'POST', body: input })) }
    catch (e) { setError(e.message) } finally { setBusy(false) }
  }
  return <div className="auth-layout"><section className="auth-intro"><a className="brand" href="#"><span className="brand-icon">ϟ</span>LoadSense<span className="brand-dot">.</span></a><div className="auth-copy"><span className="eyebrow">WEB API PERFORMANCE LAB</span><h1>Hiểu hiệu năng.<br /><em>Làm chủ tải.</em></h1><p>Từ cấu hình đến kết quả — một không gian kiểm thử Web API trực quan dành cho developer và tester.</p><div className="auth-features"><div><span>01</span><strong>Cấu hình không cần script</strong></div><div><span>02</span><strong>Theo dõi số liệu mỗi giây</strong></div><div><span>03</span><strong>Lưu lịch sử và xuất báo cáo</strong></div></div></div><small>Core Engine · Spring Boot / PostgreSQL / WebSocket</small></section><main className="auth-form-wrap"><div className="auth-form"><span className="tag success">WORKSPACE CỤC BỘ</span><h2>{register ? 'Tạo tài khoản' : 'Chào mừng trở lại'}</h2><p>{register ? 'Bắt đầu xây dựng bộ kiểm thử của bạn.' : 'Đăng nhập để tiếp tục phiên kiểm thử.'}</p><form onSubmit={submit}><label>Tên đăng nhập<input name="username" autoComplete="username" pattern="[a-zA-Z0-9_.-]{3,40}" title="3–40 ký tự: chữ, số, dấu chấm, gạch ngang hoặc gạch dưới" required placeholder="developer" /></label><label>Mật khẩu<input name="password" type="password" autoComplete={register ? 'new-password' : 'current-password'} minLength={8} maxLength={72} required placeholder="Tối thiểu 8 ký tự" /></label>{error && <div className="alert error" role="alert">{error}</div>}<button className="button primary full" disabled={busy}>{busy ? 'Đang xử lý…' : register ? 'Tạo tài khoản →' : 'Đăng nhập →'}</button></form><p className="auth-switch">{register ? 'Đã có tài khoản?' : 'Chưa có tài khoản?'} <button className="text-button" onClick={() => { setRegister(!register); setError('') }}>{register ? 'Đăng nhập' : 'Đăng ký'}</button></p><div className="auth-note"><span className="status-dot amber" /> LocalAI đang ở giai đoạn tích hợp. Các chức năng kiểm thử thủ công đã sẵn sàng khi Core Engine hoạt động.</div></div></main></div>
}
