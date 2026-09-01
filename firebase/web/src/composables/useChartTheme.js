// Shared Chart.js defaults so every chart in the Performance view reads as one system, matching
// the dashboard's dark "trading terminal" token palette (assets/main.css) rather than Chart.js's
// stock light-mode defaults.
import {
  Chart as ChartJS,
  CategoryScale,
  LinearScale,
  TimeScale,
  PointElement,
  LineElement,
  BarElement,
  Title,
  Tooltip,
  Legend,
  Filler,
} from 'chart.js'
// Registers the 'time' scale's date adapter (Chart.js's TimeScale needs one to format/parse axis
// ticks) - side-effecting import, no named exports used directly.
import 'chartjs-adapter-date-fns'

ChartJS.register(CategoryScale, LinearScale, TimeScale, PointElement, LineElement, BarElement, Title, Tooltip, Legend, Filler)

const TEXT_DIM = '#9198ac'
const BORDER = '#262b3a'

ChartJS.defaults.color = TEXT_DIM
ChartJS.defaults.borderColor = BORDER
ChartJS.defaults.font.family =
  "Inter, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif"
ChartJS.defaults.plugins.legend.labels.usePointStyle = true
ChartJS.defaults.plugins.tooltip.backgroundColor = '#181c26'
ChartJS.defaults.plugins.tooltip.borderColor = '#343b4f'
ChartJS.defaults.plugins.tooltip.borderWidth = 1
ChartJS.defaults.plugins.tooltip.padding = 10
ChartJS.defaults.plugins.tooltip.titleColor = '#e7e9f0'
ChartJS.defaults.plugins.tooltip.bodyColor = '#e7e9f0'
ChartJS.defaults.plugins.tooltip.cornerRadius = 8

export const CHART_COLORS = {
  accent: '#e0a836',
  profit: '#3ecf8e',
  loss: '#f2685b',
  info: '#5b9df2',
  grid: BORDER,
  text: TEXT_DIM,
}

export { ChartJS }
