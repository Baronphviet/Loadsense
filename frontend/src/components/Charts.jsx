import { Chart as ChartJS, CategoryScale, LinearScale, PointElement, LineElement, Tooltip, Legend, Filler } from 'chart.js'
import { Line } from 'react-chartjs-2'
ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, Tooltip, Legend, Filler)

export default function Charts({ metrics }) {
  const options = (unit, max) => ({
    responsive: true, maintainAspectRatio: false, animation: false,
    interaction: { mode: 'index', intersect: false },
    plugins: { legend: { position: 'bottom', labels: { usePointStyle: true, boxWidth: 7, padding: 20 } } },
    scales: {
      x: { grid: { display: false }, ticks: { maxTicksLimit: 7, color: '#87939f' }, title: { display: true, text: 'Thời gian (giây)' } },
      y: { beginAtZero: true, ...(max ? { suggestedMax: max } : {}), grid: { color: '#edf1f4' }, title: { display: true, text: unit } },
    },
  })
  const line = (label, key, color, fill = false) => ({ label, data: metrics.map(m => m[key]), borderColor: color, backgroundColor: `${color}15`, borderWidth: 2, pointRadius: metrics.length < 2 ? 3 : 0, pointHitRadius: 10, tension: 0.2, fill })
  const labels = metrics.map(m => m.elapsedSeconds.toFixed(1))
  return <div className="chart-grid">
    <section className="panel"><div className="panel-heading"><div><h3>Độ trễ phản hồi</h3><p>Phân vị tích lũy · milliseconds</p></div><span className="tag">LATENCY</span></div><div className="chart-area">{metrics.length ? <Line options={options('ms')} data={{ labels, datasets: [line('p50', 'p50LatencyMs', '#0f9f87', true), line('p95', 'p95LatencyMs', '#6366f1')] }} /> : <ChartEmpty />}</div></section>
    <section className="panel"><div className="panel-heading"><div><h3>Lưu lượng & lỗi</h3><p>Request/giây theo khoảng đo · % lỗi tích lũy</p></div><span className="tag">TRAFFIC</span></div><div className="chart-area">{metrics.length ? <Line options={{ ...options('req/s'), scales: { ...options('req/s').scales, error: { position: 'right', min: 0, max: 100, grid: { drawOnChartArea: false }, title: { display: true, text: '% lỗi' } } } }} data={{ labels, datasets: [line('Throughput', 'throughput', '#0f9f87', true), { ...line('Error rate', 'errorRate', '#f2994a'), yAxisID: 'error' }] }} /> : <ChartEmpty />}</div></section>
  </div>
}
function ChartEmpty() { return <div className="chart-empty"><span>⌁</span><p>Biểu đồ xuất hiện khi có số liệu đo</p><small>Mỗi điểm là một mẫu tổng hợp từ engine</small></div> }
