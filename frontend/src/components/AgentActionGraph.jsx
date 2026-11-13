import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

const NODE_W = 172
const FRAME_H = 98
const COL_GAP = 268
const ROW_GAP = 156
const PAD_X = 64
const PAD_Y = 56

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

function MiniWebThumbnail({
  snapshot,
  status,
  path,
  title,
  headings = [],
  excerpt = '',
  small = false,
  liveIframe = false,
}) {
  const isError = Number(status) >= 400
  if (isError) {
    return (
      <div className={`rm-mini-error ${small ? 'is-small' : ''}`}>
        <span className="rm-err-code">HTTP {status}</span>
        <strong>{status === 404 ? 'Not Found' : 'Server Error'}</strong>
        <small>{path}</small>
      </div>
    )
  }
  if (liveIframe && snapshot) {
    return (
      <div className={`rm-mini-viewport ${small ? 'is-small' : ''}`}>
        <iframe
          sandbox=""
          srcDoc={snapshot}
          title={path}
          loading="lazy"
          tabIndex={-1}
        />
      </div>
    )
  }
  if (small) {
    return (
      <div className="rm-mini-fallback is-small">
        <div className="rm-fallback-bar">
          <span /><span /><span />
        </div>
        <strong>{title || path}</strong>
        <small>{path}</small>
      </div>
    )
  }
  const headline = headings?.[0] || title || path
  const subline = headings?.[1] || (excerpt ? excerpt.slice(0, 68) : path)
  return (
    <div className={`rm-mini-fallback rm-mini-dom-card ${snapshot ? 'has-snap' : ''}`}>
      <div className="rm-fallback-bar">
        <span /><span /><span />
        <em className="rm-mini-url-bar">{path}</em>
      </div>
      <div className="rm-mini-dom-body">
        <div className="rm-mini-hero-chip">{status || 200} OK</div>
        <strong>{headline}</strong>
        <small>{subline}</small>
        <div className="rm-mini-wireframe" aria-hidden="true">
          <span className="w-70" />
          <span className="w-90" />
          <span className="w-55" />
        </div>
      </div>
    </div>
  )
}

