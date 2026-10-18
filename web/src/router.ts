import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { NetworkError } from '@/api/http'
import { useOfflineStore } from '@/stores/offline'
import { t } from '@/i18n'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
    {
      path: '/',
      component: () => import('@/components/AppLayout.vue'),
      children: [
        { path: '', name: 'shelf', component: () => import('@/views/ShelfView.vue'), meta: { title: t('书架') } },
        { path: 'search', name: 'search', component: () => import('@/views/SearchView.vue'), meta: { title: t('搜索') } },
        { path: 'explore', name: 'explore', component: () => import('@/views/ExploreView.vue'), meta: { title: t('发现') } },
        { path: 'book/:id', name: 'book', component: () => import('@/views/BookView.vue'), meta: { title: t('书籍详情') } },
        { path: 'sources', name: 'sources', component: () => import('@/views/SourcesView.vue'), meta: { title: t('书源') } },
        {
          path: 'sources/edit/:id?',
          name: 'source-edit',
          component: () => import('@/views/SourceEditView.vue'),
          meta: { title: t('编辑书源') },
        },
        { path: 'rules', name: 'rules', component: () => import('@/views/RulesView.vue'), meta: { title: t('规则') } },
        { path: 'replace', redirect: '/rules' },
        { path: 'settings', name: 'settings', component: () => import('@/views/SettingsView.vue'), meta: { title: t('我的') } },
        { path: 'admin', name: 'admin', component: () => import('@/views/AdminView.vue'), meta: { title: t('管理'), admin: true } },
      ],
    },
    { path: '/read/:id', name: 'read', component: () => import('@/views/ReaderView.vue') },
    { path: '/listen/:id', name: 'listen', component: () => import('@/views/AudioView.vue') },
    { path: '/watch/:id', name: 'watch', component: () => import('@/views/VideoView.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.meta.public) return true
  if (!auth.loggedIn) return { name: 'login', query: { redirect: to.fullPath } }
  if (!auth.me) {
    try {
      await auth.fetchMe()
    } catch (e) {
      // 离线且没有缓存的用户信息：仍然放行，页面会显示本地的离线书籍
      if (e instanceof NetworkError) {
        await useOfflineStore().init(null)
        return true
      }
      auth.logout()
      return { name: 'login', query: { redirect: to.fullPath } }
    }
  }
  // 切换到当前用户的离线数据
  await useOfflineStore().init(auth.user?.id)
  if (to.meta.admin && !auth.isAdmin) return { name: 'shelf' }
  return true
})

export default router
