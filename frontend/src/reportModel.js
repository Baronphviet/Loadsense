export function parseAiAnalysis(value) {
  try {
    const data = typeof value === 'string' ? JSON.parse(value) : value
    if (!data || typeof data !== 'object' || Array.isArray(data)) return null
    return {
      summary: typeof data.summary === 'string' ? data.summary : '',
      analysisMode: data.analysisMode === 'insufficient_data' ? 'insufficient_data' : 'model',
      findings: Array.isArray(data.findings) ? data.findings.filter(item => item && typeof item === 'object') : [],
      limitations: Array.isArray(data.limitations) ? data.limitations.filter(item => typeof item === 'string') : [],
      bottlenecks: Array.isArray(data.bottlenecks) ? data.bottlenecks.filter(item => typeof item === 'string') : [],
      recommendations: Array.isArray(data.recommendations) ? data.recommendations.filter(item => typeof item === 'string') : [],
      healthScore: typeof data.healthScore === 'number' ? data.healthScore : null,
      healthGrade: typeof data.healthGrade === 'string' ? data.healthGrade : null,
      capacitySummary: typeof data.capacitySummary === 'string' ? data.capacitySummary : '',
    }
  } catch { return null }
}

export function compareExecutions(baseline, current) {
  if (!baseline || !current) return null
  const diff = (a, b) => (Number(b) || 0) - (Number(a) || 0)
  const pct = (a, b) => {
    const base = Number(a) || 0
    if (base === 0) return null
    return ((Number(b) || 0) - base) / base * 100
  }
  return {
    throughputDelta: diff(baseline.throughput, current.throughput),
    throughputPct: pct(baseline.throughput, current.throughput),
    p95DeltaMs: diff(baseline.p95LatencyMs, current.p95LatencyMs),
    p95Pct: pct(baseline.p95LatencyMs, current.p95LatencyMs),
    p99DeltaMs: diff(baseline.p99LatencyMs, current.p99LatencyMs),
    errorRateDelta: diff(baseline.errorRate, current.errorRate),
    requestsDelta: diff(baseline.totalRequests, current.totalRequests),
  }
}

export const loadProfileLabels = {
  CONSTANT: 'Tải cố định',
  RAMP_UP: 'Tăng dần (Ramp-up)',
  STEP_STRESS: 'Tăng theo bậc (Stress)',
  SPIKE: 'Đột biến (Spike)',
}
