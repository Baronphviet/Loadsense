import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, date } from '../api'
import AgentActionGraph from './AgentActionGraph'

function normalizePriorityClass(p) {
  const lower = String(p || 'medium').toLowerCase()
  if (lower === 'high') return 'prio-high'
  if (lower === 'low') return 'prio-low'
  return 'prio-medium'
}

function safePathFromUrl(url) {
  try {
    const u = new URL(url)
    let p = u.pathname || '/'
    if (p.length > 1 && p.endsWith('/')) p = p.slice(0, -1)
    return u.search ? `${p}${u.search}` : p
  } catch {
    return '/'
  }
}

const CRAWL_STAGES = [
  'Tầng 0 · Kết nối Trang chủ, kiểm tra bảo mật & thăm dò OpenAPI/Swagger…',
  'Tầng 1 · Thu thập menu điều hướng chính & chụp giao diện HTML…',
  'Tầng 2 · Đào sâu các chuyên mục con, bài viết & biểu mẫu tương tác…',
  'Tầng 3 · Phân nhóm Use Case, dựng cây hành trình & sinh mã Playwright…',
]

function CrawlProgressBanner({ targetUrl, maxCases }) {
  const [elapsedMs, setElapsedMs] = useState(0)
  useEffect(() => {
    const started = Date.now()
    const timer = setInterval(() => {
      setElapsedMs(Date.now() - started)
    }, 250)
    return () => clearInterval(timer)
  }, [])
  const activeStageIdx = Math.min(CRAWL_STAGES.length - 1, Math.floor(elapsedMs / 1800))
  return (
    <div className="activity-loader-card" role="status" aria-live="polite">
      <div className="activity-loader-head">
        <div className="activity-pulse-ring" />
        <div style={{ flex: 1 }}>
          <strong>Agent đang crawl sâu vào {targetUrl} (Mục tiêu tối đa {maxCases} UC)</strong>
          <p className="muted" style={{ margin: '0.15rem 0 0' }}>{CRAWL_STAGES[activeStageIdx]}</p>
        </div>
        <span className="activity-timer">{(elapsedMs / 1000).toFixed(1)}s</span>
      </div>
      <div className="activity-shimmer-track">
        <div className="activity-shimmer-bar" />
      </div>
      <div className="activity-steps-row">
        {CRAWL_STAGES.map((_, idx) => (
          <span key={idx} className={`activity-step-chip ${idx <= activeStageIdx ? 'is-active' : ''}`}>
            {idx < activeStageIdx ? '✓' : idx === activeStageIdx ? '◉' : '○'} Tầng {idx}
          </span>
        ))}
      </div>
    </div>
  )
}

