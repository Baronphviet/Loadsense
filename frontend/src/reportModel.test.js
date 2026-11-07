import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parseAiAnalysis } from './reportModel.js'

test('reads structured findings and preserves actionable evidence', () => {
  const result = parseAiAnalysis(JSON.stringify({ summary: 'Có lỗi', findings: [{ kind: 'observation', title: 'Lỗi tăng', evidence: '2 trên 10 request', meaning: 'Cần xem lại', nextStep: 'Kiểm tra mã lỗi' }], limitations: ['Chưa có log'] }))
  assert.equal(result.findings[0].evidence, '2 trên 10 request')
  assert.equal(result.findings[0].nextStep, 'Kiểm tra mã lỗi')
  assert.deepEqual(result.limitations, ['Chưa có log'])
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
