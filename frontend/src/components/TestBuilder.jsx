import { useState } from 'react'
import { api } from '../api'
import { loadProfileLabels } from '../reportModel'

const defaults = {
  name: '',
  targetUrl: 'http://localhost:8080/demo/echo?delayMs=30',
  httpMethod: 'GET',
  headers: {},
  body: '',
  virtualUsers: 20,
  durationSeconds: 30,
  requestsPerSecond: 100,
  timeoutMs: 5000,
  loadProfile: 'CONSTANT',
  rampUpSeconds: 0,
  steps: [],
}

function emptyStep(baseUrl) {
  return {
    name: '',
    targetUrl: baseUrl || 'http://localhost:8080/demo/echo?delayMs=20',
    httpMethod: 'GET',
    headersText: '{}',
    body: '',
    extractVar: '',
    extractPath: '',
  }
}

export default function TestBuilder({ config, draft, token, onSave, onCancel, busy, hosts, onGuide }) {
  const initial = config || (draft ? { ...defaults, ...draft, body: draft.body || '' } : defaults)
  const [authorizeHost, setAuthorizeHost] = useState(false)
  const [form, setForm] = useState({ ...defaults, ...initial, loadProfile: initial.loadProfile || 'CONSTANT', rampUpSeconds: initial.rampUpSeconds ?? 0 })
  const [headers, setHeaders] = useState(JSON.stringify(initial.headers || {}, null, 2))
  const [steps, setSteps] = useState(() => (initial.steps || []).map(s => ({
    name: s.name || '',
    targetUrl: s.targetUrl || '',
    httpMethod: s.httpMethod || 'GET',
    headersText: JSON.stringify(s.headers || {}, null, 2),
    body: s.body || '',
    extractVar: s.extractVar || '',
    extractPath: s.extractPath || '',
  })))
  const [error, setError] = useState('')
  const [probeState, setProbeState] = useState(null)
  const [probing, setProbing] = useState(false)
  const set = (key, value) => setForm(current => ({ ...current, [key]: value }))
  const updateStep = (idx, key, value) => setSteps(curr => curr.map((item, i) => (i === idx ? { ...item, [key]: value } : item)))

  async function runProbe() {
    setError(''); setProbing(true)
    try {
      const parsedHeaders = parseHeaderObject(headers, 'Headers')
      const res = await api('/probe', {
        token,
        method: 'POST',
        body: {
          targetUrl: form.targetUrl,
          httpMethod: form.httpMethod,
          headers: parsedHeaders,
          body: ['GET', 'HEAD'].includes(form.httpMethod) ? null : form.body,
          authorizedTarget: true,
        },
      })
      setProbeState(res)
    } catch (e) { setError(e.message) } finally { setProbing(false) }
  }

  function applyExtractPathToChain(path) {
    const rawVar = path.split('.').pop()?.replace(/[^a-zA-Z0-9_]/g, '') || 'token'
    const varName = /^[a-zA-Z_]/.test(rawVar) ? rawVar : `var_${rawVar}`
    setSteps(curr => {
      if (curr.length === 0) {
        return [{
          name: `Bước 1 · Trích xuất ${varName}`,
          targetUrl: form.targetUrl,
          httpMethod: form.httpMethod,
          headersText: headers || '{}',
          body: form.body || '',
          extractVar: varName,
          extractPath: path,
        }]
      }
      return curr.map((s, idx) => (idx === 0 ? { ...s, extractVar: varName, extractPath: path } : s))
    })
  }

  function parseHeaderObject(text, label) {
    const parsed = JSON.parse(text)
    if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object' || Object.values(parsed).some(v => typeof v !== 'string')) {
      throw new Error(`${label} phải là JSON object với giá trị chuỗi.`)
    }
    return parsed
  }

  async function submit(event) {
    event.preventDefault(); setError('')
    try {
      const parsed = parseHeaderObject(headers, 'Headers')
      if (['GET', 'HEAD'].includes(form.httpMethod) && (form.body || '').trim()) throw new Error('GET/HEAD không hỗ trợ body. Xóa body hoặc đổi method.')
      const parsedSteps = steps.map((s, idx) => {
        const stepHeaders = parseHeaderObject(s.headersText || '{}', `Headers bước ${idx + 1}`)
        if (['GET', 'HEAD'].includes(s.httpMethod) && (s.body || '').trim()) throw new Error(`Bước ${idx + 1}: GET/HEAD không hỗ trợ body.`)
        return {
          name: s.name,
          targetUrl: s.targetUrl,
          httpMethod: s.httpMethod,
          headers: stepHeaders,
          body: s.body,
          extractVar: s.extractVar,
          extractPath: s.extractPath,
        }
      })
      const hostsToCheck = []
      const addHost = rawUrl => {
        const sanitized = String(rawUrl || '').replace(/\{\{[a-zA-Z_][a-zA-Z0-9_]*\}\}/g, 'var')
        let h
        try { h = new URL(sanitized).hostname.toLowerCase() } catch { throw new Error(`URL không hợp lệ: ${rawUrl}`) }
        if (!(hosts || []).includes(h) && !hostsToCheck.includes(h)) hostsToCheck.push(h)
      }
      addHost(form.targetUrl)
      for (const s of parsedSteps) addHost(s.targetUrl)
      if (hostsToCheck.length > 0 && !authorizeHost) throw new Error('Hãy xác nhận quyền kiểm thử host trước khi lưu.')
      await onSave({
        ...form,
        headers: parsed,
        rampUpSeconds: form.loadProfile === 'RAMP_UP' ? Number(form.rampUpSeconds || 0) : 0,
        steps: parsedSteps,
      }, hostsToCheck.length ? hostsToCheck : null)
    } catch (e) { setError(e.message) }
  }

  return <form className="builder" onSubmit={submit}>
    <div className="page-heading">
      <div><span className="eyebrow">CẤU HÌNH KIỂM THỬ</span><h1>{config ? 'Chỉnh sửa cấu hình' : 'Tạo bài kiểm thử'}</h1><p>Thiết lập request, kiểu tải và chuỗi kịch bản bằng biểu mẫu. Không cần viết kịch bản.</p></div>
      <button type="button" className="button" onClick={onCancel}>← Quay lại</button>
    </div>
    <div className="builder-grid">
      <div className="stack">
        <section className="panel">
          <div className="section-title"><span className="step">01</span><div><h3>Request đến API</h3><p>Chọn API bạn muốn kiểm thử</p></div></div>
          <label>Tên bài kiểm thử<input value={form.name} onChange={e => set('name', e.target.value)} required maxLength={120} placeholder="Ví dụ: Hiệu năng API sản phẩm" /></label>
          <div className="request-row">
            <label>Method<select value={form.httpMethod} onChange={e => set('httpMethod', e.target.value)}>{['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'].map(method => <option key={method}>{method}</option>)}</select></label>
            <label>Target URL<input type="url" value={form.targetUrl} onChange={e => set('targetUrl', e.target.value)} required maxLength={2048} /></label>
          </div>
          {token && (
            <div className="probe-bar">
              <button type="button" className="button small" disabled={probing || !form.targetUrl} onClick={runProbe}>
                {probing ? 'Đang thăm dò…' : '⚡ Thăm dò URL (Probe)'}
              </button>
              {probeState && (
                <span className={`tag ${probeState.reachable && probeState.status < 400 ? 'success' : 'error-pill'}`}>
                  {probeState.reachable ? `HTTP ${probeState.status} · ${probeState.latencyMs} ms` : `Lỗi kết nối: ${probeState.error || 'timeout'}`}
                </span>
              )}
              {probeState?.suggestedExtractPaths?.length > 0 && (
                <div className="probe-pill-row">
                  <small className="muted">Gợi ý trích xuất:</small>
                  {probeState.suggestedExtractPaths.map(path => (
                    <button key={path} type="button" className="text-button" onClick={() => applyExtractPathToChain(path)}>
                      ＋ {path}
                    </button>
                  ))}
                </div>
              )}
            </div>
          )}
          <label>Headers <small>JSON object</small><textarea className="code" rows={4} value={headers} onChange={e => setHeaders(e.target.value)} spellCheck={false} /></label>
          <label>Request body <small>Văn bản / JSON; bỏ trống với GET, HEAD</small><textarea className="code" rows={4} value={form.body || ''} onChange={e => set('body', e.target.value)} maxLength={65536} spellCheck={false} placeholder={'{\n  "productId": "demo-001"\n}'} /></label>
        </section>

        <section className="panel">
          <div className="section-title"><span className="step">02</span><div><h3>Mức tải &amp; kiểu tải</h3><p>Cấu hình VUs, RPS và hồ sơ tạo tải (Load / Stress / Spike)</p></div></div>
          <div className="context-help"><button type="button" className="text-button" onClick={() => onGuide('virtual-users')}>Người dùng ảo là gì? ↗</button><button type="button" className="text-button" onClick={() => onGuide('rps')}>Tìm hiểu RPS ↗</button><button type="button" className="text-button" onClick={() => onGuide('timeout')}>Timeout là gì? ↗</button></div>
          <div className="fields-grid">
            <label>Kiểu tải (Load profile)
              <select value={form.loadProfile} onChange={e => set('loadProfile', e.target.value)}>
                {Object.entries(loadProfileLabels).map(([val, label]) => <option key={val} value={val}>{label}</option>)}
              </select>
              <small>Điều tiết lưu lượng theo thời gian chạy</small>
            </label>
            {form.loadProfile === 'RAMP_UP' && (
              <label>Thời gian tăng tải (giây)
                <input type="number" min={0} max={form.durationSeconds || 600} value={form.rampUpSeconds} onChange={e => set('rampUpSeconds', e.target.value === '' ? 0 : Number(e.target.value))} />
                <small>Tăng tuyến tính từ 20% lên 100% RPS</small>
              </label>
            )}
          </div>
          <div className="fields-grid">
            {[['virtualUsers', 'Virtual users', 'Người dùng đồng thời', 1, 300], ['durationSeconds', 'Thời lượng (giây)', 'Khoảng thời gian tạo tải', 1, 600], ['requestsPerSecond', 'Giới hạn RPS', 'Số request tối đa mỗi giây', 1, 500], ['timeoutMs', 'Timeout (ms)', 'Thời gian chờ mỗi request', 100, 30000]].map(([key, title, hint, min, max]) => (
              <label key={key}>{title}<input type="number" min={min} max={max} required value={form[key]} onChange={e => set(key, e.target.value === '' ? '' : Number(e.target.value))} /><small>{hint} · {min}–{max}</small></label>
            ))}
          </div>
        </section>

        <section className="panel">
          <div className="section-title">
            <span className="step">03</span>
            <div><h3>Scenario Chaining (tùy chọn · tối đa 3 bước)</h3><p>Chạy tuần tự nhiều bước, trích xuất JSON từ phản hồi bước trước bằng <code>{'{{ten_bien}}'}</code></p></div>
            {steps.length < 3 && (
              <button type="button" className="button small" style={{ marginLeft: 'auto' }} onClick={() => setSteps(curr => [...curr, emptyStep(form.targetUrl)])}>＋ Thêm bước</button>
            )}
          </div>
          {!steps.length && <p className="muted">Để trống nếu chỉ kiểm thử endpoint chính phía trên.</p>}
          <div className="stack">
            {steps.map((s, idx) => (
              <div key={idx} className="chain-step">
                <div className="chain-step-head">
                  <strong>Bước {idx + 1}</strong>
                  <button type="button" className="text-button danger" onClick={() => setSteps(curr => curr.filter((_, i) => i !== idx))}>Xóa bước</button>
                </div>
                <div className="fields-grid">
                  <label>Tên bước<input value={s.name} maxLength={80} onChange={e => updateStep(idx, 'name', e.target.value)} placeholder={`Ví dụ: Bước ${idx + 1} - Đăng nhập`} /></label>
                  <label>Method<select value={s.httpMethod} onChange={e => updateStep(idx, 'httpMethod', e.target.value)}>{['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'].map(m => <option key={m}>{m}</option>)}</select></label>
                </div>
                <label>URL bước (hỗ trợ {'{{var}}'} ở path/query)<input value={s.targetUrl} required maxLength={2048} onChange={e => updateStep(idx, 'targetUrl', e.target.value)} /></label>
                <div className="fields-grid">
                  <label>Lưu vào biến (tùy chọn)<input value={s.extractVar} maxLength={64} placeholder="token" onChange={e => updateStep(idx, 'extractVar', e.target.value)} /></label>
                  <label>Đường dẫn JSON trích xuất<input value={s.extractPath} maxLength={256} placeholder="$.data.token" onChange={e => updateStep(idx, 'extractPath', e.target.value)} /></label>
                </div>
                <label>Headers bước (JSON, hỗ trợ {'{{var}}'})<textarea className="code" rows={2} value={s.headersText} onChange={e => updateStep(idx, 'headersText', e.target.value)} spellCheck={false} /></label>
                {!['GET', 'HEAD'].includes(s.httpMethod) && (
                  <label>Body bước (hỗ trợ {'{{var}}'})<textarea className="code" rows={2} value={s.body} maxLength={65536} onChange={e => updateStep(idx, 'body', e.target.value)} spellCheck={false} /></label>
                )}
              </div>
            ))}
          </div>
        </section>
      </div>

      <aside className="stack">
        <section className="panel preview-card">
          <span className="eyebrow">CẤU HÌNH DỰ KIẾN</span>
          <h3>{form.name || 'Bài kiểm thử mới'}</h3>
          <span className="method">{form.httpMethod}</span>
          <p className="target-text">{form.targetUrl}</p>
          <dl>
            <div><dt>Kiểu tải</dt><dd>{loadProfileLabels[form.loadProfile] || form.loadProfile}</dd></div>
            <div><dt>Đồng thời</dt><dd>{form.virtualUsers} VUs</dd></div>
            <div><dt>Thời lượng</dt><dd>{form.durationSeconds}s</dd></div>
            <div><dt>Tải tối đa</dt><dd>{form.requestsPerSecond} req/s</dd></div>
            {steps.length > 0 && <div><dt>Chuỗi kịch bản</dt><dd>{steps.length} bước</dd></div>}
          </dl>
          <p className="muted">Đây là giới hạn tải, số request thực tế phụ thuộc độ trễ và số worker.</p>
          {error && <div className="alert error" role="alert">{error}</div>}
          <button className="button primary full" disabled={busy}>{busy ? 'Đang lưu…' : 'Lưu cấu hình →'}</button>
        </section>
        <section className="panel note-card">
          <h3>Phạm vi kiểm thử</h3>
          <p>Chỉ chạy trên API mà bạn có quyền kiểm thử. Với host công khai mới, xác nhận quyền kiểm thử để lưu cấu hình.</p>
          <label className="check-label"><input type="checkbox" checked={authorizeHost} onChange={e => setAuthorizeHost(e.target.checked)} />Tôi có quyền kiểm thử các host trong cấu hình và muốn cho phép tài khoản này sử dụng chúng.</label>
          <p>Lần chạy lưu snapshot riêng; sửa cấu hình không làm thay đổi lịch sử.</p>
        </section>
        <section className="ai-note"><span className="tag">AI CLOUD</span><h3>Prompt-to-Test</h3><p>Bạn có thể tạo đề xuất trong AI Workspace và kiểm tra, chỉnh sửa tại đây trước khi lưu.</p></section>
      </aside>
    </div>
  </form>
}
