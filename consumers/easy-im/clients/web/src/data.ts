export type AvatarTone = 'mint' | 'coral' | 'sky' | 'violet' | 'amber' | 'rose' | 'ocean' | 'sage'
export type MessageKind = 'text' | 'image' | 'file' | 'link'

export interface ChatMessage {
  id: string
  from: 'me' | 'them'
  author?: string
  text?: string
  time: string
  kind?: MessageKind
  attachment?: {
    title: string
    meta: string
  }
}

export interface Conversation {
  id: string
  name: string
  kind: 'direct' | 'group'
  subtitle: string
  lastMessage: string
  time: string
  unread: number
  pinned?: boolean
  muted?: boolean
  online?: boolean
  members?: number
  tone: AvatarTone
  avatarChars: string[]
  messages: ChatMessage[]
}

export type ContactInitial = 'A' | 'C' | 'G' | 'L' | 'S' | 'W' | 'X' | 'Z'

export interface Contact {
  id: string
  name: string
  initial: ContactInitial
  title: string
  tone: AvatarTone
  online: boolean
  location: string
  signature: string
  tags: string[]
  phone: string
  email: string
  mutualGroups: string[]
}

export interface ContactGroup {
  id: string
  name: string
  members: number
  online: number
  description: string
  announcement: string
  tone: AvatarTone
  avatarChars: string[]
  memberNames: string[]
  recentActivity: string
}

export interface UserProfile {
  name: string
  handle: string
  signature: string
  location: string
  email: string
  phone: string
}

export const initialConversations: Conversation[] = [
  {
    id: 'design-team',
    name: '产品设计组',
    kind: 'group',
    subtitle: '12 位成员',
    lastMessage: '顾言：交互稿已经更新到最新版了',
    time: '10:24',
    unread: 2,
    pinned: true,
    members: 12,
    tone: 'mint',
    avatarChars: ['顾', '苏', '林', '周'],
    messages: [
      {
        id: 'dt-1',
        from: 'them',
        author: '顾言',
        text: '早上好，大家看下新版路由结构～',
        time: '09:42',
      },
      {
        id: 'dt-2',
        from: 'me',
        text: '收到，我刚看完。移动端的返回路径还可以再简化一点。',
        time: '09:50',
      },
      {
        id: 'dt-3',
        from: 'them',
        author: '苏禾',
        text: '新版交互预览：',
        time: '10:03',
        kind: 'image',
        attachment: {
          title: '消息中心 · 空状态',
          meta: 'Design Preview · 2.4 MB',
        },
      },
      {
        id: 'dt-4',
        from: 'them',
        author: '顾言',
        text: '交互稿已经更新到最新版了，重点调整了空状态和加载态。',
        time: '10:24',
      },
    ],
  },
  {
    id: 'shen-yu',
    name: '沈屿',
    kind: 'direct',
    subtitle: '在线',
    lastMessage: '周末一起去看展吗？',
    time: '09:48',
    unread: 1,
    online: true,
    tone: 'sky',
    avatarChars: ['沈'],
    messages: [
      {
        id: 'sy-1',
        from: 'them',
        text: '这周末上海有个「光与建筑」的展，感觉你会喜欢。',
        time: '09:45',
      },
      {
        id: 'sy-2',
        from: 'them',
        text: '周末一起去看展吗？',
        time: '09:48',
      },
    ],
  },
  {
    id: 'frontend-team',
    name: '前端开发小组',
    kind: 'group',
    subtitle: '8 位成员',
    lastMessage: '苏禾：构建已经通过 ✅',
    time: '昨天',
    unread: 5,
    pinned: true,
    members: 8,
    tone: 'amber',
    avatarChars: ['苏', '陈', '许', '林'],
    messages: [
      {
        id: 'fe-1',
        from: 'them',
        author: '陈序',
        text: '主题色的 token 我统一放到 variables.css 了。',
        time: '昨天 17:36',
      },
      {
        id: 'fe-2',
        from: 'them',
        author: '苏禾',
        text: '构建已经通过 ✅',
        time: '昨天 18:02',
      },
    ],
  },
  {
    id: 'zhao-qinghe',
    name: '赵清和',
    kind: 'direct',
    subtitle: '2 小时前在线',
    lastMessage: '[图片]',
    time: '昨天',
    unread: 0,
    tone: 'coral',
    avatarChars: ['赵'],
    messages: [
      {
        id: 'zq-1',
        from: 'them',
        text: '上次拍的照片洗出来了。',
        time: '昨天 15:20',
      },
      {
        id: 'zq-2',
        from: 'them',
        time: '昨天 15:21',
        kind: 'image',
        attachment: {
          title: '白墙与树影',
          meta: 'IMG_2048.HEIC · 4.8 MB',
        },
      },
    ],
  },
  {
    id: 'night-reading',
    name: '夜读会',
    kind: 'group',
    subtitle: '26 位成员',
    lastMessage: '本周共读：《人类简史》',
    time: '周三',
    unread: 0,
    muted: true,
    members: 26,
    tone: 'violet',
    avatarChars: ['夜', '读', '会', '书'],
    messages: [
      {
        id: 'nr-1',
        from: 'them',
        author: '温澜',
        text: '本周共读：《人类简史》，周日晚上一起聊聊。',
        time: '周三 20:12',
      },
    ],
  },
  {
    id: 'wen-lan',
    name: '温澜',
    kind: 'direct',
    subtitle: '离线',
    lastMessage: '收到，谢谢～',
    time: '周二',
    unread: 0,
    tone: 'rose',
    avatarChars: ['温'],
    messages: [
      {
        id: 'wl-1',
        from: 'me',
        text: '资料发你邮箱了，有空帮我看看。',
        time: '周二 16:40',
      },
      {
        id: 'wl-2',
        from: 'them',
        text: '收到，谢谢～',
        time: '周二 16:42',
      },
    ],
  },
  {
    id: 'mori',
    name: 'Mori',
    kind: 'direct',
    subtitle: '昨天在线',
    lastMessage: '下次一起喝咖啡。',
    time: '周一',
    unread: 0,
    tone: 'sage',
    avatarChars: ['M'],
    messages: [
      {
        id: 'mo-1',
        from: 'them',
        text: '下次一起喝咖啡。',
        time: '周一 11:05',
      },
    ],
  },
]

