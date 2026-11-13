import { test } from 'node:test'
import assert from 'node:assert/strict'
import { compareExecutions, parseAiAnalysis } from './reportModel.js'

test('reads structured findings and preserves actionable evidence', () => {
  const result = parseAiAnalysis(JSON.stringify({ summary: 'Có lỗi', bottlenecks: ['HTTP 429: 4'], recommendations: ['Giảm RPS'], findings: [{ kind: 'observation', title: 'Lỗi tăng', evidence: '2 trên 10 request', meaning: 'Cần xem lại', nextStep: 'Kiểm tra mã lỗi' }], limitations: ['Chưa có log'], healthScore: 88, healthGrade: 'B', capacitySummary: 'An toàn ở 50 req/s' }))
  assert.equal(result.findings[0].evidence, '2 trên 10 request')
  assert.equal(result.findings[0].nextStep, 'Kiểm tra mã lỗi')
  assert.deepEqual(result.bottlenecks, ['HTTP 429: 4'])
  assert.deepEqual(result.recommendations, ['Giảm RPS'])
  assert.deepEqual(result.limitations, ['Chưa có log'])
  assert.equal(result.healthScore, 88)
  assert.equal(result.healthGrade, 'B')
  assert.equal(result.capacitySummary, 'An toàn ở 50 req/s')
})

test('reads old reports and safely handles invalid stored data', () => {
  const old = parseAiAnalysis('{"summary":"Cũ","bottlenecks":["Lỗi"],"recommendations":["Kiểm tra"]}')
  assert.deepEqual(old.bottlenecks, ['Lỗi'])
  assert.deepEqual(old.findings, [])
  assert.equal(parseAiAnalysis('{oops'), null)
})

test('distinguishes a deterministic summary from AI analysis', () => {
  const result = parseAiAnalysis('{"summary":"Chưa đủ mẫu","analysisMode":"insufficient_data"}')
  assert.equal(result.analysisMode, 'insufficient_data')
})

test('compares baseline and current execution metrics cleanly', () => {
  const delta = compareExecutions(
    { throughput: 100, p95LatencyMs: 200, p99LatencyMs: 300, errorRate: 5, totalRequests: 1000 },
    { throughput: 120, p95LatencyMs: 150, p99LatencyMs: 250, errorRate: 1, totalRequests: 1200 },
  )
  assert.equal(delta.throughputDelta, 20)
  assert.equal(delta.throughputPct, 20)
  assert.equal(delta.p95DeltaMs, -50)
  assert.equal(delta.p99DeltaMs, -50)
  assert.equal(delta.errorRateDelta, -4)
  assert.equal(delta.requestsDelta, 200)
})
