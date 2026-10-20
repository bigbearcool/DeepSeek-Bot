/**
 * Chinese for the ds-bot refusals the phone can meet when it creates a Bot or a group chat
 * or posts to one. Taken from ds-bot src/client/i18n.js (HOST_ZH / HOST_ERRORS, 0.1.9),
 * with "Bot" said as 「助手」 like the rest of the phone app. ds-bot keeps its own errors
 * English (Bots read them too), so the translation happens where they are shown.
 */
const EXACT = {
  'A Bot needs a name': '助手需要一个名字',
  'A Bot name cannot contain @, ` or a line break': '助手名字里不能有 @、` 或换行',
  'Avatar images must be PNG, JPEG, WebP, or GIF': '头像图片必须是 PNG、JPEG、WebP 或 GIF',
  'Avatar image is too large': '头像图片太大了',
  'A group chat needs at least two Bots': '群聊至少要两个助手',
  'The admin must be a member of the group': '管理员必须是群成员',
  'Unknown Bot': '找不到这个助手',
  'Unknown group chat': '找不到这个群聊',
  'unknown group chat': '找不到这个群聊',
  'Pick a model before choosing a thinking level': '请先选模型，再选思考强度',
  'No model is available. Add one in Harness settings.': '没有可用的模型，请先在电脑上添加模型',
  'This DeepSeek Harness cannot list folders': '这台电脑不能在手机上浏览文件夹，请直接填写完整路径',
  'Nothing to send': '没有要发送的内容',
  'DS Bot is not running on this computer': '电脑上的 DS Bot 没有在运行',
  'This approval is no longer open': '这个请求已经处理过了',
  'Choose allow or reject': '请选择同意或拒绝',
}
const PATTERNS = [
  [/^A Bot named (.+) already exists$/, '已经有叫 $1 的助手了'],
  [/^Name (.*) is not available$/, '名字 $1 不可用'],
  [/^"(.+)" calls everyone in a group, so it cannot be a Bot name$/, '「$1」在群里表示所有人，不能用作助手名字'],
  [/^"(.+)" stands for the user in a chat, so it cannot be a Bot name$/, '「$1」在对话里表示你本人，不能用作助手名字'],
  [/^Another group chat is already called "(.+)"$/, '已经有叫「$1」的群聊了'],
  [/^A group chat holds at most (\d+) Bots$/, '一个群聊最多 $1 个助手'],
  [/^Model (.+) is not available\. Available: (.*)$/, '模型 $1 不可用'],
  [/^No agent preset named (.+)$/, '没有叫 $1 的工作方式'],
  [/^Give the full path of the folder, not (.+)$/, '请填写文件夹的完整路径，而不是 $1'],
  [/^There is no folder at (.+)$/, '找不到文件夹 $1'],
  [/^(.+) is a file, not a folder$/, '$1 是文件，不是文件夹'],
  [/^(.+) holds DeepSeek Harness data, so Bots cannot work there$/, '$1 里有系统数据，助手不能在这里工作'],
  [/^The Bot team in (.+) is open in another dsh/, '助手团队正在另一台电脑上打开，这里只能查看'],
]

export function zhMessage(message) {
  const text = String(message ?? '')
  if (Object.hasOwn(EXACT, text)) return EXACT[text]
  for (const [pattern, template] of PATTERNS) if (pattern.test(text)) return text.replace(pattern, template)
  return null
}
