<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { api } from './api.js'
import {
  CATEGORIES, DAYS, GENDERS, GRADES, DELETE_CONFIRM_WORD, TIME_OPTIONS,
  invitationStatus, needStatus, formatTime, splitTags, toTime, toMinute
} from './format.js'

const PAGE_TITLES = { plaza: '需求广场', recommend: '发布与匹配', invitations: '我的邀请', messages: '站内信', profile: '个人资料', admin: '管理端' }
const PAGE_HINTS = {
  plaza: '看看大家正在寻找怎样的搭子。',
  recommend: '发布需求，再根据共同时间、兴趣与技能找到候选。',
  invitations: '每一次连接，都由双方共同决定。',
  messages: '查看站内信记录。',
  profile: '介绍自己，让合适的人更容易找到你。',
  admin: '查看用户、需求与邀请，并处理需要管理的内容。'
}

// ---------- 全局状态 ----------
const user = ref(null)
const page = ref('auth') // 未登录时先显示登录/注册页；页面上的“先随便看看”可免注册浏览广场
const busy = ref(false)
const online = ref(typeof navigator === 'undefined' ? true : navigator.onLine)
const toast = ref(null) // { text, kind, undo }
let toastTimer

function notify(text, kind = 'success', undo = null) {
  clearTimeout(toastTimer)
  toast.value = { text, kind, undo }
  toastTimer = setTimeout(() => { toast.value = null }, undo ? 8000 : 5000)
}

/** 包装用户操作：防止重复提交，统一提示错误。 */
async function run(task) {
  if (busy.value) return
  busy.value = true
  try {
    await task()
  } catch (e) {
    notify(e.message, 'error')
    if (e.status === 401 && user.value) { user.value = null; page.value = 'auth' }
  } finally {
    busy.value = false
  }
}

// ---------- 无障碍偏好（公约第七条：视障 / 老年用户） ----------
const prefs = ref({ large: false, contrast: false })
function loadPrefs() {
  try {
    const saved = JSON.parse(localStorage.getItem('buddy_prefs') || '{}')
    prefs.value = { large: !!saved.large, contrast: !!saved.contrast }
  } catch { /* 浏览器禁用存储时使用默认值 */ }
}
watch(prefs, value => {
  try { localStorage.setItem('buddy_prefs', JSON.stringify(value)) } catch { /* 忽略 */ }
}, { deep: true })

// ---------- 登录 / 注册 / 找回密码 ----------
const mode = ref('login')
const credentials = ref({ account: '', password: '' })
const agreePrivacy = ref(false)
const confirmPassword = ref('')
const visible = ref({}) // 各密码框的“小眼睛”显示状态
const privacy = ref(null)
const showPrivacy = ref(false)
const emptyResetForm = () => ({ account: '', answers: ['', '', ''], new_password: '' })
const resetForm = ref(emptyResetForm())
const resetQuestions = ref([])

async function openPrivacy() {
  showPrivacy.value = true
  if (!privacy.value) {
    try { privacy.value = await api('/privacy') } catch (e) { notify(e.message, 'error') }
  }
}

function submitAuth() {
  return run(async () => {
    if (mode.value === 'register') {
      if (credentials.value.password !== confirmPassword.value) throw new Error('两次输入的密码不一致')
      if (!agreePrivacy.value) throw new Error('请先阅读并勾选同意《个人信息使用说明》')
      const r = await api('/register', 'POST', { email: credentials.value.account, password: credentials.value.password, agree_privacy: true })
      mode.value = 'login'
      credentials.value.password = ''
      confirmPassword.value = ''
      visible.value = {}
      notify(`注册成功，你的账号 ID 是 ${r.account_id}，请登录`)
    } else {
      await api('/login', 'POST', credentials.value)
      credentials.value.password = ''
      page.value = 'plaza'
      await refresh()
      notify('登录成功')
    }
  })
}

function loadResetQuestions() {
  return run(async () => {
    resetQuestions.value = (await api('/security-questions?account=' + encodeURIComponent(resetForm.value.account))).questions
    resetForm.value.answers = ['', '', '']
  })
}

function resetPassword() {
  return run(async () => {
    await api('/reset-password', 'POST', resetForm.value)
    resetForm.value = emptyResetForm()
    resetQuestions.value = []
    mode.value = 'login'
    notify('密码已重置，请用新密码登录')
  })
}

async function refresh() {
  try {
    const r = await api('/me')
    user.value = r.user
    profile.value = {
      ...r.user,
      personality: r.user.personality.join('，'),
      interests: r.user.interests.join('，'),
      skills: r.user.skills.join('，'),
      wants: r.user.wants.join('，')
    }
    if (page.value === 'auth' || (page.value === 'admin' && !r.user.admin)) page.value = 'plaza'
    if (r.user.admin && ['recommend', 'invitations', 'profile'].includes(page.value)) page.value = 'admin'
  } catch {
    user.value = null // 未登录或会话过期：以访客身份浏览
  }
  await go(page.value)
}

function clearSession() {
  user.value = null
  adminData.value = { users: [], needs: [], invitations: [] }
  invitations.value = []
  myNeeds.value = []
  matches.value = []
  contacts.value = {}
  mode.value = 'login'
  go('auth')
}

async function logout() {
  try { await api('/logout', 'POST') } catch { /* 无论服务端是否成功，本地都退出 */ }
  clearSession()
}

// ---------- 页面切换 ----------
const navItems = computed(() => {
  if (!user.value) return [['plaza', '需求广场']]
  const items = [['plaza', '需求广场']]
  if (!user.value.admin) items.push(['recommend', '发布与匹配'], ['invitations', '我的邀请'])
  items.push(['messages', '站内信'])
  if (!user.value.admin) items.push(['profile', '个人资料'])
  if (user.value.admin) items.push(['admin', '管理端'])
  return items
})

