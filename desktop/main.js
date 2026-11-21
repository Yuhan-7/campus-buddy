// 校园搭子 Windows 桌面版：用 Electron 把网页包装成独立窗口程序。
// 界面和业务逻辑完全复用网页版（frontend/），后端仍由 Docker Compose 启动。
// 桌面外壳只负责：窗口、中文菜单与快捷键、连接失败提示、外部链接用系统浏览器打开。
const { app, BrowserWindow, Menu, dialog, shell } = require('electron')
const path = require('path')

// 后端地址：本机运行默认 http://localhost:8088；在 Windows 虚拟机里可通过环境变量指向宿主机
const APP_URL = process.env.CAMPUS_BUDDY_URL || 'http://localhost:8088'
let win

function createWindow() {
  win = new BrowserWindow({
    width: 1280,
    height: 860,
    minWidth: 800,
    minHeight: 600,
    title: '校园搭子匹配系统',
    show: false,
    autoHideMenuBar: false, // 菜单栏常显：键盘用户按 Alt 即可进入菜单
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  })
  win.once('ready-to-show', () => { win.show(); win.focus() })

  // 不拦截 page-title-updated：网页切换页面时修改 document.title，窗口标题随之变化，
  // 讲述人按 Alt+Tab 切换窗口时能读出当前所在页面

  // 后端没启动或地址不对：显示本地说明页，而不是一片空白
  win.webContents.on('did-fail-load', (_e, code, desc, url, isMainFrame) => {
    if (!isMainFrame || code === -3) return // -3 为主动取消，不算失败
    win.loadFile(path.join(__dirname, 'offline.html'), { query: { url: APP_URL, reason: desc } })
  })

  // 只在本程序内打开系统自己的页面，其他链接交给系统默认浏览器
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (!url.startsWith(APP_URL)) shell.openExternal(url)
    return { action: 'deny' }
  })
  win.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith(APP_URL) && !url.startsWith('file:')) { e.preventDefault(); shell.openExternal(url) }
  })

  win.loadURL(APP_URL)
}

function showShortcuts() {
  dialog.showMessageBox(win, {
    type: 'info',
    title: '键盘操作说明',
    message: '键盘操作说明',
    detail: [
      'Tab / Shift+Tab：在按钮和输入框之间前后移动',
      'Enter 或空格：按下当前按钮',
      'Esc：关闭弹出窗口',
      'Alt+Z：撤销刚刚关闭的需求',
      'Alt：进入窗口菜单',
      'Ctrl+加号 / Ctrl+减号 / Ctrl+0：放大 / 缩小 / 恢复字号',
      'F5：重新加载',
      'F11：全屏',
      '',
      '页面右上角的“大字”“高对比”按钮可以单独开启；',
      '也可以直接使用 Windows 设置里的“对比度主题”，程序会自动跟随。'
    ].join('\n'),
    buttons: ['知道了']
  })
}

function buildMenu() {
  return Menu.buildFromTemplate([
    {
      label: '文件(&F)',
      submenu: [
        { label: '重新加载', accelerator: 'F5', click: () => win.loadURL(APP_URL) },
        { type: 'separator' },
        { label: '退出', role: 'quit' }
      ]
    },
    {
      label: '编辑(&E)',
      submenu: [
        { label: '撤销输入', role: 'undo' },
        { label: '重做', role: 'redo' },
        { type: 'separator' },
        { label: '剪切', role: 'cut' },
        { label: '复制', role: 'copy' },
        { label: '粘贴', role: 'paste' },
        { label: '全选', role: 'selectAll' }
      ]
    },
    {
      label: '查看(&V)',
      submenu: [
        { label: '放大', accelerator: 'CmdOrCtrl+=', role: 'zoomIn' },
        { label: '缩小', accelerator: 'CmdOrCtrl+-', role: 'zoomOut' },
        { label: '恢复默认大小', accelerator: 'CmdOrCtrl+0', role: 'resetZoom' },
        { type: 'separator' },
        { label: '全屏', accelerator: 'F11', role: 'togglefullscreen' }
      ]
    },
    {
      label: '帮助(&H)',
      submenu: [
        { label: '键盘操作说明', accelerator: 'F1', click: showShortcuts }
      ]
    }
  ])
}

// 显式开启 Chromium 的无障碍树，确保讲述人 / NVDA 能读取页面上的名称、角色和状态
app.whenReady().then(() => {
  app.setAccessibilitySupportEnabled(true)
  Menu.setApplicationMenu(buildMenu())
  createWindow()
})

app.on('window-all-closed', () => app.quit())
