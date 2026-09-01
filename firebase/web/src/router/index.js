import { createRouter, createWebHistory } from 'vue-router'
import DashboardView from '@/views/DashboardView.vue'
import HistoryView from '@/views/HistoryView.vue'
import PerformanceView from '@/views/PerformanceView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'dashboard', component: DashboardView, meta: { title: 'Live Dashboard' } },
    { path: '/history', name: 'history', component: HistoryView, meta: { title: 'Trade History' } },
    { path: '/performance', name: 'performance', component: PerformanceView, meta: { title: 'Performance' } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

export default router
