// 界面常量与文案格式化（班级公约第八条：交付前文案校对，界面不直接显示英文状态码）。

export const CATEGORIES = ['学习', '运动', '兴趣', '技能互助', '项目组队']
export const DAYS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
export const GENDERS = ['', '男', '女', '其他', '不愿透露']
export const GRADES = ['', '大一', '大二', '大三', '大四', '大五', '研一', '研二', '研三', '博士', '其他']
export const DELETE_CONFIRM_WORD = '确认注销'

const INVITATION_STATUS = { pending: '等待对方处理', accepted: '已同意', rejected: '已拒绝', withdrawn: '已撤回' }
const NEED_STATUS = { open: '开放中', closed: '已关闭', removed: '管理员已撤下', deleted: '已删除' }

export const invitationStatus = s => INVITATION_STATUS[s] || s
export const needStatus = s => NEED_STATUS[s] || s

/** “2026-11-11 10:05:03.123” 或 ISO 格式 → “2026-11-11 10:05” */
export function formatTime(value) {
  if (!value) return ''
  const text = String(value).replace('T', ' ')
  return text.length >= 16 ? text.slice(0, 16) : text
}

/** 把“羽毛球，Python、摄影”拆成去重后的标签数组 */
export const splitTags = s => [...new Set(String(s || '').split(/[,，、]/).map(x => x.trim()).filter(Boolean))]

export const toTime = n => `${String(Math.floor(n / 60)).padStart(2, '0')}:${String(n % 60).padStart(2, '0')}`
export const toMinute = s => Number(s.slice(0, 2)) * 60 + Number(s.slice(3))
/** 06:00 – 23:00，每 30 分钟一档 */
export const TIME_OPTIONS = Array.from({ length: 35 }, (_, i) => toTime(360 + i * 30))
