/**
 * 终端标识：每台公共电脑用带 ?t=TERM01 的地址打开一次即可（安装时配置快捷方式），
 * 之后写入 localStorage，下单时随订单上报，打印助手只领自己终端的任务。
 */
const KEY = 'printsysTerminalId'

export function initTerminal() {
  const params = new URLSearchParams(window.location.search)
  const fromUrl = params.get('t') || params.get('terminal')
  if (fromUrl) {
    localStorage.setItem(KEY, fromUrl.trim())
    return fromUrl.trim()
  }
  return localStorage.getItem(KEY) || ''
}

export function getTerminalId() {
  return localStorage.getItem(KEY) || ''
}

export function setTerminalId(id) {
  if (id) localStorage.setItem(KEY, id.trim())
}