export default function TestSuiteView({ token, onStressTest }) {
  const [suites, setSuites] = useState([])
  const [activeSuite, setActiveSuite] = useState(null)
  const [targetUrl, setTargetUrl] = useState('https://cntt.actvn.edu.vn')
  const [maxCases, setMaxCases] = useState(100)
  const [authorized, setAuthorized] = useState(true)
  const [busy, setBusy] = useState(false)
  const [crawling, setCrawling] = useState(false)
  const [running, setRunning] = useState(false)
  const [runDelaySec, setRunDelaySec] = useState(2)
  const [liveTraversal, setLiveTraversal] = useState(null)
  const skipDelayRef = useRef(false)
  const [aiExplaining, setAiExplaining] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  // Filter & view state (TestSprite dashboard controls)
  const [search, setSearch] = useState('')
  const [statusTab, setStatusTab] = useState('ALL')
  const [priorityFilter, setPriorityFilter] = useState('ALL')
  const [viewMode, setViewMode] = useState('agent') // 'agent' | 'list'
  const [collapsed, setCollapsed] = useState({})
  const [selectedIds, setSelectedIds] = useState([])

  // Edit Test Plan modal/mode ('view' | 'form' | 'yaml')
  const [editMode, setEditMode] = useState(null) // null | 'form' | 'yaml'
  const [yamlDraft, setYamlDraft] = useState('')
  const [formGroupsDraft, setFormGroupsDraft] = useState([])

  // Slide-over Detail Drawer
  const [drawerCaseId, setDrawerCaseId] = useState(null)
  const [leftTab, setLeftTab] = useState('preview') // 'preview' | 'log' | 'code'
  const [rightTab, setRightTab] = useState('error') // 'error' | 'cause' | 'fix' | 'plan'
  const [drawerZoom, setDrawerZoom] = useState(0.55)
  const [drawerExpandedWeb, setDrawerExpandedWeb] = useState(false)

  const loadSuites = useCallback(async (selectId = null) => {
    try {
      const list = await api('/suites', { token })
      setSuites(list)
      const targetId = selectId || (list[0]?.id ?? null)
      if (targetId) {
        const detail = await api(`/suites/${targetId}`, { token })
        setActiveSuite(detail)
      } else {
        setActiveSuite(null)
      }
    } catch (e) { setError(e.message) }
  }, [token])

  useEffect(() => { void loadSuites() }, [loadSuites])
  useEffect(() => { if (notice) { const t = setTimeout(() => setNotice(''), 4500); return () => clearTimeout(t) } }, [notice])

  async function selectSuite(id) {
    if (!id) return
    setBusy(true); setError(''); setDrawerCaseId(null); setSelectedIds([])
    try {
      setActiveSuite(await api(`/suites/${id}`, { token }))
    } catch (e) { setError(e.message) } finally { setBusy(false) }
  }

  async function createSuite(event) {
    event.preventDefault()
    setBusy(true); setCrawling(true); setError(''); setNotice('')
    try {
      const created = await api('/suites', {
        token,
        method: 'POST',
        body: { targetUrl, maxCases: Number(maxCases), authorizedTarget: authorized },
      })
      setActiveSuite(created)
      setSelectedIds([])
      setDrawerCaseId(null)
      setEditMode(null)
      setViewMode('agent')
      setSuites(prev => [{
        id: created.id, name: created.name, targetUrl: created.targetUrl,
        totalCases: created.totalCases, passedCases: created.passedCases,
        failedCases: created.failedCases, runningCases: created.runningCases,
        lastRunAt: created.lastRunAt, updatedAt: created.updatedAt,
      }, ...prev.filter(s => s.id !== created.id)])
      setNotice(`Đã crawl sâu và sinh ${created.totalCases} Test Cases cho ${created.name}`)
    } catch (e) { setError(e.message) } finally { setBusy(false); setCrawling(false) }
  }

  const allCases = useMemo(() => {
    if (!activeSuite?.useCases) return []
    return activeSuite.useCases.flatMap(g => g.testCases.map(tc => ({ ...tc, groupName: g.useCase, slug: g.slug })))
  }, [activeSuite])

  async function runSuite(onlySelected = false) {
    if (!activeSuite) return
    setRunning(true); setError(''); setNotice('')
    skipDelayRef.current = false
    // Tự động chuyển sang Agent Action View làm mặt chính trong quá trình chạy
    setViewMode('agent')

    try {
      const targetCases = onlySelected && selectedIds.length > 0
        ? allCases.filter(tc => selectedIds.includes(tc.id))
        : allCases

      if (!targetCases.length) {
        setRunning(false)
        return
      }

      const rootPath = safePathFromUrl(activeSuite.targetUrl || '/')
      // Sắp xếp từ Trang chủ (depth 0) -> Tầng 1 -> Tầng 2 -> Tầng 3 để mô phỏng thao tác duyệt sâu
      const sorted = [...targetCases].sort((a, b) => (a.depth || 0) - (b.depth || 0))
      const delayMs = Number(runDelaySec) * 1000

      if (delayMs <= 0) {
        const caseIds = onlySelected && selectedIds.length > 0 ? selectedIds : []
        const updated = await api(`/suites/${activeSuite.id}/run`, {
          token,
          method: 'POST',
          body: { caseIds },
        })
        setActiveSuite(updated)
        syncSuiteSummary(updated)
        setNotice(`Đã chạy xong ${caseIds.length || updated.totalCases} kịch bản: ${updated.passedCases} Passed · ${updated.failedCases} Failed`)
        return
      }

      // Chia thành từng đợt theo nhánh (mỗi đợt 1–5 kịch bản cùng nhánh đích) để vừa trực quan vừa mượt
      const waves = []
      const byPath = new Map()
      for (const tc of sorted) {
        const p = safePathFromUrl(tc.targetUrl)
        if (!byPath.has(p)) byPath.set(p, [])
        byPath.get(p).push(tc)
      }
      const pathEntries = Array.from(byPath.entries())
      const maxWaves = Math.min(6, pathEntries.length)
      const chunkSize = Math.max(1, Math.ceil(pathEntries.length / maxWaves))
      for (let i = 0; i < pathEntries.length; i += chunkSize) {
        const slice = pathEntries.slice(i, i + chunkSize)
        const waveCases = slice.flatMap(([, list]) => list)
        const leadCase = waveCases[0]
        const targetPath = safePathFromUrl(leadCase.targetUrl)
        const chain = leadCase.visitedPages?.length
          ? leadCase.visitedPages
          : (targetPath === rootPath ? [rootPath] : [rootPath, targetPath])
        waves.push({
          leadCase,
          targetPath,
          chain,
          caseIds: waveCases.map(c => c.id),
        })
      }

      let completedCount = 0
      let latestUpdated = activeSuite
      for (let wIdx = 0; wIdx < waves.length; wIdx++) {
        const wave = waves[wIdx]
        if (skipDelayRef.current) {
          // Người dùng bấm "Chạy nốt tức thì" -> gom toàn bộ phần còn lại chạy 1 lần
          const remainingIds = waves.slice(wIdx).flatMap(w => w.caseIds)
          latestUpdated = await api(`/suites/${activeSuite.id}/run`, {
            token,
            method: 'POST',
            body: { caseIds: remainingIds },
          })
          setActiveSuite(latestUpdated)
          syncSuiteSummary(latestUpdated)
          break
        }

        setLiveTraversal({
          activeCase: wave.leadCase,
          activePath: wave.targetPath,
          activeChain: wave.chain,
          waveIndex: wIdx + 1,
          totalWaves: waves.length,
          completedCount,
          totalCount: sorted.length,
          delaySec: runDelaySec,
        })

        const waveStarted = Date.now()
        latestUpdated = await api(`/suites/${activeSuite.id}/run`, {
          token,
          method: 'POST',
          body: { caseIds: wave.caseIds },
        })
        completedCount += wave.caseIds.length
        setActiveSuite(latestUpdated)
        syncSuiteSummary(latestUpdated)

        const elapsed = Date.now() - waveStarted
        const remainingWait = Math.max(250, delayMs - elapsed)
        if (wIdx < waves.length - 1 && !skipDelayRef.current) {
          await new Promise(resolve => setTimeout(resolve, remainingWait))
        }
      }

      setNotice(`Đã duyệt sâu & chạy xong ${sorted.length} kịch bản: ${latestUpdated.passedCases} Passed · ${latestUpdated.failedCases} Failed`)
    } catch (e) {
      setError(e.message)
    } finally {
      setLiveTraversal(null)
      setRunning(false)
    }
  }

  function syncSuiteSummary(updated) {
    setSuites(prev => prev.map(s => (s.id === updated.id ? {
      ...s,
      totalCases: updated.totalCases,
      passedCases: updated.passedCases,
      failedCases: updated.failedCases,
      runningCases: updated.runningCases,
      lastRunAt: updated.lastRunAt,
      updatedAt: updated.updatedAt,
    } : s)))
  }

  async function explainCaseWithAi(tc) {
    if (!activeSuite || !tc) return
    setAiExplaining(true); setError('')
    try {
      const updated = await api(`/suites/${activeSuite.id}/cases/${tc.id}/ai-explain`, {
        token,
        method: 'POST',
        body: {},
      })
      setActiveSuite(updated)
      setNotice(`✦ AI đã tóm tắt dễ hiểu mục Preview, Error, Cause & Fix cho ${tc.id}`)
    } catch (e) {
      setError(e.message)
    } finally {
      setAiExplaining(false)
    }
  }

  async function deleteCurrentSuite() {
    if (!activeSuite) return
    setBusy(true); setError('')
    try {
      await api(`/suites/${activeSuite.id}`, { token, method: 'DELETE' })
      setDrawerCaseId(null)
      const rest = suites.filter(s => s.id !== activeSuite.id)
      setSuites(rest)
      if (rest[0]) await selectSuite(rest[0].id)
      else setActiveSuite(null)
      setNotice('Đã xóa Test Suite')
    } catch (e) { setError(e.message) } finally { setBusy(false) }
  }

  function openPlanEditor(mode = 'form') {
    if (!activeSuite) return
    setYamlDraft(activeSuite.planYaml || '')
    setFormGroupsDraft(JSON.parse(JSON.stringify(activeSuite.useCases || [])))
    setEditMode(mode)
  }

  async function savePlanChanges() {
    if (!activeSuite) return
    setBusy(true); setError('')
    try {
      const body = editMode === 'yaml'
        ? { planYaml: yamlDraft }
        : { useCases: formGroupsDraft }
      const updated = await api(`/suites/${activeSuite.id}/plan`, { token, method: 'PUT', body })
      setActiveSuite(updated)
      setEditMode(null)
      setNotice('Đã lưu và đồng bộ kế hoạch kiểm thử (Form ↔ YAML)')
    } catch (e) { setError(e.message) } finally { setBusy(false) }
  }

  function updateFormCase(groupIdx, caseIdx, field, val) {
    setFormGroupsDraft(prev => prev.map((g, gi) => (
      gi !== groupIdx ? g : {
        ...g,
        testCases: g.testCases.map((tc, ci) => (ci !== caseIdx ? tc : { ...tc, [field]: val })),
      }
    )))
  }

  function addFormCase(groupIdx) {
    setFormGroupsDraft(prev => prev.map((g, gi) => {
      if (gi !== groupIdx) return g
      const nextNum = String(g.testCases.length + 1).padStart(3, '0')
      return {
        ...g,
        testCases: [...g.testCases, {
          id: `${g.slug}-${nextNum}`,
          priority: 'Medium',
          name: 'New test case',
          description: '',
          targetUrl: activeSuite.targetUrl,
          httpMethod: 'GET',
          steps: [`Navigate to ${activeSuite.targetUrl}`, 'assert: Verify HTTP 200'],
        }],
      }
    }))
  }

  function removeFormCase(groupIdx, caseIdx) {
    setFormGroupsDraft(prev => prev.map((g, gi) => (
      gi !== groupIdx ? g : { ...g, testCases: g.testCases.filter((_, ci) => ci !== caseIdx) }
    )))
  }

  const snapshotByPath = useMemo(() => {
    const map = {}
    for (const tc of allCases) {
      const p = safePathFromUrl(tc.preview?.finalUrl || tc.targetUrl)
      const baseP = p.split('?')[0] || '/'
      const entry = {
        finalUrl: tc.preview?.finalUrl || tc.targetUrl,
        httpStatus: tc.preview?.httpStatus || 200,
        pageTitle: tc.preview?.pageTitle || tc.name,
        htmlSnapshot: tc.preview?.htmlSnapshot || '',
        touchedElements: tc.preview?.touchedElements || [],
        headings: tc.preview?.headings || [],
        excerpt: tc.preview?.excerpt || '',
      }
      if (!map[p]) {
        map[p] = entry
      } else {
        if (!map[p].htmlSnapshot && entry.htmlSnapshot) map[p].htmlSnapshot = entry.htmlSnapshot
        if (!map[p].touchedElements?.length && entry.touchedElements.length) map[p].touchedElements = entry.touchedElements
        if (entry.httpStatus >= 400) map[p].httpStatus = entry.httpStatus
      }
      if (!map[baseP]) {
        map[baseP] = { ...entry }
      } else if (!map[baseP].htmlSnapshot && entry.htmlSnapshot) {
        map[baseP].htmlSnapshot = entry.htmlSnapshot
      }
    }
    return map
  }, [allCases])

  const counts = useMemo(() => {
    const c = { ALL: allCases.length, PLANNED: 0, RUNNING: 0, PASSED: 0, FAILED: 0 }
    for (const tc of allCases) c[tc.status] = (c[tc.status] || 0) + 1
    return c
  }, [allCases])

  const filteredGroups = useMemo(() => {
    if (!activeSuite?.useCases) return []
    const q = search.trim().toLowerCase()
    return activeSuite.useCases
      .map(g => ({
        ...g,
        testCases: g.testCases.filter(tc => {
          if (statusTab !== 'ALL' && tc.status !== statusTab) return false
          if (priorityFilter !== 'ALL' && tc.priority !== priorityFilter) return false
          if (q && !`${tc.id} ${tc.name} ${tc.targetUrl} ${tc.failureSummary || ''}`.toLowerCase().includes(q)) return false
          return true
        }),
      }))
      .filter(g => g.testCases.length > 0)
  }, [activeSuite, search, statusTab, priorityFilter])

  const drawerCase = useMemo(
    () => allCases.find(tc => tc.id === drawerCaseId) || null,
    [allCases, drawerCaseId],
  )

  function openCaseDrawer(tc) {
    setDrawerCaseId(tc.id)
    setLeftTab('preview')
    setRightTab(tc.status === 'FAILED' ? 'error' : 'cause')
  }

  function toggleSelect(id) {
    setSelectedIds(prev => (prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id]))
  }

  function copyText(text, msg) {
    navigator.clipboard?.writeText(text)
    setNotice(msg)
  }

  function sendToLoadTest(tc) {
    onStressTest({
      name: `Stress · ${tc.id} · ${tc.name.slice(0, 60)}`,
      targetUrl: tc.targetUrl,
      httpMethod: tc.httpMethod || 'GET',
      headers: {},
      body: '',
      virtualUsers: 20,
      durationSeconds: 12,
      requestsPerSecond: 60,
      timeoutMs: 5000,
      loadProfile: 'STEP_STRESS',
      rampUpSeconds: 0,
      steps: [],
    })
  }

  return (
    <div className="suite-page">
      <div className="page-heading">
        <div>
          <span className="eyebrow">AUTO TEST SUITE · KIỂU TESTSPRITE</span>
          <h1>Kiểm thử tự hành theo URL</h1>
          <p>Dán URL trang web hoặc API để tự động crawl 3 tầng (50 / 100 / 200 UC), xem bản đồ giao diện trực quan và để AI tóm tắt Preview · Error · Cause · Fix.</p>
        </div>
        {suites.length > 0 && (
          <div className="inline">
            <select aria-label="Chọn Project Suite" value={activeSuite?.id || ''} onChange={e => selectSuite(e.target.value)}>
              {suites.map(s => (
                <option key={s.id} value={s.id}>{s.name} ({s.passedCases}/{s.totalCases} Passed)</option>
              ))}
            </select>
            {activeSuite && <button type="button" className="text-button danger" disabled={busy} onClick={deleteCurrentSuite}>Xóa Suite</button>}
          </div>
        )}
      </div>

      <section className="panel suite-create-panel">
        <form className="stack" onSubmit={createSuite}>
          <div className="scan-row">
            <input
              type="url"
              value={targetUrl}
              onChange={e => setTargetUrl(e.target.value)}
              required
              maxLength={2048}
              placeholder="Ví dụ: https://cntt.actvn.edu.vn hoặc http://core-engine:8080/demo/echo"
              aria-label="URL website hoặc API cần kiểm thử"
            />
            <select aria-label="Quy mô crawl tối đa" value={maxCases} onChange={e => setMaxCases(Number(e.target.value))} style={{ maxWidth: 185 }}>
              <option value={50}>Tiêu chuẩn (≤ 50 UC)</option>
              <option value={100}>Chuyên sâu (≤ 100 UC)</option>
              <option value={200}>Toàn diện (≤ 200 UC)</option>
            </select>
            <button className="button primary" disabled={busy || !authorized}>
              {crawling ? '✦ Đang crawl sâu…' : '✦ Crawl & Sinh Test Cases'}
            </button>
          </div>
          <label className="check-label">
            <input type="checkbox" checked={authorized} onChange={e => setAuthorized(e.target.checked)} />
            Tôi xác nhận có quyền kiểm thử URL/tên miền này (hệ thống sẽ crawl các đường dẫn cùng domain và tự động theo dõi redirect).
          </label>
        </form>

        {/* Giao diện động chờ trong quá trình Crawl sâu */}
        {crawling && <CrawlProgressBanner targetUrl={targetUrl} maxCases={maxCases} />}
      </section>

      {error && <div className="alert error" role="alert">{error}</div>}
      {notice && <div className="alert success" role="status">✓ {notice}</div>}

      {activeSuite && editMode && (
        <section className="panel edit-plan-panel">
          <div className="panel-heading">
            <div>
              <span className="eyebrow">{activeSuite.name} › TEST CASES › EDIT TEST PLAN</span>
              <h2>Edit Test Plan</h2>
              <p className="muted">Chỉnh sửa Use Cases, độ ưu tiên, URL hoặc danh sách các bước &amp; assert ở dạng Form hoặc YAML.</p>
            </div>
            <div className="inline">
              <div className="segmented">
                <button type="button" className={editMode === 'form' ? 'active' : ''} onClick={() => setEditMode('form')}>Form</button>
                <button type="button" className={editMode === 'yaml' ? 'active' : ''} onClick={() => setEditMode('yaml')}>YAML</button>
              </div>
              <button type="button" className="button" onClick={() => setEditMode(null)}>Done</button>
              <button type="button" className="button primary" disabled={busy} onClick={savePlanChanges}>
                {busy ? 'Đang lưu…' : 'Save Changes'}
              </button>
            </div>
          </div>

          {editMode === 'yaml' ? (
            <textarea
              className="code yaml-editor"
              rows={22}
              spellCheck={false}
              value={yamlDraft}
              onChange={e => setYamlDraft(e.target.value)}
            />
          ) : (
            <div className="stack">
              {formGroupsDraft.map((g, gi) => (
                <div key={g.slug + gi} className="chain-step">
                  <div className="chain-step-head">
                    <strong>{g.useCase} <span className="tag">{g.slug}</span></strong>
                    <button type="button" className="button small" onClick={() => addFormCase(gi)}>＋ Thêm Test Case</button>
                  </div>
                  <div className="stack">
                    {g.testCases.map((tc, ci) => (
                      <div key={tc.id + ci} className="form-plan-row">
                        <input value={tc.id} onChange={e => updateFormCase(gi, ci, 'id', e.target.value)} placeholder="ID" style={{ maxWidth: 100 }} />
                        <select value={tc.priority} onChange={e => updateFormCase(gi, ci, 'priority', e.target.value)} style={{ maxWidth: 110 }}>
                          <option value="High">High</option>
                          <option value="Medium">Medium</option>
                          <option value="Low">Low</option>
                        </select>
                        <input value={tc.name} onChange={e => updateFormCase(gi, ci, 'name', e.target.value)} placeholder="Tên test case" />
                        <input value={tc.targetUrl} onChange={e => updateFormCase(gi, ci, 'targetUrl', e.target.value)} placeholder="URL" />
                        <button type="button" className="text-button danger" onClick={() => removeFormCase(gi, ci)}>Xóa</button>
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          )}
        </section>
      )}

      {activeSuite && !editMode && (
        <section className="panel suite-workspace">
          <div className="suite-toolbar">
            <div className="suite-breadcrumb">
              <strong>{activeSuite.name}</strong>
              <span className="muted">› Test Cases · {activeSuite.targetUrl}</span>
            </div>
            <div className="inline suite-actions">
              <input className="search-input" placeholder="Search Tests…" value={search} onChange={e => setSearch(e.target.value)} />
              <select aria-label="Lọc Priority" value={priorityFilter} onChange={e => setPriorityFilter(e.target.value)}>
                <option value="ALL">All Priorities</option>
                <option value="High">High</option>
                <option value="Medium">Medium</option>
                <option value="Low">Low</option>
              </select>
              <select
                aria-label="Nhịp độ hiệu ứng duyệt nhánh"
                value={runDelaySec}
                onChange={e => setRunDelaySec(Number(e.target.value))}
                title="Độ trễ hiệu ứng duyệt sâu từng nhánh giống TestSprite (1–5s)"
              >
                <option value={2}>⚡ Duyệt trực quan (2s/nhánh)</option>
                <option value={4}>🐢 Duyệt sâu chậm (4s/nhánh)</option>
                <option value={1}>🚀 Duyệt nhanh (1s/nhánh)</option>
                <option value={0}>⏭ Chạy tức thì (0s)</option>
              </select>
              <button type="button" className="button small" onClick={() => openPlanEditor('form')}>✎ Edit Test Plan</button>
              {running && runDelaySec > 0 && (
                <button
                  type="button"
                  className="button ghost small"
                  onClick={() => { skipDelayRef.current = true }}
                >
                  ⏭ Hoàn tất ngay
                </button>
              )}
              {selectedIds.length > 0 ? (
                <button type="button" className="button primary small" disabled={running} onClick={() => runSuite(true)}>
                  {running ? '🤖 Agent đang duyệt…' : `▷ Run Selected (${selectedIds.length})`}
                </button>
              ) : (
                <button type="button" className="button primary small" disabled={running} onClick={() => runSuite(false)}>
                  {running ? '🤖 Agent đang duyệt…' : `▷ Run All ${activeSuite.totalCases}`}
                </button>
              )}
            </div>
          </div>

          <div className="suite-tabs-row">
            <div className="status-tabs">
              {[['ALL', 'All'], ['PLANNED', 'Planned'], ['PASSED', 'Passed'], ['FAILED', 'Failed']].map(([key, label]) => (
                <button
                  key={key}
                  type="button"
                  className={`tab-pill ${statusTab === key ? 'active' : ''}`}
                  onClick={() => setStatusTab(key)}
                >
                  {label} <span className="count-badge">{counts[key] || 0}</span>
                </button>
              ))}
            </div>
            <div className="segmented">
              <button type="button" className={viewMode === 'agent' ? 'active' : ''} onClick={() => setViewMode('agent')}>◎ Agent Action View (Mặt chính)</button>
              <button type="button" className={viewMode === 'list' ? 'active' : ''} onClick={() => setViewMode('list')}>▤ List View</button>
            </div>
          </div>

          {viewMode === 'agent' ? (
            <AgentActionGraph
              suite={activeSuite}
              filteredGroups={filteredGroups}
              snapshotByPath={snapshotByPath}
              onOpenCase={openCaseDrawer}
              onStressTest={sendToLoadTest}
              liveTraversal={liveTraversal}
            />
          ) : (
            <div className="accordion-list">
              {filteredGroups.map(g => {
                const isOpen = !collapsed[g.slug]
                const passedInGroup = g.testCases.filter(t => t.status === 'PASSED').length
                return (
                  <div key={g.slug} className="uc-accordion">
                    <button
                      type="button"
                      className="uc-header"
                      onClick={() => setCollapsed(prev => ({ ...prev, [g.slug]: !prev[g.slug] }))}
                    >
                      <span className="inline">
                        <span>{isOpen ? '▾' : '▸'}</span>
                        <strong>{g.useCase}</strong>
                        <span className="tag">{g.slug}</span>
                      </span>
                      <span className="muted">{g.testCases.length} tests · {passedInGroup} passed</span>
                    </button>
                    {isOpen && (
                      <div className="table-scroll">
                        <table className="suite-table">
                          <thead>
                            <tr>
                              <th style={{ width: 32 }} />
                              <th style={{ width: 92 }}>ID</th>
                              <th style={{ width: 112 }}>Latest Status</th>
                              <th>Test Name</th>
                              <th style={{ width: 90 }}>Latency</th>
                              <th style={{ width: 90 }}>Priority</th>
                              <th style={{ width: 110 }} />
                            </tr>
                          </thead>
                          <tbody>
                            {g.testCases.map(tc => (
                              <tr key={tc.id} className={drawerCaseId === tc.id ? 'selected-row' : ''}>
                                <td>
                                  <input
                                    type="checkbox"
                                    checked={selectedIds.includes(tc.id)}
                                    onChange={() => toggleSelect(tc.id)}
                                    aria-label={`Chọn ${tc.id}`}
                                  />
                                </td>
                                <td><code>{tc.id}</code></td>
                                <td><span className={`ts-badge ${tc.status.toLowerCase()}`}>{tc.status}</span></td>
                                <td>
                                  <button type="button" className="case-link" onClick={() => openCaseDrawer(tc)}>
                                    <strong>{tc.name}</strong>
                                  </button>
                                  <small className="muted">
                                    {tc.httpMethod} {tc.targetUrl}
                                    {tc.visitedPages?.length > 1 ? ` · Hành trình ${tc.visitedPages.length} trang (${tc.visitedPages.join(' → ')})` : ''}
                                  </small>
                                  {tc.failureSummary && <small className="failure-inline">{tc.failureSummary}</small>}
                                </td>
                                <td>{tc.latencyMs != null ? `${tc.latencyMs} ms` : '—'}</td>
                                <td><span className={`prio-pill ${normalizePriorityClass(tc.priority)}`}>{tc.priority}</span></td>
                                <td>
                                  <button type="button" className="button small" onClick={() => sendToLoadTest(tc)} title="Đưa sang kiểm thử chịu tải STEP_STRESS">
                                    ⚡ Tải
                                  </button>
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    )}
                  </div>
                )
              })}
              {!filteredGroups.length && <p className="muted">Không có Test Case nào khớp bộ lọc hiện tại.</p>}
            </div>
          )}
        </section>
      )}

      {drawerCase && (
        <div className="drawer-backdrop" onClick={() => setDrawerCaseId(null)}>
          <aside className="suite-drawer" onClick={e => e.stopPropagation()} aria-label="Chi tiết Test Case">
            <header className="drawer-head">
              <div>
                <div className="inline">
                  <code>{drawerCase.id}</code>
                  <span className={`ts-badge ${drawerCase.status.toLowerCase()}`}>{drawerCase.status}</span>
                  <span className={`prio-pill ${normalizePriorityClass(drawerCase.priority)}`}>{drawerCase.priority}</span>
                </div>
                <h3>{drawerCase.name}</h3>
                <small className="muted">{drawerCase.httpMethod} {drawerCase.targetUrl}{drawerCase.lastRunAt ? ` · Chạy lúc ${date(drawerCase.lastRunAt)}` : ''}</small>
              </div>
              <div className="inline">
                <button
                  type="button"
                  className="button primary small"
                  disabled={aiExplaining}
                  onClick={() => explainCaseWithAi(drawerCase)}
                >
                  {aiExplaining ? '✦ AI đang phân tích…' : '✦ AI Tóm tắt & Giải thích'}
                </button>
                <button type="button" className="button small" onClick={() => sendToLoadTest(drawerCase)}>⚡ Đưa sang Stress Test</button>
                <button type="button" className="modal-close" onClick={() => setDrawerCaseId(null)} aria-label="Đóng">×</button>
              </div>
            </header>

            {/* Hoạt ảnh chờ khi AI đang phân tích sâu Test Case */}
            {aiExplaining && (
              <div className="activity-loader-card compact" role="status">
                <div className="activity-loader-head">
                  <div className="activity-pulse-ring" />
                  <div>
                    <strong>AI đang đọc nội dung trang & phân tích nguyên nhân cho {drawerCase.id}…</strong>
                    <p className="muted" style={{ margin: 0 }}>Đang tổng hợp bản tóm tắt tiếng Việt dễ hiểu cho Preview · Error · Cause · Fix</p>
                  </div>
                </div>
                <div className="activity-shimmer-track"><div className="activity-shimmer-bar" /></div>
              </div>
            )}

            {/* Thẻ AI Tóm tắt tổng quan (Preview + Kết quả) ngay đầu Drawer */}
            <div className={`ai-summary-banner ${drawerCase.status === 'FAILED' ? 'is-failed' : ''}`}>
              <div className="ai-summary-head">
                <strong>
                  ✦ Tóm tắt nhanh cho người kiểm thử
                  {drawerCase.diagnosis?.aiGenerated ? ' (AI Summary)' : ''}
                </strong>
                {drawerCase.visitedPages?.length > 0 && (
                  <small className="muted">Luồng duyệt: {drawerCase.visitedPages.join(' → ')}</small>
                )}
              </div>
              <p>
                {drawerCase.diagnosis?.previewSummary
                  || (drawerCase.status === 'FAILED'
                    ? drawerCase.failureSummary
                    : `Kịch bản kiểm tra ${drawerCase.httpMethod} ${drawerCase.targetUrl}. Bấm "✦ AI Tóm tắt & Giải thích" hoặc chạy Suite để xem bản phân tích chi tiết.`)}
              </p>
            </div>

            <div className="drawer-columns">
              <div className="drawer-col">
                <div className="segmented">
                  <button type="button" className={leftTab === 'preview' ? 'active' : ''} onClick={() => setLeftTab('preview')}>Preview</button>
                  <button type="button" className={leftTab === 'log' ? 'active' : ''} onClick={() => setLeftTab('log')}>Run Log</button>
                  <button type="button" className={leftTab === 'code' ? 'active' : ''} onClick={() => setLeftTab('code')}>Code</button>
                </div>

                {leftTab === 'preview' && (() => {
                  const pPath = safePathFromUrl(drawerCase.preview?.finalUrl || drawerCase.targetUrl)
                  const basePath = pPath.split('?')[0]
                  const htmlSnap = drawerCase.preview?.htmlSnapshot
                    || snapshotByPath[pPath]?.htmlSnapshot
                    || snapshotByPath[basePath]?.htmlSnapshot
                    || ''
                  const pv = drawerCase.preview || snapshotByPath[pPath] || snapshotByPath[basePath]
                  return (pv || htmlSnap) ? (
                    <div className="drawer-box">
                      {htmlSnap && (
                        <div className={`drawer-live-frame ${drawerExpandedWeb ? 'is-expanded' : ''}`}>
                          <div className="rm-fallback-bar">
                            <span /><span /><span />
                            <small style={{ flex: 1 }}>{pv?.finalUrl || drawerCase.targetUrl}</small>
                            <div className="inline" style={{ gap: '0.25rem' }}>
                              {[0.4, 0.55, 0.8, 1].map(sc => (
                                <button
                                  key={sc}
                                  type="button"
                                  className={`button ghost small ${drawerZoom === sc ? 'active-chip' : ''}`}
                                  style={{ padding: '0.1rem 0.38rem', fontSize: '0.72rem' }}
                                  onClick={() => setDrawerZoom(sc)}
                                >
                                  {Math.round(sc * 100)}%
                                </button>
                              ))}
                              <button
                                type="button"
                                className="button ghost small"
                                style={{ padding: '0.1rem 0.45rem', fontSize: '0.72rem' }}
                                onClick={() => setDrawerExpandedWeb(v => !v)}
                              >
                                {drawerExpandedWeb ? 'Thu gọn' : '⛶ Mở rộng'}
                              </button>
                            </div>
                          </div>
                          <div className="drawer-iframe-wrap" style={{ height: drawerExpandedWeb ? 460 : 230 }}>
                            <iframe
                              sandbox=""
                              srcDoc={htmlSnap}
                              title={drawerCase.id}
                              loading="lazy"
                              style={{
                                width: `${Math.round(100 / drawerZoom)}%`,
                                height: `${Math.round((drawerExpandedWeb ? 460 : 230) / drawerZoom)}px`,
                                transform: `scale(${drawerZoom})`,
                                transformOrigin: 'top left',
                              }}
                            />
                          </div>
                        </div>
                      )}
                      {pv && (
                        <>
                          <dl className="preview-kv">
                            <div><dt>Final URL</dt><dd>{pv.finalUrl || drawerCase.targetUrl}</dd></div>
                            <div><dt>HTTP Status</dt><dd>{pv.httpStatus ?? '—'}</dd></div>
                            <div><dt>Latency</dt><dd>{pv.latencyMs != null ? `${pv.latencyMs} ms` : '—'}</dd></div>
                            <div><dt>Content-Type</dt><dd>{pv.contentType || 'text/html'}</dd></div>
                            <div><dt>Kích thước</dt><dd>{pv.contentBytes != null ? `${pv.contentBytes} bytes` : '—'}</dd></div>
                            <div><dt>Page Title</dt><dd>{pv.pageTitle || '—'}</dd></div>
                          </dl>
                          {pv.headings?.length > 0 && (
                            <div><strong>Các mục chính trên trang (H1/H2):</strong><ul>{pv.headings.map((h, i) => <li key={i}>{h}</li>)}</ul></div>
                          )}
                          {pv.excerpt && <p className="muted">{pv.excerpt}</p>}
                        </>
                      )}
                    </div>
                  ) : <p className="muted">Chưa có dữ liệu chạy. Hãy bấm Run All để thu thập Preview.</p>
                })()}

                {leftTab === 'log' && (
                  <div className="drawer-box">
                    {drawerCase.runLog?.length ? (
                      <ol className="run-log-list">
                        {drawerCase.runLog.map(step => (
                          <li key={step.stepNumber} className={`log-item ${step.status.toLowerCase()}`}>
                            <div className="inline">
                              <span className={`ts-badge ${step.status.toLowerCase()}`}>{step.status}</span>
                              <strong>{step.action}</strong>
                              <small className="muted">{step.durationMs} ms</small>
                            </div>
                            <p className="muted">{step.detail}</p>
                          </li>
                        ))}
                      </ol>
                    ) : <p className="muted">Chưa có Run Log. Hãy chạy Test Suite để xem chi tiết từng bước.</p>}
                  </div>
                )}

                {leftTab === 'code' && (
                  <div className="drawer-box">
                    <div className="inline" style={{ justifyContent: 'space-between', marginBottom: 8 }}>
                      <strong>Playwright / TypeScript</strong>
                      <button type="button" className="button small" onClick={() => copyText(drawerCase.playwrightCode || '', 'Đã sao chép Playwright code')}>Copy Code</button>
                    </div>
                    <pre className="code-block">{drawerCase.playwrightCode || '// Chưa có mã sinh tự động'}</pre>
                  </div>
                )}
              </div>

              <div className="drawer-col">
                <div className="segmented">
                  <button type="button" className={rightTab === 'error' ? 'active' : ''} onClick={() => setRightTab('error')}>Error</button>
                  <button type="button" className={rightTab === 'cause' ? 'active' : ''} onClick={() => setRightTab('cause')}>Cause</button>
                  <button type="button" className={rightTab === 'fix' ? 'active' : ''} onClick={() => setRightTab('fix')}>Fix</button>
                  <button type="button" className={rightTab === 'plan' ? 'active' : ''} onClick={() => setRightTab('plan')}>Test Plan</button>
                </div>

                {rightTab !== 'plan' ? (
                  drawerCase.diagnosis ? (
                    <div className="drawer-box">
                      {rightTab === 'error' && (
                        <div className="stack">
                          <strong>Giải thích trạng thái / Lỗi ghi nhận:</strong>
                          <pre className={`code-block ${drawerCase.status === 'FAILED' ? 'error-text' : ''}`}>{drawerCase.diagnosis.error}</pre>
                          {drawerCase.diagnosis.previewSummary && (
                            <p className="muted" style={{ margin: 0 }}>{drawerCase.diagnosis.previewSummary}</p>
                          )}
                        </div>
                      )}
                      {rightTab === 'cause' && (
                        <div className="stack">
                          <strong>Nguyên nhân phân tích (Cause):</strong>
                          <p style={{ lineHeight: 1.6, margin: 0 }}>{drawerCase.diagnosis.cause}</p>
                        </div>
                      )}
                      {rightTab === 'fix' && (
                        <div className="stack">
                          <strong>Hướng khắc phục &amp; tối ưu (Fix):</strong>
                          <p style={{ lineHeight: 1.6, margin: 0 }}>{drawerCase.diagnosis.fix}</p>
                          <button type="button" className="button primary small" onClick={() => copyText(drawerCase.diagnosis.fixPrompt, '✓ Đã sao chép Fix Prompt')}>
                            📋 Copy Fix Prompt cho AI/IDE
                          </button>
                        </div>
                      )}
                    </div>
                  ) : (
                    <div className="drawer-box stack">
                      <p className="muted">
                        {drawerCase.status === 'PASSED'
                          ? 'Test Case này đang đạt toàn bộ assertion. Bạn có thể bấm nút bên dưới để nhờ AI tóm tắt chi tiết nội dung và gợi ý kiểm thử nâng cao.'
                          : 'Chưa có chẩn đoán lỗi. Hãy chạy Test Case hoặc bấm nút bên dưới để AI phân tích Error → Cause → Fix.'}
                      </p>
                      <button
                        type="button"
                        className="button primary small"
                        disabled={aiExplaining}
                        onClick={() => explainCaseWithAi(drawerCase)}
                      >
                        {aiExplaining ? '✦ AI đang phân tích…' : '✦ Nhờ AI Tóm tắt & Phân tích ngay'}
                      </button>
                    </div>
                  )
                ) : (
                  <div className="drawer-box">
                    <p><strong>Mô tả:</strong> {drawerCase.description || drawerCase.name}</p>
                    <strong>Các bước thực thi:</strong>
                    <ol>{(drawerCase.steps || []).map((s, i) => <li key={i}>{s}</li>)}</ol>
                  </div>
                )}
              </div>
            </div>
          </aside>
        </div>
      )}
    </div>
  )
}