async function go(target) {
  if (!user.value && target !== 'plaza' && target !== 'auth') target = 'auth'
  page.value = target
  try {
    if (target === 'plaza') {
      await loadNeeds()
      trending.value = (await api('/trending')).topics
    }
    if (target === 'recommend') {
      myNeeds.value = (await api('/my-needs')).needs
      if (!myNeeds.value.some(n => String(n.id) === selectedNeed.value && n.status === 'open')) {
        selectedNeed.value = String(myNeeds.value.find(n => n.status === 'open')?.id || '')
      }
      await loadMatches()
    }
    if (target === 'invitations') invitations.value = (await api('/invitations')).invitations
    if (target === 'messages') messages.value = (await api('/messages')).messages
    if (target === 'admin') adminData.value = await api('/admin/overview')
  } catch (e) {
    notify(e.message, 'error')
  }
}

function needLogin(text = '登录后才能发送邀请') {
  notify(text, 'error')
  mode.value = 'login'
  go('auth')
}

// ---------- 需求广场（免注册可浏览，公约第九条） ----------
const needs = ref([])
const hasMore = ref(false)
const trending = ref([])
const query = ref('')
const categoryFilter = ref('')
const detail = ref(null)

async function loadNeeds(append = false) {
  const params = new URLSearchParams({ q: query.value.trim(), category: categoryFilter.value, offset: String(append ? needs.value.length : 0) })
  const r = await api('/needs?' + params)
  if (append) {
    const known = new Set(needs.value.map(n => n.id))
    needs.value = [...needs.value, ...r.needs.filter(n => !known.has(n.id))]
  } else {
    needs.value = r.needs
  }
  hasMore.value = r.has_more
}

let searchTimer
watch([query, categoryFilter], () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => loadNeeds().catch(e => notify(e.message, 'error')), 300)
})

function loadMore() {
  return run(() => loadNeeds(true))
}

function openDetail(n) {
  return run(async () => {
    detail.value = (await api('/needs/' + n.id)).need
    const item = needs.value.find(x => x.id === n.id)
    if (item) item.views = detail.value.views
  })
}

// ---------- 发布与匹配 ----------
const emptyNeed = () => ({ category: '学习', title: '', description: '', tags: '', day: '周六', start: '14:00', duration: '120', end: '16:00' })
const newNeed = ref(emptyNeed())
const myNeeds = ref([])
const matches = ref([])
const selectedNeed = ref('')
const openNeeds = computed(() => myNeeds.value.filter(n => n.status === 'open'))

async function loadMatches() {
  matches.value = selectedNeed.value ? (await api('/recommend/' + selectedNeed.value)).matches : []
}

function changeSelectedNeed() {
  return run(loadMatches)
}

function publish() {
  return run(async () => {
    const n = newNeed.value
    const start = toMinute(n.start)
    const endMinute = n.duration === 'custom' ? toMinute(n.end) : start + Number(n.duration)
    if (endMinute <= start) throw new Error('结束时间需要晚于开始时间')
    if (endMinute > 23 * 60 + 59) throw new Error('结束时间不能超过当天 24:00，请提前开始时间或缩短时长')
    await api('/needs', 'POST', {
      category: n.category, title: n.title, description: n.description, tags: splitTags(n.tags),
      slots: [{ day: n.day, start: n.start, end: toTime(endMinute) }]
    })
    newNeed.value = emptyNeed()
    await go('recommend')
    notify('需求已发布，可以在下方查看匹配推荐')
  })
}

// 关闭可恢复 → 提供“撤销”；删除不可恢复 → 二次确认（公约第五条）
function toggleNeed(n) {
  return run(async () => {
    const next = n.status === 'open' ? 'closed' : 'open'
    await api('/needs/' + n.id, 'PATCH', { status: next })
    await go('recommend')
    if (next === 'closed') notify(`已关闭「${n.title}」`, 'success', () => reopenNeed(n.id))
    else notify(`已重新开放「${n.title}」`)
  })
}

function reopenNeed(id) {
  toast.value = null
  return run(async () => {
    await api('/needs/' + id, 'PATCH', { status: 'open' })
    await go(page.value)
    notify('已撤销关闭')
  })
}

function deleteNeed(n) {
  if (!window.confirm(`确定删除「${n.title}」吗？\n删除后无法恢复，待处理的邀请会被撤回。`)) return
  return run(async () => {
    await api('/needs/' + n.id, 'DELETE')
    await go('recommend')
    notify('需求已删除')
  })
}

// ---------- 邀请 ----------
const invitations = ref([])
const contacts = ref({})
const person = ref(null)
const incoming = computed(() => invitations.value.filter(i => i.receiver_id === user.value?.id && i.status !== 'accepted'))
const outgoing = computed(() => invitations.value.filter(i => i.sender_id === user.value?.id && i.status !== 'accepted'))
const accepted = computed(() => invitations.value.filter(i => i.status === 'accepted'))

function sendInvite(needId, receiverId) {
  if (!user.value) return needLogin()
  return run(async () => {
    await api('/invitations', 'POST', { need_id: needId, receiver_id: receiverId })
    notify('邀请已发出，对方同意后可在“我的邀请”查看联系方式')
  })
}

// 拒绝：先展开填写原因的小表单（同时起到二次确认作用），再提交
const rejecting = ref(null)
const rejectReason = ref('')
const REJECT_PRESETS = ['时间对不上，下次再约', '已经找到搭子了，谢谢', '这周比较忙，暂时不参加']

function startReject(i) {
  rejecting.value = i.id
  rejectReason.value = ''
}

function handleInvitation(i, type) {
  if (type === 'withdrawn' && !window.confirm(`确定撤回发给 ${i.receiver_name} 的邀请吗？`)) return
  return run(async () => {
    await api('/invitations/' + i.id, 'PATCH', type === 'rejected' ? { action: type, reason: rejectReason.value.trim() } : { action: type })
    rejecting.value = null
    await go('invitations')
    notify({ accepted: '已同意，现在可以查看彼此联系方式', rejected: '已拒绝该邀请', withdrawn: '邀请已撤回' }[type])
  })
}