export default function AgentActionGraph({
  suite,
  filteredGroups,
  snapshotByPath,
  onOpenCase,
  onStressTest,
  liveTraversal = null,
}) {
  const containerRef = useRef(null)
  const [zoom, setZoom] = useState(0.88)
  const [pan, setPan] = useState({ x: 24, y: 24 })
  const [dragging, setDragging] = useState(false)
  const [isFullscreen, setIsFullscreen] = useState(false)
  const dragOrigin = useRef({ x: 0, y: 0, panX: 0, panY: 0 })

  const [selectedPath, setSelectedPath] = useState(null)
  const [hoverTestId, setHoverTestId] = useState(null)
  const [panelCollapsed, setPanelCollapsed] = useState({})
  const [zoomedWebNode, setZoomedWebNode] = useState(null)
  const [webScale, setWebScale] = useState(1)

  const rootPath = useMemo(() => safePathFromUrl(suite?.targetUrl || '/'), [suite?.targetUrl])

  const graph = useMemo(() => {
    const nodeMap = new Map()
    const edgeMap = new Map()
    const testPathMap = new Map()

    function ensureNode(path, depthHint) {
      if (!nodeMap.has(path)) {
        const snap = snapshotByPath[path] || snapshotByPath[path.split('?')[0]]
        nodeMap.set(path, {
          path,
          depth: Math.min(3, Math.max(0, depthHint)),
          title: snap?.pageTitle || path,
          htmlSnapshot: snap?.htmlSnapshot || '',
          httpStatus: snap?.httpStatus || 200,
          fullUrl: snap?.finalUrl || (path.startsWith('http') ? path : ''),
          touchedElements: snap?.touchedElements || [],
          headings: snap?.headings || [],
          excerpt: snap?.excerpt || '',
          testIds: new Set(),
          testList: [],
          touchCount: 0,
          directFailCount: 0,
          directPassCount: 0,
          arrivedFrom: new Map(),
          leadsTo: new Map(),
        })
      } else if (depthHint < nodeMap.get(path).depth) {
        nodeMap.get(path).depth = Math.min(3, Math.max(0, depthHint))
      }
      return nodeMap.get(path)
    }

    ensureNode(rootPath, 0)

    for (const g of filteredGroups || []) {
      for (const tc of g.testCases || []) {
        const targetPath = safePathFromUrl(tc.targetUrl)
        const rawChain = tc.visitedPages?.length
          ? tc.visitedPages
          : targetPath === rootPath
            ? [rootPath]
            : [rootPath, targetPath]

        const chain = []
        for (const p of rawChain) {
          if (!p) continue
          if (chain[chain.length - 1] !== p) chain.push(p)
        }
        if (chain[0] !== rootPath) chain.unshift(rootPath)
        testPathMap.set(tc.id, chain)

        for (let i = 0; i < chain.length; i++) {
          const p = chain[i]
          const node = ensureNode(p, i)
          node.touchCount += 1
          if (!node.testIds.has(tc.id)) {
            node.testIds.add(tc.id)
            node.testList.push(tc)
          }
          if (!node.fullUrl && i === chain.length - 1) node.fullUrl = tc.targetUrl
          if (i === chain.length - 1) {
            if (tc.status === 'FAILED') {
              node.directFailCount += 1
              if (tc.preview?.httpStatus >= 400) node.httpStatus = tc.preview.httpStatus
            } else if (tc.status === 'PASSED') {
              node.directPassCount += 1
            }
            if (tc.preview?.pageTitle && node.title === node.path) node.title = tc.preview.pageTitle
            if (tc.preview?.htmlSnapshot && !node.htmlSnapshot) node.htmlSnapshot = tc.preview.htmlSnapshot
            if (tc.preview?.touchedElements?.length && !node.touchedElements.length) {
              node.touchedElements = tc.preview.touchedElements
            }
          }
          if (i > 0) {
            const prevPath = chain[i - 1]
            const prevNode = ensureNode(prevPath, i - 1)
            const action = (i === chain.length - 1 && tc.actionLabel) ? tc.actionLabel : `Navigate to ${p}`
            if (!node.arrivedFrom.has(prevPath)) node.arrivedFrom.set(prevPath, action)
            if (!prevNode.leadsTo.has(p)) prevNode.leadsTo.set(p, action)

            const edgeKey = `${prevPath}=>${p}`
            if (!edgeMap.has(edgeKey)) {
              edgeMap.set(edgeKey, {
                key: edgeKey,
                from: prevPath,
                to: p,
                count: 0,
                hasFail: false,
                testIds: new Set(),
              })
            }
            const edge = edgeMap.get(edgeKey)
            edge.count += 1
            edge.testIds.add(tc.id)
            if (tc.status === 'FAILED' && i === chain.length - 1) edge.hasFail = true
          }
        }
      }
    }

    const columns = [[], [], [], []]
    for (const node of nodeMap.values()) {
      columns[Math.min(3, Math.max(0, node.depth))].push(node)
    }

    const posIndex = new Map()
    columns[0].forEach((n, idx) => posIndex.set(n.path, idx))
    for (let d = 1; d < 4; d++) {
      columns[d].sort((a, b) => {
        const pa = Array.from(a.arrivedFrom.keys())[0] || rootPath
        const pb = Array.from(b.arrivedFrom.keys())[0] || rootPath
        const ia = posIndex.get(pa) ?? 999
        const ib = posIndex.get(pb) ?? 999
        if (ia !== ib) return ia - ib
        return b.testList.length - a.testList.length
      })
      columns[d].forEach((n, idx) => posIndex.set(n.path, idx))
    }

    const maxRows = Math.max(1, ...columns.map(c => c.length))
    const totalHeight = Math.max(480, PAD_Y * 2 + maxRows * ROW_GAP)
    const activeCols = columns.reduce((maxD, col, d) => (col.length ? d + 1 : maxD), 1)
    const totalWidth = Math.max(780, PAD_X * 2 + (activeCols - 1) * COL_GAP + NODE_W + 380)

    const positioned = new Map()
    for (let d = 0; d < 4; d++) {
      const col = columns[d]
      if (!col.length) continue
      const colHeight = col.length * ROW_GAP - (ROW_GAP - FRAME_H)
      const startY = Math.max(PAD_Y, (totalHeight - colHeight) / 2)
      col.forEach((node, i) => {
        positioned.set(node.path, {
          ...node,
          x: PAD_X + d * COL_GAP,
          y: startY + i * ROW_GAP,
        })
      })
    }

    const edges = Array.from(edgeMap.values())
      .map(e => {
        const fromNode = positioned.get(e.from)
        const toNode = positioned.get(e.to)
        if (!fromNode || !toNode) return null
        return {
          ...e,
          x1: fromNode.x + NODE_W,
          y1: fromNode.y + FRAME_H / 2,
          x2: toNode.x,
          y2: toNode.y + FRAME_H / 2,
        }
      })
      .filter(Boolean)

    return {
      nodes: Array.from(positioned.values()),
      positioned,
      edges,
      testPathMap,
      totalWidth,
      totalHeight,
    }
  }, [filteredGroups, snapshotByPath, rootPath])

  const fitToMap = useCallback(() => {
    const el = containerRef.current
    if (!el) return
    const viewW = Math.max(360, el.clientWidth - 350)
    const viewH = Math.max(320, el.clientHeight - 32)
    const contentW = Math.max(400, graph.totalWidth - 360)
    const contentH = Math.max(320, graph.totalHeight)
    const nextZoom = Math.max(0.35, Math.min(1.25, Math.min(viewW / contentW, viewH / contentH) * 0.92))
    setZoom(Number(nextZoom.toFixed(2)))
    setPan({
      x: Math.max(12, (viewW - contentW * nextZoom) / 2),
      y: Math.max(12, (viewH - contentH * nextZoom) / 2),
    })
  }, [graph.totalWidth, graph.totalHeight])

  const fitToMapRef = useRef(fitToMap)
  useEffect(() => {
    fitToMapRef.current = fitToMap
  }, [fitToMap])

  useEffect(() => {
    setSelectedPath(null)
    fitToMapRef.current()
  }, [suite?.id])

  // Khi Agent đang duyệt sâu (liveTraversal), tự động căn khung nhìn hướng tới node đang duyệt
  useEffect(() => {
    if (!liveTraversal?.activePath) return
    const targetNode = graph.positioned.get(liveTraversal.activePath)
    const el = containerRef.current
    if (!targetNode || !el) return
    const viewW = Math.max(360, el.clientWidth - 350)
    const viewH = Math.max(320, el.clientHeight - 40)
    const desiredX = viewW / 2 - (targetNode.x + NODE_W / 2) * zoom
    const desiredY = viewH / 2 - (targetNode.y + FRAME_H / 2) * zoom
    setPan({
      x: Math.round(desiredX),
      y: Math.round(desiredY),
    })
  }, [liveTraversal?.activePath, graph.positioned, zoom])

  const hoveredChainSet = useMemo(() => {
    if (liveTraversal?.activeChain?.length) {
      return new Set(liveTraversal.activeChain)
    }
    if (!hoverTestId) return null
    const chain = graph.testPathMap.get(hoverTestId)
    return chain ? new Set(chain) : null
  }, [hoverTestId, graph.testPathMap, liveTraversal])

  const liveEdgeKeys = useMemo(() => {
    if (!liveTraversal?.activeChain || liveTraversal.activeChain.length < 2) return null
    const s = new Set()
    for (let i = 1; i < liveTraversal.activeChain.length; i++) {
      s.add(`${liveTraversal.activeChain[i - 1]}=>${liveTraversal.activeChain[i]}`)
    }
    return s
  }, [liveTraversal])

  const selectedNode = selectedPath ? graph.positioned.get(selectedPath) || null : null
  const activeLiveNode = liveTraversal?.activePath
    ? graph.positioned.get(liveTraversal.activePath) || graph.positioned.get(rootPath)
    : null

  function handleWheel(e) {
    if (e.target.closest('.rm-right-panel') || e.target.closest('.rm-web-lightbox')) return
    e.preventDefault()
    const delta = e.deltaY < 0 ? 0.1 : -0.1
    setZoom(z => Math.max(0.25, Math.min(2.5, Number((z + delta).toFixed(2)))))
  }

  function startDrag(e) {
    if (
      e.target.closest('button') ||
      e.target.closest('input') ||
      e.target.closest('.rm-right-panel') ||
      e.target.closest('.rm-live-spotlight') ||
      e.target.closest('.rm-web-lightbox')
    ) {
      return
    }
    setDragging(true)
    dragOrigin.current = { x: e.clientX, y: e.clientY, panX: pan.x, panY: pan.y }
  }

  function onDragMove(e) {
    if (!dragging) return
    setPan({
      x: dragOrigin.current.panX + (e.clientX - dragOrigin.current.x),
      y: dragOrigin.current.panY + (e.clientY - dragOrigin.current.y),
    })
  }

  function stopDrag() {
    setDragging(false)
  }

  return (
    <div
      ref={containerRef}
      className={`rm-canvas-shell ${dragging ? 'is-dragging' : ''} ${isFullscreen ? 'is-fullscreen' : ''}`}
      onMouseDown={startDrag}
      onMouseMove={onDragMove}
      onMouseUp={stopDrag}
      onMouseLeave={stopDrag}
      onWheel={handleWheel}
    >
      {/* Floating Zoom / Slider / Fullscreen Controls */}
      <div className="rm-controls" aria-label="Điều khiển phóng to thu nhỏ bản đồ">
        <button
          type="button"
          title="Thu nhỏ (Zoom Out)"
          onClick={() => setZoom(z => Math.max(0.25, Number((z - 0.15).toFixed(2))))}
        >
          −
        </button>
        <input
          type="range"
          min="0.25"
          max="2.5"
          step="0.05"
          value={zoom}
          aria-label="Thanh trượt phóng to thu nhỏ"
          onChange={e => setZoom(Number(e.target.value))}
          className="rm-zoom-slider"
        />
        <button
          type="button"
          title="Phóng to (Zoom In)"
          onClick={() => setZoom(z => Math.min(2.5, Number((z + 0.15).toFixed(2))))}
        >
          ＋
        </button>
        <button type="button" title="Vừa khung nhìn (Fit Map)" onClick={fitToMap}>⊡</button>
        <button
          type="button"
          title={isFullscreen ? 'Thoát toàn màn hình' : 'Mở rộng toàn màn hình'}
          onClick={() => setIsFullscreen(f => !f)}
        >
          {isFullscreen ? '✕' : '⛶'}
        </button>
        <span className="rm-zoom-readout">{Math.round(zoom * 100)}%</span>
      </div>

      {/* Live Agent Spotlight Banner during execution */}
      {liveTraversal && (
        <div className="rm-live-spotlight">
          <div className="rm-live-spotlight-head">
            <span className="rm-radar-dot" />
            <div>
              <strong>
                Agent đang thao tác sâu · Tầng {liveTraversal.waveIndex}/{liveTraversal.totalWaves}
              </strong>
              <small>
                Đã duyệt {liveTraversal.completedCount}/{liveTraversal.totalCount} kịch bản · Nhịp trễ {liveTraversal.delaySec}s
              </small>
            </div>
            {activeLiveNode && (
              <button
                type="button"
                className="button ghost small"
                onClick={() => {
                  setZoomedWebNode(activeLiveNode)
                  setWebScale(1)
                }}
              >
                🔍 Phóng to Web
              </button>
            )}
          </div>
          <div className="rm-live-chain">
            {(liveTraversal.activeChain || [rootPath]).map((stepPath, idx) => (
              <span key={stepPath + idx} className="rm-chain-step">
                {idx > 0 && <em className="rm-chain-arrow">→</em>}
                <code>{stepPath}</code>
              </span>
            ))}
          </div>
          {activeLiveNode && (
            <div className="rm-live-mini-stage">
              <MiniWebThumbnail
                snapshot={activeLiveNode.htmlSnapshot}
                status={activeLiveNode.httpStatus}
                path={activeLiveNode.path}
                title={activeLiveNode.title}
                headings={activeLiveNode.headings}
                excerpt={activeLiveNode.excerpt}
                liveIframe
              />
              <div className="rm-live-mini-meta">
                <strong>{liveTraversal.activeCase?.id} · {liveTraversal.activeCase?.name}</strong>
                <span>{liveTraversal.activeCase?.actionLabel || `Đang kiểm tra ${activeLiveNode.path}`}</span>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Interactive Branching Tree Canvas */}
      <div
        className="rm-world"
        style={{
          width: graph.totalWidth,
          height: graph.totalHeight,
          transform: `translate(${pan.x}px, ${pan.y}px) scale(${zoom})`,
        }}
      >
        <svg className="rm-svg-layer" width={graph.totalWidth} height={graph.totalHeight}>
          {graph.edges.map(edge => {
            const dx = Math.max(48, Math.abs(edge.x2 - edge.x1) * 0.45)
            const d = `M ${edge.x1} ${edge.y1} C ${edge.x1 + dx} ${edge.y1}, ${edge.x2 - dx} ${edge.y2}, ${edge.x2} ${edge.y2}`
            const isLiveEdge = liveEdgeKeys ? liveEdgeKeys.has(edge.key) : false
            const isHighlighted = isLiveEdge
              || (hoverTestId && edge.testIds.has(hoverTestId))
              || (selectedPath && (edge.from === selectedPath || edge.to === selectedPath))
            const isDimmed = (liveEdgeKeys && !isLiveEdge) || (hoverTestId && !edge.testIds.has(hoverTestId))
            return (
              <path
                key={edge.key}
                d={d}
                className={`rm-edge ${edge.hasFail ? 'is-fail' : ''} ${isHighlighted ? 'is-hi' : ''} ${isLiveEdge ? 'is-live-flow' : ''} ${isDimmed ? 'is-dim' : ''}`}
              />
            )
          })}
        </svg>

        {graph.nodes.map(node => {
          const isSelected = selectedPath === node.path
          const isLiveActive = liveTraversal?.activePath === node.path
          const isHighlighted = hoveredChainSet ? hoveredChainSet.has(node.path) : isSelected
          const isDimmed = hoveredChainSet ? !hoveredChainSet.has(node.path) : false
          const hasFail = node.directFailCount > 0 || node.httpStatus >= 400
          return (
            <div
              key={node.path}
              className={`rm-node-wrap ${isDimmed ? 'is-dim' : ''} ${isLiveActive ? 'is-live-target' : ''}`}
              style={{ left: node.x, top: node.y, width: NODE_W }}
            >
              <div className="rm-node-path" title={node.path}>
                {node.depth === 0 && <span className="rm-start-pill">start · {suite?.totalCases || node.testList.length}</span>}
                {isLiveActive && <span className="rm-live-pill">🤖 Đang duyệt...</span>}
                <span>{node.path}</span>
              </div>

              <div className="rm-frame-container">
                <button
                  type="button"
                  className={`rm-frame ${hasFail ? 'rm-has-fail' : node.directPassCount > 0 ? 'rm-has-pass' : ''} ${isHighlighted ? 'rm-hi' : ''} ${isSelected ? 'rm-sel' : ''} ${isLiveActive ? 'rm-agent-active' : ''}`}
                  style={{ width: NODE_W, height: FRAME_H }}
                  onClick={() => setSelectedPath(prev => (prev === node.path ? null : node.path))}
                >
                  <MiniWebThumbnail
                    snapshot={node.htmlSnapshot}
                    status={node.httpStatus}
                    path={node.path}
                    title={node.title}
                    headings={node.headings}
                    excerpt={node.excerpt}
                    liveIframe={isSelected || isLiveActive}
                  />
                </button>
                <button
                  type="button"
                  className="rm-zoom-web-btn"
                  title="Phóng to giao diện trang web này"
                  onClick={e => {
                    e.stopPropagation()
                    setZoomedWebNode(node)
                    setWebScale(1)
                  }}
                >
                  🔍
                </button>
              </div>

              <div className="rm-node-meta">
                {node.directFailCount > 0 && <span className="rm-fail-text">{node.directFailCount} failed here · </span>}
                <span>{node.testList.length} {node.testList.length === 1 ? 'test' : 'tests'} · {node.touchCount} {node.touchCount === 1 ? 'touch' : 'touches'}</span>
              </div>
            </div>
          )
        })}
      </div>

      {/* Floating Right Panel: Page Inspector or Tests List */}
      <aside className="rm-right-panel" aria-label={selectedNode ? 'Page Inspector' : 'Tests in this run'}>
        {selectedNode ? (
          <div className="rm-inspector">
            <div className="rm-panel-head">
              <div>
                <button type="button" className="text-button" onClick={() => setSelectedPath(null)}>← Quay lại Tests</button>
                <h3>{selectedNode.path}</h3>
                <small className="muted">
                  {selectedNode.testList.length} tests reached · {selectedNode.touchCount} touches
                  {selectedNode.directFailCount > 0 ? ` · ${selectedNode.directFailCount} failed here` : ''}
                </small>
              </div>
            </div>

            <div className="rm-inspector-scroll">
              <div className="rm-inspector-preview">
                <MiniWebThumbnail
                  snapshot={selectedNode.htmlSnapshot}
                  status={selectedNode.httpStatus}
                  path={selectedNode.path}
                  title={selectedNode.title}
                  headings={selectedNode.headings}
                  excerpt={selectedNode.excerpt}
                  liveIframe
                />
              </div>

              <div className="button-row" style={{ gap: '0.45rem' }}>
                <button
                  type="button"
                  className="button ghost small"
                  style={{ flex: 1 }}
                  onClick={() => {
                    setZoomedWebNode(selectedNode)
                    setWebScale(1)
                  }}
                >
                  🔍 Phóng to Web
                </button>
                <button
                  type="button"
                  className="button primary small"
                  style={{ flex: 1 }}
                  onClick={() => onStressTest({
                    id: `NODE-${selectedNode.depth}`,
                    name: selectedNode.title || selectedNode.path,
                    targetUrl: selectedNode.fullUrl || suite.targetUrl,
                    httpMethod: 'GET',
                  })}
                >
                  ⚡ Stress Lab
                </button>
              </div>

              {selectedNode.touchedElements.length > 0 && (
                <div className="rm-insp-block">
                  <h4>Touched here ({selectedNode.touchedElements.length})</h4>
                  <ul>
                    {selectedNode.touchedElements.map((el, i) => (
                      <li key={i}><span className="rm-dot-icon">⊙</span> {el}</li>
                    ))}
                  </ul>
                </div>
              )}

              {selectedNode.arrivedFrom.size > 0 && (
                <div className="rm-insp-block">
                  <h4>Arrived from ({selectedNode.arrivedFrom.size})</h4>
                  <div className="rm-link-chips">
                    {Array.from(selectedNode.arrivedFrom.entries()).map(([fromPath, act]) => (
                      <button key={fromPath} type="button" className="rm-nav-chip" onClick={() => setSelectedPath(fromPath)}>
                        <strong>← {fromPath}</strong>
                        <small>{act}</small>
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {selectedNode.leadsTo.size > 0 && (
                <div className="rm-insp-block">
                  <h4>Leads to ({selectedNode.leadsTo.size})</h4>
                  <div className="rm-link-chips">
                    {Array.from(selectedNode.leadsTo.entries()).map(([toPath, act]) => (
                      <button key={toPath} type="button" className="rm-nav-chip" onClick={() => setSelectedPath(toPath)}>
                        <strong>→ {toPath}</strong>
                        <small>{act}</small>
                      </button>
                    ))}
                  </div>
                </div>
              )}

              <div className="rm-insp-block">
                <h4>Tests that reached it ({selectedNode.testList.length})</h4>
                <div className="rm-test-list">
                  {selectedNode.testList.map(tc => (
                    <button
                      key={tc.id}
                      type="button"
                      className="rm-test-item"
                      onMouseEnter={() => setHoverTestId(tc.id)}
                      onMouseLeave={() => setHoverTestId(null)}
                      onClick={() => onOpenCase(tc)}
                    >
                      <span className={`rm-status-ico ${tc.status.toLowerCase()}`}>
                        {tc.status === 'PASSED' ? '✓' : tc.status === 'FAILED' ? '×' : '○'}
                      </span>
                      <div>
                        <strong>{tc.id} · {tc.name}</strong>
                        <small>{tc.latencyMs != null ? `${tc.latencyMs} ms` : tc.status}</small>
                      </div>
                    </button>
                  ))}
                </div>
              </div>
            </div>
          </div>
        ) : (
          <div className="rm-tests-panel">
            <div className="rm-panel-head">
              <strong>Tests ({filteredGroups.reduce((s, g) => s + g.testCases.length, 0)})</strong>
              <span className="muted">{graph.nodes.length} trang</span>
            </div>
            <div className="rm-tests-scroll">
              {filteredGroups.map(g => {
                const isOpen = !panelCollapsed[g.slug]
                return (
                  <div key={g.slug} className="rm-test-group">
                    <button
                      type="button"
                      className="rm-group-toggle"
                      onClick={() => setPanelCollapsed(p => ({ ...p, [g.slug]: !p[g.slug] }))}
                    >
                      <span>{isOpen ? '▾' : '▸'} {g.useCase}</span>
                      <small>{g.testCases.length}</small>
                    </button>
                    {isOpen && (
                      <div className="rm-test-list">
                        {g.testCases.map(tc => {
                          const p = safePathFromUrl(tc.targetUrl)
                          const nodeSnap = snapshotByPath[p] || snapshotByPath[p.split('?')[0]]
                          const pagesCount = tc.visitedPages?.length || (p === rootPath ? 1 : 2)
                          return (
                            <button
                              key={tc.id}
                              type="button"
                              className={`rm-test-item ${hoverTestId === tc.id ? 'is-hovered' : ''}`}
                              onMouseEnter={() => setHoverTestId(tc.id)}
                              onMouseLeave={() => setHoverTestId(null)}
                              onClick={() => onOpenCase(tc)}
                            >
                              <div className="rm-item-thumb">
                                <MiniWebThumbnail
                                  snapshot={nodeSnap?.htmlSnapshot || ''}
                                  status={tc.preview?.httpStatus || 200}
                                  path={p}
                                  title={tc.name}
                                  small
                                />
                                <span className={`rm-status-ico ${tc.status.toLowerCase()}`}>
                                  {tc.status === 'PASSED' ? '✓' : tc.status === 'FAILED' ? '×' : '○'}
                                </span>
                              </div>
                              <div className="rm-item-info">
                                <strong>{tc.name}</strong>
                                <small>{pagesCount} {pagesCount === 1 ? 'page' : 'pages'}{tc.failureSummary ? ` · ${tc.failureSummary}` : ''}</small>
                              </div>
                            </button>
                          )
                        })}
                      </div>
                    )}
                  </div>
                )
              })}
            </div>
            <div className="rm-panel-foot">Cuộn chuột để phóng to/thu nhỏ · Bấm 🔍 trên trang để xem toàn khung.</div>
          </div>
        )}
      </aside>

      {/* Full Zoomable Webpage Lightbox Modal */}
      {zoomedWebNode && (
        <div className="rm-web-lightbox" role="dialog" aria-label={`Xem giao diện web ${zoomedWebNode.path}`}>
          <div className="rm-web-lightbox-card">
            <div className="rm-web-lightbox-bar">
              <div className="rm-browser-dots"><span /><span /><span /></div>
              <div className="rm-web-url-pill">
                <strong>{zoomedWebNode.title || zoomedWebNode.path}</strong>
                <code>{zoomedWebNode.fullUrl || zoomedWebNode.path}</code>
              </div>
              <div className="rm-web-zoom-controls">
                <span className="muted" style={{ fontSize: '0.78rem' }}>Tỷ lệ hiển thị Web:</span>
                {[0.5, 0.75, 1, 1.25].map(scaleOpt => (
                  <button
                    key={scaleOpt}
                    type="button"
                    className={`button ghost small ${webScale === scaleOpt ? 'active-chip' : ''}`}
                    onClick={() => setWebScale(scaleOpt)}
                  >
                    {Math.round(scaleOpt * 100)}%
                  </button>
                ))}
                <button
                  type="button"
                  className="button primary small"
                  onClick={() => setZoomedWebNode(null)}
                >
                  Đóng ✕
                </button>
              </div>
            </div>

            <div className="rm-web-lightbox-viewport">
              {zoomedWebNode.htmlSnapshot ? (
                <div
                  className="rm-web-scaled-wrap"
                  style={{
                    transform: `scale(${webScale})`,
                    transformOrigin: 'top center',
                    width: `${Math.round(100 / webScale)}%`,
                    height: `${Math.round(100 / webScale)}%`,
                  }}
                >
                  <iframe
                    sandbox=""
                    srcDoc={zoomedWebNode.htmlSnapshot}
                    title={`Full preview ${zoomedWebNode.path}`}
                    className="rm-web-lightbox-iframe"
                  />
                </div>
              ) : (
                <div className="rm-web-lightbox-empty">
                  <h3>{zoomedWebNode.title || zoomedWebNode.path}</h3>
                  <p className="muted">HTTP {zoomedWebNode.httpStatus || 200} · {zoomedWebNode.fullUrl || zoomedWebNode.path}</p>
                  {zoomedWebNode.excerpt && <p>{zoomedWebNode.excerpt}</p>}
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
