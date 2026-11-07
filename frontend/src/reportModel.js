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
    }
  } catch { return null }
}