function toggleContacts(i) {
  if (contacts.value[i.id]) {
    const next = { ...contacts.value }
    delete next[i.id]
    contacts.value = next
    return
  }
  return run(async () => {
    contacts.value = { ...contacts.value, [i.id]: (await api('/contacts/' + i.id)).contacts }
  })
}

function showPerson(id) {
  if (!user.value) return needLogin('登录后才能查看个人资料')
  return run(async () => { person.value = (await api('/users/' + id)).user })
}

// ---------- 个人资料与账号 ----------
const profile = ref({})
const passwords = ref({ current_password: '', new_password: '' })
const emptySecurityForm = () => ({ password: '', questions: [{ question: '', answer: '' }, { question: '', answer: '' }, { question: '', answer: '' }] })
const securityForm = ref(emptySecurityForm())
const deleteForm = ref({ password: '', confirm: '' })

function saveProfile() {
  return run(async () => {
    const p = { ...profile.value }
    for (const k of ['personality', 'interests', 'skills', 'wants']) p[k] = splitTags(p[k])
    const r = await api('/me', 'PATCH', p)
    await refresh()
    notify(r.has_contact ? '资料已保存' : '资料已保存。提示：填写至少一种联系方式后才能发出或接受邀请')
  })
}

function changePassword() {
  return run(async () => {
    await api('/password', 'PATCH', passwords.value)
    passwords.value = { current_password: '', new_password: '' }
    notify('密码已修改，其他设备需要重新登录')
  })
}

function saveSecurityQuestions() {
  return run(async () => {
    await api('/security-questions', 'POST', securityForm.value)
    securityForm.value = emptySecurityForm()
    notify('密保问题已保存')
  })
}

function deleteAccount() {
  if (deleteForm.value.confirm !== DELETE_CONFIRM_WORD) return notify(`请在确认框中输入“${DELETE_CONFIRM_WORD}”`, 'error')
  if (!window.confirm('注销后，你的个人资料、联系方式和发布的需求将被清除且无法恢复。确定继续吗？')) return
  return run(async () => {
    await api('/me', 'DELETE', deleteForm.value)
    deleteForm.value = { password: '', confirm: '' }
    clearSession()
    notify('账号已注销，个人资料已清除')
  })
}

// ---------- 站内信与管理端 ----------
const messages = ref([])
const adminData = ref({ users: [], needs: [], invitations: [] })
const adminSearch = ref('')
const adminDrafts = ref({})
const filteredAdminUsers = computed(() => adminData.value.users.filter(u =>
  [u.account_id, u.name].some(x => String(x || '').toLowerCase().includes(adminSearch.value.toLowerCase()))))

function adminUserAction(u) {
  if (!u.disabled && !window.confirm(`确定停用「${u.name}」吗？该用户会被立即退出登录，之后可以恢复。`)) return
  return run(async () => {
    await api('/admin/users/' + u.id, 'PATCH', { action: u.disabled ? 'enable' : 'disable' })
    await go('admin')
    notify(u.disabled ? '账号已恢复' : '账号已停用')
  })
}

function adminRemoveNeed(n) {
  if (!window.confirm(`确定撤下「${n.title}」吗？撤下后作者不能自行重新开放。`)) return
  return run(async () => {
    await api('/admin/needs/' + n.id, 'PATCH', { action: 'remove' })
    await go('admin')
    notify('帖子已撤下')
  })
}

function adminSendMessage(n) {
  return run(async () => {
    await api('/admin/messages', 'POST', { need_id: n.id, body: adminDrafts.value[n.id] })
    adminDrafts.value[n.id] = ''
    notify('私信已发送给发布者')
  })
}

// ---------- 生命周期 ----------
const updateOnline = () => { online.value = navigator.onLine }
const closeOnEscape = e => {
  if (e.key === 'Escape') { detail.value = null; person.value = null; showPrivacy.value = false }
}
onMounted(() => {
  loadPrefs()
  window.addEventListener('online', updateOnline)
  window.addEventListener('offline', updateOnline)
  window.addEventListener('keydown', closeOnEscape)
  refresh()
})
onBeforeUnmount(() => {
  window.removeEventListener('online', updateOnline)
  window.removeEventListener('offline', updateOnline)
  window.removeEventListener('keydown', closeOnEscape)
})
</script>

