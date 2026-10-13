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
    let disposed = false, timer, finished = false, lastSnapshot = 0
    const controller = new AbortController()
    const client = new Client({ brokerURL: websocketUrl(), connectHeaders: { Authorization: `Bearer ${token}` }, reconnectDelay: 3000, heartbeatIncoming: 0, heartbeatOutgoing: 10000 })
    const mergeMetrics = incoming => setMetrics(previous => {
      const byId = new Map(previous.map(m => [m.id, m]))
      for (const m of incoming) byId.set(m.id, m)
      return [...byId.values()].sort((a, b) => a.elapsedSeconds - b.elapsedSeconds)
    })
    const handle = async value => {
      if (disposed || (finished && !terminal(value.execution.status))) return
      const snapshotTime = value.metric ? new Date(value.metric.sampleTime).getTime() : new Date(value.execution.endTime || value.execution.startTime || value.execution.createdAt).getTime()
      if (snapshotTime >= lastSnapshot) { lastSnapshot = snapshotTime; setExecution(value.execution) }
      if (value.metric) mergeMetrics([value.metric])
      if (terminal(value.execution.status)) {
        finished = true; clearTimeout(timer); void client.deactivate(); setConnection('complete')
        try {
          const [history, result] = await Promise.all([api(`/executions/${id}/metrics`, { token, signal: controller.signal }), api(`/executions/${id}/report`, { token, signal: controller.signal })])
          if (!disposed) { mergeMetrics(history); setReport(result) }
        } catch (e) { if (!disposed) setError(e.message) }
      }
    }
    const poll = async () => {
      try {
        const [current, history] = await Promise.all([api(`/executions/${id}`, { token, signal: controller.signal }), api(`/executions/${id}/metrics`, { token, signal: controller.signal })])
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
    return () => { disposed = true; controller.abort(); clearTimeout(timer); void client.deactivate() }
  }, [id, token])
  return { execution, metrics, report, connection, error }
}