export const initialContacts: Contact[] = [
  {
    id: 'c-shen-yu',
    name: '沈屿',
    initial: 'S',
    title: '建筑设计师',
    tone: 'sky',
    online: true,
    location: '上海',
    signature: '在城市里寻找光留下来的痕迹。',
    tags: ['朋友', '看展搭子'],
    phone: '138 **** 2176',
    email: 'shenyu@example.com',
    mutualGroups: ['产品设计组', '周末出逃计划'],
  },
  {
    id: 'c-su-he',
    name: '苏禾',
    initial: 'S',
    title: '前端工程师',
    tone: 'mint',
    online: true,
    location: '杭州',
    signature: '把复杂的事情做得简单一点。',
    tags: ['同事', '前端'],
    phone: '186 **** 9032',
    email: 'suhe@example.com',
    mutualGroups: ['前端开发小组', '产品设计组'],
  },
  {
    id: 'c-gu-yan',
    name: '顾言',
    initial: 'G',
    title: '产品设计师',
    tone: 'amber',
    online: false,
    location: '上海',
    signature: '保持好奇，保持松弛。',
    tags: ['同事', '设计'],
    phone: '139 **** 5821',
    email: 'guyan@example.com',
    mutualGroups: ['产品设计组'],
  },
  {
    id: 'c-wen-lan',
    name: '温澜',
    initial: 'W',
    title: '内容编辑',
    tone: 'rose',
    online: false,
    location: '北京',
    signature: '读书、写字，偶尔发呆。',
    tags: ['朋友', '书友'],
    phone: '137 **** 0448',
    email: 'wenlan@example.com',
    mutualGroups: ['夜读会'],
  },
  {
    id: 'c-zhao-qinghe',
    name: '赵清和',
    initial: 'Z',
    title: '摄影师',
    tone: 'coral',
    online: true,
    location: '成都',
    signature: '记录日常里的微小闪光。',
    tags: ['朋友', '摄影'],
    phone: '185 **** 6690',
    email: 'qinghe@example.com',
    mutualGroups: ['周末出逃计划'],
  },
  {
    id: 'c-chen-xu',
    name: '陈序',
    initial: 'C',
    title: '后端工程师',
    tone: 'ocean',
    online: false,
    location: '深圳',
    signature: '稳定优先，简单至上。',
    tags: ['同事', '技术'],
    phone: '136 **** 8120',
    email: 'chenxu@example.com',
    mutualGroups: ['前端开发小组'],
  },
  {
    id: 'c-lin-mo',
    name: '林默',
    initial: 'L',
    title: '独立开发者',
    tone: 'violet',
    online: false,
    location: '杭州',
    signature: '正在做一个很慢的产品。',
    tags: ['朋友', '独立开发'],
    phone: '158 **** 3307',
    email: 'linmo@example.com',
    mutualGroups: ['夜读会'],
  },
  {
    id: 'c-xu-zhi',
    name: '许知',
    initial: 'X',
    title: '插画师',
    tone: 'sage',
    online: true,
    location: '广州',
    signature: '今天也在认真画小房子。',
    tags: ['朋友', '插画'],
    phone: '135 **** 4912',
    email: 'xuzhi@example.com',
    mutualGroups: ['产品设计组', '周末出逃计划'],
  },
  {
    id: 'c-an-ran',
    name: '安然',
    initial: 'A',
    title: '品牌策划',
    tone: 'mint',
    online: false,
    location: '南京',
    signature: '慢慢来，比较快。',
    tags: ['朋友', '品牌'],
    phone: '187 **** 7725',
    email: 'anran@example.com',
    mutualGroups: [],
  },
]