<template>
<div class="shell" :class="{ 'large-text': prefs.large, 'high-contrast': prefs.contrast }">
  <a class="skip-link" href="#main">跳到主要内容</a>

  <header>
    <div class="brand">
      <span class="brand-icon" aria-hidden="true">✦</span>
      <div><strong>校园搭子</strong><small>找到同频的人，一起出发</small></div>
    </div>
    <nav v-if="user || page !== 'auth'" aria-label="主导航">
      <button v-for="x in navItems" :key="x[0]" :class="{ active: page === x[0] }" :aria-current="page === x[0] ? 'page' : null" @click="go(x[0])">{{ x[1] }}</button>
    </nav>
    <div class="a11y-toggles" role="group" aria-label="显示设置">
      <button type="button" :aria-pressed="prefs.large" @click="prefs.large = !prefs.large">大字</button>
      <button type="button" :aria-pressed="prefs.contrast" @click="prefs.contrast = !prefs.contrast">高对比</button>
    </div>
    <div v-if="user" class="header-user">{{ user.name }} <button @click="logout">退出</button></div>
    <div v-else-if="page !== 'auth'" class="header-user"><button class="primary" @click="mode = 'login'; go('auth')">登录 / 注册</button></div>
  </header>

  <div v-if="!online" class="offline-banner" role="alert">网络已断开，页面内容可能不是最新的。恢复网络后操作会自动可用。</div>

  <!-- 登录 / 注册 / 找回密码 -->
  <main v-if="!user && page === 'auth'" id="main" class="auth-wrap">
    <section class="hero">
      <span class="eyebrow">CAMPUS CONNECTION</span>
      <h1>下一次同行，<br>从这里开始。</h1>
      <p>找学习、运动、兴趣与技能互助搭子。只有双方同意后，才能查看彼此联系方式。</p>
      <div class="hero-pills"><span>✎ 一起学习</span><span>◉ 一起运动</span><span>✦ 交换技能</span></div>
      <button type="button" class="link hero-browse" @click="go('plaza')">不想注册？先随便看看需求广场 →</button>
    </section>

    <form v-if="mode !== 'reset'" class="auth-card" @submit.prevent="submitAuth">
      <h2>{{ mode === 'login' ? '欢迎回来' : '创建账号' }}</h2>
      <p>{{ mode === 'login' ? '使用邮箱或账号 ID 登录。' : '用邮箱注册，无需验证码，完全免费。' }}</p>
      <div class="tabs" role="tablist">
        <button type="button" role="tab" :aria-selected="mode === 'login'" :class="{ active: mode === 'login' }" @click="mode = 'login'">登录</button>
        <button type="button" role="tab" :aria-selected="mode === 'register'" :class="{ active: mode === 'register' }" @click="mode = 'register'">注册</button>
      </div>
      <label>{{ mode === 'login' ? '邮箱或账号 ID' : '邮箱' }}
        <input v-model="credentials.account" :type="mode === 'register' ? 'email' : 'text'" :autocomplete="mode === 'register' ? 'email' : 'username'" required>
      </label>
      <label>{{ mode === 'login' ? '密码' : '密码（8–20 位）' }}
        <span class="pwd-field">
          <input v-model="credentials.password" :type="visible.auth ? 'text' : 'password'" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" :minlength="mode === 'register' ? 8 : null" :maxlength="mode === 'register' ? 20 : null" required>
          <button type="button" class="eye" :aria-label="visible.auth ? '隐藏密码' : '显示密码'" :aria-pressed="!!visible.auth" @click="visible.auth = !visible.auth"><svg v-if="!visible.auth" viewBox="0 0 24 24" aria-hidden="true"><path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7S1 12 1 12z"/><circle cx="12" cy="12" r="3"/></svg><svg v-else viewBox="0 0 24 24" aria-hidden="true"><path d="M17.9 17.9A10.8 10.8 0 0 1 12 19c-7 0-11-7-11-7a19.8 19.8 0 0 1 5.1-5.9M9.9 4.2A10 10 0 0 1 12 4c7 0 11 7 11 7a19.9 19.9 0 0 1-2.2 3.2M1 1l22 22"/><path d="M14.1 14.1a3 3 0 0 1-4.2-4.2"/></svg></button>
        </span>
      </label>
      <label v-if="mode === 'register'">再次输入密码
        <input v-model="confirmPassword" type="password" autocomplete="new-password" maxlength="20" required>
        <small v-if="confirmPassword && confirmPassword !== credentials.password" class="field-error" role="alert">两次输入的密码不一致</small>
      </label>
      <div v-if="mode === 'register'" class="consent">
        <label class="checkbox"><input v-model="agreePrivacy" type="checkbox"> 我已阅读并同意</label>
        <button type="button" class="link" @click="openPrivacy">《个人信息使用说明》</button>
      </div>
      <button class="primary full" type="submit" :disabled="busy">{{ busy ? '请稍候…' : (mode === 'login' ? '登录' : '注册') }}</button>
      <button v-if="mode === 'login'" type="button" class="link" @click="mode = 'reset'">忘记密码？</button>
    </form>

    <form v-else class="auth-card" @submit.prevent="resetPassword">
      <h2>重置密码</h2>
      <p>先输入账号，回答预先设置的 3 个密保问题；答对其中 2 个即可重置。连续答错 5 次会锁定 15 分钟。</p>
      <label>邮箱或账号 ID<input v-model="resetForm.account" required></label>
      <button type="button" :disabled="busy || !resetForm.account.trim()" @click="loadResetQuestions">查看密保问题</button>
      <template v-if="resetQuestions.length === 3">
        <label v-for="(q, index) in resetQuestions" :key="q.question_no">{{ q.question }}<input v-model="resetForm.answers[index]" autocomplete="off"></label>
        <label>新密码（8–20 位）<span class="pwd-field"><input v-model="resetForm.new_password" :type="visible.reset ? 'text' : 'password'" minlength="8" maxlength="20" autocomplete="new-password" required><button type="button" class="eye" :aria-label="visible.reset ? '隐藏密码' : '显示密码'" :aria-pressed="!!visible.reset" @click="visible.reset = !visible.reset"><svg v-if="!visible.reset" viewBox="0 0 24 24" aria-hidden="true"><path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7S1 12 1 12z"/><circle cx="12" cy="12" r="3"/></svg><svg v-else viewBox="0 0 24 24" aria-hidden="true"><path d="M17.9 17.9A10.8 10.8 0 0 1 12 19c-7 0-11-7-11-7a19.8 19.8 0 0 1 5.1-5.9M9.9 4.2A10 10 0 0 1 12 4c7 0 11 7 11 7a19.9 19.9 0 0 1-2.2 3.2M1 1l22 22"/><path d="M14.1 14.1a3 3 0 0 1-4.2-4.2"/></svg></button></span></label>
        <button class="primary full" type="submit" :disabled="busy">重置密码</button>
      </template>
      <button type="button" class="link" @click="mode = 'login'">返回登录</button>
    </form>
  </main>

  <main v-else id="main" class="main">
    <div class="page-heading">
      <span class="eyebrow">CAMPUS BUDDY</span>
      <h1>{{ PAGE_TITLES[page] }}</h1>
      <p>{{ PAGE_HINTS[page] }}</p>
    </div>

    <!-- 需求广场 -->
    <div v-if="page === 'plaza'">
      <div v-if="!user" class="guest-banner">
        你正在以访客身份浏览。<button class="link" @click="mode = 'register'; go('auth')">免费注册</button>后可以发布需求、查看资料和发送邀请。
      </div>
      <section class="trending" aria-labelledby="trending-title">
        <h2 id="trending-title">热门搭子主题</h2>
        <div v-if="trending.length" class="trend-list">
          <button v-for="t in trending" :key="t.topic" @click="categoryFilter = ''; query = t.topic">
            <strong>{{ t.topic }}搭子</strong><span>{{ t.posts }} 条需求 · {{ t.views_7d }} 次浏览</span>
          </button>
        </div>
        <p v-else class="empty">最近还没有形成热门主题。</p>
      </section>
      <div class="toolbar">
        <input v-model="query" type="search" maxlength="40" placeholder="搜索需求关键词" aria-label="搜索需求关键词">
        <select v-model="categoryFilter" aria-label="按类型筛选">
          <option value="">全部类型</option>
          <option v-for="c in CATEGORIES" :key="c">{{ c }}</option>
        </select>
        <button v-if="query || categoryFilter" type="button" @click="query = ''; categoryFilter = ''">清除筛选</button>
        <span aria-live="polite">已显示 {{ needs.length }} 条{{ hasMore ? '，还有更多' : '' }}</span>
      </div>
      <div class="grid">
        <article v-for="n in needs" :key="n.id" class="card">
          <div class="card-top"><span class="badge">{{ n.category }}</span><small>{{ n.owner_name }} · {{ n.views || 0 }} 次浏览</small></div>
          <h3>{{ n.title }}</h3>
          <p>{{ n.description || '期待找到合适的搭子，一起开始。' }}</p>
          <div class="tags">
            <span v-for="t in n.tags" :key="t">{{ t }}</span>
            <span v-for="s in n.slots" :key="s.day + s.start">{{ s.day }} {{ s.start }}–{{ s.end }}</span>
          </div>
          <div class="actions">
            <button @click="openDetail(n)">查看详情</button>
            <template v-if="user && n.user_id !== user.id">
              <button @click="showPerson(n.user_id)">查看资料</button>
              <button v-if="!user.admin" class="primary" :disabled="busy" @click="sendInvite(n.id, n.user_id)">发送邀请</button>
            </template>
            <button v-else-if="!user" class="primary" @click="needLogin()">登录后邀请</button>
          </div>
        </article>
      </div>
      <div v-if="!needs.length" class="empty">暂无相关需求{{ user ? '，发布一条试试。' : '。' }}</div>
      <div v-if="hasMore" class="load-more"><button :disabled="busy" @click="loadMore">{{ busy ? '加载中…' : '加载更多' }}</button></div>
    </div>

    <!-- 发布与匹配 -->
    <div v-if="page === 'recommend'">
      <div class="panel">
        <h2>发布新需求</h2>
        <form class="need-form" @submit.prevent="publish">
          <div class="form-grid">
            <label>类型<select v-model="newNeed.category"><option v-for="c in CATEGORIES" :key="c">{{ c }}</option></select></label>
            <label>标题（3–60 字）<input v-model="newNeed.title" minlength="3" maxlength="60" required placeholder="例如 周六图书馆自习搭子"></label>
            <label class="span2">说明（选填，最多 500 字）<textarea v-model="newNeed.description" maxlength="500" rows="2"></textarea></label>
            <label class="span2">标签（用逗号分隔，选填）<input v-model="newNeed.tags" placeholder="例如 羽毛球，Python"></label>
            <label>星期<select v-model="newNeed.day"><option v-for="d in DAYS" :key="d">{{ d }}</option></select></label>
            <label>开始时间<select v-model="newNeed.start"><option v-for="t in TIME_OPTIONS" :key="t">{{ t }}</option></select></label>
            <label>时长
              <select v-model="newNeed.duration">
                <option value="60">1 小时</option><option value="120">2 小时</option><option value="180">3 小时</option><option value="custom">自选结束时间</option>
              </select>
            </label>
            <label v-if="newNeed.duration === 'custom'">结束时间<select v-model="newNeed.end"><option v-for="t in TIME_OPTIONS" :key="t">{{ t }}</option></select></label>
          </div>
          <button class="primary" type="submit" :disabled="busy">发布需求</button>
        </form>
      </div>

      <div class="panel">
        <h2>我的需求</h2>
        <div v-for="n in myNeeds" :key="n.id" class="list-row">
          <div><strong>{{ n.title }}</strong><small>{{ n.category }} · {{ needStatus(n.status) }} · {{ n.views || 0 }} 次浏览</small></div>
          <div class="actions">
            <button v-if="n.status !== 'removed'" :disabled="busy" @click="toggleNeed(n)">{{ n.status === 'open' ? '关闭' : '重新开放' }}</button>
            <button class="danger" :disabled="busy" @click="deleteNeed(n)">删除</button>
          </div>
        </div>
        <div v-if="!myNeeds.length" class="empty">还没有发布需求。</div>
      </div>

      <div class="panel">
        <h2>匹配推荐</h2>
        <label>为哪条需求找搭子
          <select v-model="selectedNeed" @change="changeSelectedNeed">
            <option value="">选择开放的需求</option>
            <option v-for="n in openNeeds" :key="n.id" :value="String(n.id)">{{ n.title }}</option>
          </select>
        </label>
        <p class="hint">分数由共同空闲时间（30 分）、共同兴趣（35 分）、需求标签契合（20 分）和技能互补（15 分）组成。</p>
        <div class="grid matches">
          <article v-for="m in matches" :key="m.user.id" class="card">
            <div class="card-top"><span class="badge">搭子候选</span><strong class="score">{{ m.score }} 分</strong></div>
            <h3>{{ m.user.name }}</h3>
            <p>{{ m.user.bio }}</p>
            <div class="tags"><span v-for="t in [...m.user.personality, ...m.user.interests]" :key="t">{{ t }}</span></div>
            <p v-for="reason in m.reasons" :key="reason" class="reason">{{ reason }}</p>
            <div class="actions">
              <button @click="showPerson(m.user.id)">查看资料</button>
              <button class="primary" :disabled="busy" @click="sendInvite(Number(selectedNeed), m.user.id)">邀请 TA</button>
            </div>
          </article>
        </div>
        <div v-if="selectedNeed && !matches.length" class="empty">暂时没有与这条需求时间重叠的候选，可以换个时间段再试。</div>
        <div v-if="!openNeeds.length" class="empty">先发布一条需求，才能看到匹配推荐。</div>
      </div>
    </div>

    <!-- 我的邀请 -->
    <div v-if="page === 'invitations'">
      <div class="panel">
        <h2>收到的邀请</h2>
        <div v-for="i in incoming" :key="i.id" class="list-row">
          <div>
            <strong>{{ i.need_title }}</strong><small>来自 {{ i.sender_name }} · {{ invitationStatus(i.status) }} · {{ formatTime(i.created_at) }}</small>
            <small v-if="i.status === 'rejected' && i.reason">我的拒绝原因：{{ i.reason }}</small>
            <form v-if="rejecting === i.id" class="reject-form" @submit.prevent="handleInvitation(i, 'rejected')">
              <label>拒绝原因（选填，最多 100 字，对方可以看到）
                <textarea v-model="rejectReason" rows="2" maxlength="100"></textarea>
              </label>
              <div class="preset-list">
                <button v-for="p in REJECT_PRESETS" :key="p" type="button" @click="rejectReason = p">{{ p }}</button>
              </div>
              <div class="actions">
                <button type="submit" class="danger" :disabled="busy">确认拒绝</button>
                <button type="button" @click="rejecting = null">取消</button>
              </div>
            </form>
          </div>
          <div class="actions">
            <button @click="showPerson(i.sender_id)">查看资料</button>
            <template v-if="i.status === 'pending' && rejecting !== i.id">
              <button class="primary" :disabled="busy" @click="handleInvitation(i, 'accepted')">同意</button>
              <button :disabled="busy" @click="startReject(i)">拒绝</button>
            </template>
          </div>
        </div>
        <div v-if="!incoming.length" class="empty">还没有收到邀请。</div>
      </div>
      <div class="panel">
        <h2>发出的邀请</h2>
        <div v-for="i in outgoing" :key="i.id" class="list-row">
          <div>
            <strong>{{ i.need_title }}</strong><small>发给 {{ i.receiver_name }} · {{ invitationStatus(i.status) }} · {{ formatTime(i.created_at) }}</small>
            <small v-if="i.status === 'rejected'" class="reason">对方的拒绝原因：{{ i.reason || '对方未填写原因' }}</small>
          </div>
          <div class="actions">
            <button @click="showPerson(i.receiver_id)">查看资料</button>
            <button v-if="i.status === 'pending'" :disabled="busy" @click="handleInvitation(i, 'withdrawn')">撤回</button>
          </div>
        </div>
        <div v-if="!outgoing.length" class="empty">还没有发出邀请。</div>
      </div>
      <div class="panel">
        <h2>匹配成功记录</h2>
        <div v-for="i in accepted" :key="i.id" class="list-row">
          <div>
            <strong>{{ i.need_title }}</strong><small>{{ i.sender_name }} 与 {{ i.receiver_name }} 已同意</small>
            <div v-if="contacts[i.id]" class="contact-list">
              <div v-for="c in contacts[i.id]" :key="c.name">
                <strong>{{ c.name }}</strong>
                <span v-if="c.email">邮箱：{{ c.email }}</span><span v-if="c.phone">电话：{{ c.phone }}</span>
                <span v-if="c.wechat">微信：{{ c.wechat }}</span><span v-if="c.qq">QQ：{{ c.qq }}</span>
                <span v-if="!c.email && !c.phone && !c.wechat && !c.qq">（未留联系方式或已注销）</span>
              </div>
            </div>
          </div>
          <button :disabled="busy" :aria-expanded="!!contacts[i.id]" @click="toggleContacts(i)">{{ contacts[i.id] ? '收起联系方式' : '查看联系方式' }}</button>
        </div>
        <div v-if="!accepted.length" class="empty">双方同意后会显示在这里。</div>
      </div>
    </div>

    <!-- 个人资料 -->
    <div v-if="page === 'profile'" class="profile-grid">
      <form class="panel" @submit.prevent="saveProfile">
        <h2>我的资料</h2>
        <p class="hint">以下资料均可不填。填写后会向登录用户展示，并用于计算匹配分数。</p>
        <div class="form-grid">
          <label>昵称<input v-model="profile.name" maxlength="30" required></label>
          <label>年龄<input v-model="profile.age" type="number" min="16" max="100"></label>
          <label>性别<select v-model="profile.gender"><option v-for="g in GENDERS" :key="g" :value="g">{{ g || '暂不填写' }}</option></select></label>
          <label>年级<select v-model="profile.grade"><option v-for="g in GRADES" :key="g" :value="g">{{ g || '暂不填写' }}</option></select></label>
          <label>家乡<input v-model="profile.hometown" maxlength="40"></label>
          <label class="span2">个人介绍<textarea v-model="profile.bio" rows="3" maxlength="300"></textarea></label>
          <label class="span2">性格特点<input v-model="profile.personality" placeholder="开朗，耐心"></label>
          <label>兴趣<input v-model="profile.interests" placeholder="羽毛球，摄影"></label>
          <label>擅长技能<input v-model="profile.skills" placeholder="Python，吉他"></label>
          <label class="span2">想学习的技能<input v-model="profile.wants"></label>
        </div>
        <h3>联系方式</h3>
        <p class="hint">至少填写一项才能发出或接受邀请。联系方式只在邀请被双方同意后向对方显示，其他用户和管理员都看不到。</p>
        <div class="form-grid">
          <label>联系邮箱<input v-model="profile.contact_email" type="email"></label>
          <label>电话<input v-model="profile.phone" type="tel"></label>
          <label>微信<input v-model="profile.wechat"></label>
          <label>QQ<input v-model="profile.qq" inputmode="numeric"></label>
        </div>
        <button class="primary" type="submit" :disabled="busy">保存资料</button>
      </form>

      <div>
        <div class="panel">
          <h2>账号信息</h2>
          <p>账号 ID：<strong>{{ user.account_id }}</strong></p>
          <p>注册邮箱：<strong>{{ user.email }}</strong></p>
          <button type="button" class="link" @click="openPrivacy">查看《个人信息使用说明》</button>
          <form @submit.prevent="changePassword">
            <h3>修改密码</h3>
            <label>当前密码<input v-model="passwords.current_password" type="password" autocomplete="current-password" required></label>
            <label>新密码（8–20 位）<span class="pwd-field"><input v-model="passwords.new_password" :type="visible.change ? 'text' : 'password'" minlength="8" maxlength="20" autocomplete="new-password" required><button type="button" class="eye" :aria-label="visible.change ? '隐藏密码' : '显示密码'" :aria-pressed="!!visible.change" @click="visible.change = !visible.change"><svg v-if="!visible.change" viewBox="0 0 24 24" aria-hidden="true"><path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7S1 12 1 12z"/><circle cx="12" cy="12" r="3"/></svg><svg v-else viewBox="0 0 24 24" aria-hidden="true"><path d="M17.9 17.9A10.8 10.8 0 0 1 12 19c-7 0-11-7-11-7a19.8 19.8 0 0 1 5.1-5.9M9.9 4.2A10 10 0 0 1 12 4c7 0 11 7 11 7a19.9 19.9 0 0 1-2.2 3.2M1 1l22 22"/><path d="M14.1 14.1a3 3 0 0 1-4.2-4.2"/></svg></button></span></label>
            <button type="submit" :disabled="busy">修改密码</button>
          </form>
          <form @submit.prevent="saveSecurityQuestions">
            <h3>设置密保问题</h3>
            <p>忘记密码时，答对任意 2 个问题即可重置。请设置不容易被别人猜到的答案。</p>
            <label>当前密码<input v-model="securityForm.password" type="password" autocomplete="current-password" required></label>
            <div v-for="(item, index) in securityForm.questions" :key="index">
              <label>问题 {{ index + 1 }}<input v-model="item.question" minlength="4" maxlength="100" required></label>
              <label>答案 {{ index + 1 }}<input v-model="item.answer" minlength="2" maxlength="100" autocomplete="off" required></label>
            </div>
            <button type="submit" :disabled="busy">保存密保问题</button>
          </form>
        </div>
        <form v-if="!user.admin" class="panel danger-zone" @submit.prevent="deleteAccount">
          <h2>注销账号</h2>
          <p>注销后将清除你的个人资料、联系方式、密保问题和浏览记录，下架你发布的需求并撤回待处理的邀请。此操作<strong>无法恢复</strong>。</p>
          <label>当前密码<input v-model="deleteForm.password" type="password" autocomplete="current-password" required></label>
          <label>请输入“{{ DELETE_CONFIRM_WORD }}”<input v-model="deleteForm.confirm" autocomplete="off" required></label>
          <button class="danger" type="submit" :disabled="busy || deleteForm.confirm !== DELETE_CONFIRM_WORD">注销账号</button>
        </form>
      </div>
    </div>

    <!-- 站内信 -->
    <div v-if="page === 'messages'" class="panel">
      <h2>{{ user.admin ? '已发送的站内信' : '我的站内信' }}</h2>
      <div v-for="m in messages" :key="m.id" class="admin-row">
        <div><strong>{{ user.admin ? `发给 ${m.receiver_name}` : m.sender_name }} · 关于「{{ m.need_title }}」</strong><small>{{ formatTime(m.created_at) }}</small><p>{{ m.body }}</p></div>
      </div>
      <div v-if="!messages.length" class="empty">暂无站内信。</div>
    </div>

    <!-- 管理端 -->
    <div v-if="page === 'admin'" class="admin-page">
      <div class="admin-stats">
        <div class="panel"><strong>{{ adminData.users.length }}</strong><span>用户</span></div>
        <div class="panel"><strong>{{ adminData.needs.length }}</strong><span>需求</span></div>
        <div class="panel"><strong>{{ adminData.invitations.length }}</strong><span>匹配成功</span></div>
      </div>
      <section class="panel">
        <h2>用户管理</h2>
        <p>管理员可查看公开资料和状态，不查看用户联系方式。停用账号会让该用户退出，恢复后可重新登录。</p>
        <input v-model="adminSearch" placeholder="搜索账号 ID 或昵称" aria-label="搜索账号 ID 或昵称">
        <div v-for="u in filteredAdminUsers" :key="u.id" class="admin-row">
          <div>
            <strong>{{ u.name }}</strong>
            <small>账号 ID：{{ u.account_id }} · 注册于 {{ formatTime(u.created_at) }}</small>
            <small>年龄：{{ u.age || '未填' }} · 性别：{{ u.gender || '未填' }} · 年级：{{ u.grade || '未填' }} · 家乡：{{ u.hometown || '未填' }}</small>
            <small>介绍：{{ u.bio || '未填' }}</small>
          </div>
          <div class="actions">
            <span v-if="u.admin" class="badge">管理员</span>
            <span v-else-if="u.deleted" class="badge muted">已注销</span>
            <button v-else :disabled="busy" :class="{ danger: !u.disabled }" @click="adminUserAction(u)">{{ u.disabled ? '恢复账号' : '停用账号' }}</button>
          </div>
        </div>
        <div v-if="!filteredAdminUsers.length" class="empty">没有匹配的用户。</div>
      </section>
      <section class="panel">
        <h2>需求管理</h2>
        <div v-for="n in adminData.needs" :key="n.id" class="admin-row">
          <div>
            <strong>{{ n.title }}</strong>
            <small>{{ n.owner_name }} · {{ n.category }} · {{ needStatus(n.status) }} · {{ formatTime(n.created_at) }}</small>
            <p>{{ n.description }}</p>
          </div>
          <div class="admin-moderation">
            <textarea v-model="adminDrafts[n.id]" rows="2" maxlength="500" placeholder="给发布者发送站内私信" aria-label="给发布者的私信内容"></textarea>
            <button :disabled="busy || !adminDrafts[n.id]?.trim()" @click="adminSendMessage(n)">发送私信</button>
            <button v-if="n.status !== 'removed'" class="danger" :disabled="busy" @click="adminRemoveNeed(n)">撤下帖子</button>
          </div>
        </div>
        <div v-if="!adminData.needs.length" class="empty">暂无需求。</div>
      </section>
      <section class="panel">
        <h2>用户需求匹配成功记录</h2>
        <div v-for="i in adminData.invitations" :key="i.id" class="admin-row">
          <div><strong>#{{ i.id }} · {{ i.sender_name }} → {{ i.receiver_name }}</strong><small>需求「{{ i.need_title }}」 · {{ formatTime(i.created_at) }}</small></div>
        </div>
        <div v-if="!adminData.invitations.length" class="empty">暂无匹配成功记录。</div>
      </section>
      <form class="panel admin-password" @submit.prevent="changePassword">
        <h2>修改管理员密码</h2>
        <div class="form-grid">
          <label>当前密码<input v-model="passwords.current_password" type="password" autocomplete="current-password" required></label>
          <label>新密码（8–20 位）<span class="pwd-field"><input v-model="passwords.new_password" :type="visible.adminPwd ? 'text' : 'password'" minlength="8" maxlength="20" autocomplete="new-password" required><button type="button" class="eye" :aria-label="visible.adminPwd ? '隐藏密码' : '显示密码'" :aria-pressed="!!visible.adminPwd" @click="visible.adminPwd = !visible.adminPwd"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7S1 12 1 12z"/><circle cx="12" cy="12" r="3"/></svg></button></span></label>
        </div>
        <button type="submit" :disabled="busy">修改密码</button>
      </form>
    </div>
  </main>

  <!-- 弹窗：需求详情 -->
  <div v-if="detail" class="modal-backdrop" @click.self="detail = null">
    <div class="modal" role="dialog" aria-modal="true" aria-labelledby="detail-title">
      <button class="close" aria-label="关闭" @click="detail = null">×</button>
      <span class="badge">{{ detail.category }}</span>
      <h2 id="detail-title">{{ detail.title }}</h2>
      <p>{{ detail.description || '发布者没有填写说明。' }}</p>
      <div class="tags">
        <span v-for="t in detail.tags" :key="t">{{ t }}</span>
        <span v-for="t in detail.slots" :key="t.day + t.start">{{ t.day }} {{ t.start }}–{{ t.end }}</span>
      </div>
      <small>{{ detail.owner_name }} · {{ detail.views }} 次浏览 · 发布于 {{ formatTime(detail.created_at) }}</small>
    </div>
  </div>

  <!-- 弹窗：个人资料 -->
  <div v-if="person" class="modal-backdrop" @click.self="person = null">
    <div class="modal" role="dialog" aria-modal="true" aria-labelledby="person-title">
      <button class="close" aria-label="关闭" @click="person = null">×</button>
      <h2 id="person-title">{{ person.name }}</h2>
      <p>{{ [person.age ? person.age + ' 岁' : '', person.gender, person.grade, person.hometown ? `家乡：${person.hometown}` : ''].filter(Boolean).join(' · ') }}</p>
      <p>{{ person.bio }}</p>
      <p v-if="person.personality.length">性格：{{ person.personality.join('、') }}</p>
      <p v-if="person.interests.length">兴趣：{{ person.interests.join('、') }}</p>
      <p v-if="person.skills.length">擅长：{{ person.skills.join('、') }}</p>
      <p v-if="person.wants.length">想学习：{{ person.wants.join('、') }}</p>
      <small>联系方式仅在双方同意邀请后开放。</small>
    </div>
  </div>

  <!-- 弹窗：个人信息使用说明（内容来自后端 /api/privacy，保证说明与实际行为一致） -->
  <div v-if="showPrivacy" class="modal-backdrop" @click.self="showPrivacy = false">
    <div class="modal wide" role="dialog" aria-modal="true" aria-labelledby="privacy-title">
      <button class="close" aria-label="关闭" @click="showPrivacy = false">×</button>
      <h2 id="privacy-title">个人信息使用说明</h2>
      <p v-if="!privacy">加载中…</p>
      <template v-else>
        <table class="privacy-table">
          <thead><tr><th scope="col">采集的信息</th><th scope="col">用途</th><th scope="col">谁能看到</th></tr></thead>
          <tbody><tr v-for="item in privacy.items" :key="item.data"><td>{{ item.data }}</td><td>{{ item.purpose }}</td><td>{{ item.visible }}</td></tr></tbody>
        </table>
        <p v-for="note in privacy.notes" :key="note" class="hint">{{ note }}</p>
        <small>版本 {{ privacy.version }}</small>
      </template>
    </div>
  </div>

  <div v-if="toast" class="toast" :class="{ bad: toast.kind === 'error' }" :role="toast.kind === 'error' ? 'alert' : 'status'">
    <span>{{ toast.text }}</span>
    <button v-if="toast.undo" class="toast-undo" @click="toast.undo()">撤销</button>
    <button class="toast-close" aria-label="关闭提示" @click="toast = null">×</button>
  </div>
</div>
</template>
