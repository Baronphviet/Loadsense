import { useEffect, useState } from 'react'
import { Client } from '@stomp/stompjs'
import { api, terminal, websocketUrl } from './api'

export default function useExecution(id, token) {
  const [execution, setExecution] = useState(null)
  const [metrics, setMetrics] = useState([])
  const [report, setReport] = useState(null)
  const [connection, setConnection] = useState('polling')
  const [error, setError] = useState('')
  useEffect(() => {
    setExecution(null); setMetrics([]); setReport(null); setError(''); setConnection('polling')
    if (!id) return
    let disposed = false, timer, reportTimer, finished = false, lastSnapshot = 0, lastElapsed = null
    const controller = new AbortController()
    const client = new Client({ brokerURL: websocketUrl(), connectHeaders: { Authorization: `Bearer ${token}` }, reconnectDelay: 3000, heartbeatIncoming: 0, heartbeatOutgoing: 10000 })
    const mergeMetrics = incoming => {
      if (!incoming.length) return
      for (const m of incoming) {
        if (typeof m.elapsedSeconds === 'number' && (lastElapsed === null || m.elapsedSeconds > lastElapsed)) {
          lastElapsed = m.elapsedSeconds
        }
      }
      setMetrics(previous => {
        const byId = new Map(previous.map(m => [m.id, m]))
        for (const m of incoming) byId.set(m.id, m)
        return [...byId.values()].sort((a, b) => a.elapsedSeconds - b.elapsedSeconds)
      })
    }
    const pollReport = async () => {
      if (disposed) return
      try {
        const result = await api(`/executions/${id}/report`, { token, signal: controller.signal })
        if (!disposed) {
          setReport(result)
          if (result?.aiStatus === 'PENDING') {
            reportTimer = setTimeout(pollReport, 2000)
          } else {
            void client.deactivate()
          }
        }
      } catch (e) {
        if (!disposed) {
          setError(e.message)
          reportTimer = setTimeout(pollReport, 2000)
        }
      }
    }
    const handle = async value => {
      if (disposed) return
      if (finished) {
        if (!terminal(value.execution.status)) return
        const snapshotTime = value.metric ? new Date(value.metric.sampleTime).getTime() : new Date(value.execution.endTime || value.execution.startTime || value.execution.createdAt).getTime()
        if (snapshotTime >= lastSnapshot) { lastSnapshot = snapshotTime; setExecution(value.execution) }
        try {
          const result = await api(`/executions/${id}/report`, { token, signal: controller.signal })
          if (!disposed) {
            setReport(result)
            if (result?.aiStatus !== 'PENDING') {
              clearTimeout(reportTimer)
              void client.deactivate()
            }
          }
        } catch (e) { if (!disposed) setError(e.message) }
        return
      }
      const snapshotTime = value.metric ? new Date(value.metric.sampleTime).getTime() : new Date(value.execution.endTime || value.execution.startTime || value.execution.createdAt).getTime()
      if (snapshotTime >= lastSnapshot) { lastSnapshot = snapshotTime; setExecution(value.execution) }
      if (value.metric) mergeMetrics([value.metric])
      if (terminal(value.execution.status)) {
        finished = true; clearTimeout(timer); setConnection('complete')
        try {
          const metricsPath = lastElapsed !== null ? `/executions/${id}/metrics?afterElapsed=${lastElapsed}` : `/executions/${id}/metrics`
          const [history, result] = await Promise.all([api(metricsPath, { token, signal: controller.signal }), api(`/executions/${id}/report`, { token, signal: controller.signal })])
          if (!disposed) {
            mergeMetrics(history)
            setReport(result)
            if (result?.aiStatus === 'PENDING') {
              clearTimeout(reportTimer)
              reportTimer = setTimeout(pollReport, 2000)
            } else {
              void client.deactivate()
            }
          }
        } catch (e) { if (!disposed) setError(e.message) }
      }
    }
    const poll = async () => {
      try {
        const metricsPath = lastElapsed !== null ? `/executions/${id}/metrics?afterElapsed=${lastElapsed}` : `/executions/${id}/metrics`
        const [current, history] = await Promise.all([api(`/executions/${id}`, { token, signal: controller.signal }), api(metricsPath, { token, signal: controller.signal })])
        if (!disposed) { mergeMetrics(history); setError(''); await handle({ execution: current, metric: history.at(-1) || null }) }
      } catch (e) { if (!disposed) setError(e.message) }
      if (!disposed && !finished) timer = setTimeout(poll, 2000)
    }
    client.onConnect = () => {
      if (disposed || finished) return
      setConnection('live')
      client.subscribe(`/user/queue/executions/${id}`, message => { try { void handle(JSON.parse(message.body)) } catch { setError('Số liệu realtime không hợp lệ') } })
    }
    client.onWebSocketClose = () => { if (!disposed && !finished) setConnection('polling') }
    client.onStompError = () => { if (!disposed && !finished) setConnection('polling') }
    client.activate(); void poll()
    return () => { disposed = true; controller.abort(); clearTimeout(timer); clearTimeout(reportTimer); void client.deactivate() }
  }, [id, token])
  return { execution, metrics, report, connection, error }
}