export const initialGroups: ContactGroup[] = [
  {
    id: 'g-design-team',
    name: '产品设计组',
    members: 12,
    online: 5,
    description: '产品、设计与前端同学的产品讨论空间。',
    announcement: '每周五 16:00 设计评审；文件请统一归档到「设计资源」目录。',
    tone: 'mint',
    avatarChars: ['顾', '苏', '林', '周'],
    memberNames: ['顾言', '苏禾', '许知', '林默', '赵清和'],
    recentActivity: '顾言 10:24 更新了交互稿',
  },
  {
    id: 'g-frontend-team',
    name: '前端开发小组',
    members: 8,
    online: 3,
    description: '前端技术、构建发布与日常协作文档。',
    announcement: '主干分支已切换为 main，提交前请先执行完整构建。',
    tone: 'amber',
    avatarChars: ['苏', '陈', '许', '林'],
    memberNames: ['苏禾', '陈序', '许知', '林默'],
    recentActivity: '苏禾 昨天 18:02 构建通过',
  },
  {
    id: 'g-weekend',
    name: '周末出逃计划',
    members: 16,
    online: 7,
    description: '认真工作，也认真过周末。',
    announcement: '本周目的地投票进行中，周四晚截止。',
    tone: 'coral',
    avatarChars: ['沈', '赵', '许', '安'],
    memberNames: ['沈屿', '赵清和', '许知', '安然'],
    recentActivity: '沈屿 09:48 发起周末看展邀约',
  },
  {
    id: 'g-reading',
    name: '夜读会',
    members: 26,
    online: 9,
    description: '一周一本书，分享阅读里的小发现。',
    announcement: '本周共读《人类简史》，周日 20:00 语音讨论。',
    tone: 'violet',
    avatarChars: ['夜', '读', '会', '书'],
    memberNames: ['温澜', '林默', '安然', '顾言'],
    recentActivity: '温澜 周三 20:12 更新共读书目',
  },
]

export const initialProfile: UserProfile = {
  name: '林知夏',
  handle: 'phantom_lin',
  signature: '今天也要保持好奇 ✨',
  location: '上海',
  email: 'lin.zhixia@example.com',
  phone: '138 **** 1024',
}
