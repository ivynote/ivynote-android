package app.ivynote.core

import java.util.Locale

/**
 * Every string the app shows, in each [Language]. One interface, so a string
 * missing from a language does not compile. Wording follows the web client's
 * dictionary (`client/packages/core/src/i18n.ts`) where both have it.
 */
interface Strings {
    /** For dates, numbers and sorting in this language. */
    val locale: Locale

    // Common
    val appName: String
    val slogan: String
    val tagline: String
    val name: String
    val save: String
    val cancel: String
    val create: String
    val delete: String
    val remove: String
    val rename: String
    val open: String
    val retry: String
    val done: String
    val back: String
    val more: String
    val clear: String
    val ok: String
    val copied: String
    val restore: String
    val deleteForever: String
    val untitled: String
    val unnamed: String
    val note: String
    val notes: String
    val tags: String
    val trash: String
    val pinned: String
    val needsNetwork: String
    val requestFailed: String
    val cannotReadFile: String

    // Language
    val language: String
    val followSystem: String
    fun followSystemWith(language: String): String
    val languageAccountHint: String
    val languageDeviceHint: String

    // Dates
    val justNow: String
    fun yesterday(time: String): String
    fun weekday(dayOfWeek: Int): String
    fun monthDay(month: Int, day: Int): String
    fun yearMonthDay(year: Int, month: Int, day: Int): String
    /** For [java.text.SimpleDateFormat]: a moment with the time. */
    val dateTimePattern: String
    /** For [java.text.SimpleDateFormat]: a day this year. */
    val monthDayPattern: String

    // Sync status (guide 11.7)
    val localUnsynced: String
    val downloadingNotes: String
    val syncing: String
    val offline: String
    fun offlinePending(n: Int): String
    val syncError: String
    fun pending(n: Int): String
    fun synced(ago: String): String
    val neverSynced: String
    fun blocked(n: Int): String

    // Libraries
    val libraries: String
    val welcome: String
    val pickLibrary: String
    val connect: String
    val connectHint: String
    val newLocal: String
    val newLocalHint: String
    val newLocalMessage: String
    val canConvert: String
    val startCloud: String
    val startCloudHint: String
    val localOnly: String
    val defaultLibraryName: String
    val renameLibrary: String
    val removeLibrary: String
    fun removeLocalMessage(name: String): String
    fun removeServerMessage(name: String): String
    val turnOnSync: String
    val migratedHint: String
    val cannotOpenData: String
    val backToLibraries: String

    // Account
    val account: String
    val username: String
    val email: String
    val role: String
    val admin: String
    val user: String
    fun syncSection(library: String): String
    val server: String
    val status: String
    val thisDevice: String
    val syncNow: String
    val openAdmin: String
    val signingOut: String
    val signOut: String
    val signOutHint: String
    val signOutTitle: String
    fun signOutUnsynced(n: Int): String
    val signOutMessage: String

    // Library and shelf
    fun uploadRefused(name: String, message: String): String
    val conflictTitle: String
    fun conflictMessage(title: String): String
    val openCopy: String
    val newNote: String
    val newFolder: String
    val newSubfolder: String
    val renameFolder: String
    val deleteFolderTitle: String
    fun deleteFolderMessage(name: String): String
    val renameNote: String
    val emptyTrashTitle: String
    val emptyTrashMessage: String
    val emptyTrash: String
    val switchLibrary: String
    val accountAndSync: String
    val turnOnSyncEllipsis: String
    val mediaLibrary: String
    val manageLibraries: String
    val searchNotes: String
    val sortAndNew: String
    fun showMore(next: Int, hidden: Int): String
    val collapse: String
    val expand: String
    val sort: String
    val sortUpdated: String
    val sortCreated: String
    val sortTitle: String
    val createFolderRow: String
    val editTagEllipsis: String
    val pin: String
    val unpin: String
    val moveToEllipsis: String
    val moveTo: String
    val duplicateNote: String
    val duplicateSuffix: String
    val moveToTrash: String
    val noNotebook: String
    fun noResults(query: String): String

    // Note
    val share: String
    val outline: String
    val tagsEllipsis: String
    val backlinks: String
    fun backlinksMenu(n: Int): String
    fun backlinksCount(n: Int): String
    val history: String
    val noteInTrash: String
    val formatPhoto: String
    val formatCamera: String
    val formatAttachment: String
    val formatHeading: String
    val formatBold: String
    val formatItalic: String
    val formatTask: String
    val formatList: String
    val formatOrdered: String
    val formatQuote: String
    val formatCode: String
    val formatWikiLink: String
    val formatLink: String
    val formatStrike: String
    val undo: String
    val hideKeyboard: String
    val noHeadings: String
    val noHeadingsHint: String
    val noBacklinks: String
    fun noBacklinksHint(title: String): String

    // Tags
    fun tagColor(color: String): String
    val searchOrCreateTag: String
    fun maxTags(n: Int): String
    fun createTag(name: String): String
    val editTag: String
    val noTags: String
    val tagExists: String
    val color: String
    val deleteTag: String
    val deleteTagTitle: String
    fun deleteTagMessage(name: String): String

    // History
    val restoreVersion: String
    val localNoHistory: String
    val localNoHistoryHint: String
    val historyLoadFailed: String
    val noHistory: String
    val noHistoryHint: String
    val unknownDevice: String
    val currentVersion: String
    val restoreVersionTitle: String
    val restoreVersionMessage: String
    val restoredVersion: String

    // Sharing
    val offlineNoPublicLinks: String
    val shareNote: String
    val localNoShare: String
    val shareNeedsNetwork: String
    val publicLink: String
    val publicLinkHint: String
    val legacyShare: String
    val needPassword: String
    val newPasswordPlaceholder: String
    val generate: String
    val createLink: String
    val copyLink: String
    val shareExpired: String
    val copyPassword: String
    val passwordSet: String
    val changePassword: String
    val copyLinkAndPassword: String
    val expires: String
    fun views(n: Int): String
    val copyOrExport: String
    val copyAsHint: String
    val copyAsOffline: String
    val copyMarkdown: String
    val sendToApp: String
    val exportMd: String
    val neverExpires: String
    fun until(date: String): String
    fun days(n: Int): String
    fun shareMessage(title: String, link: String, password: String): String
    fun sharePasswordMin(n: Int): String
    fun sharePasswordMax(n: Int): String

    // Media
    val fileTooLarge: String
    fun queuedUpload(name: String): String
    val duplicateMerged: String
    fun cannotAddFile(error: String): String
    val noCamera: String
    val noFilePicker: String
    val upload: String
    val photoOrVideo: String
    val camera: String
    val file: String
    val searchFiles: String
    val mediaOffline: String
    fun orphanBanner(n: Int, days: Int): String
    fun orphanBannerNoDays(n: Int): String
    val mediaTrashEmpty: String
    val mediaEmpty: String
    val isPublic: String
    fun refs(n: Int): String
    val unreferenced: String
    val cannotGetFile: String
    val type: String
    val size: String
    val uploadedAt: String
    val publicUrl: String
    val publicPlaceholder: String
    val trashedOrphan: String
    val trashedManual: String
    val noNotesReference: String
    fun referencedBy(n: Int): String
    val copyReference: String
    val copyPublicUrl: String
    val makePublicUrl: String
    val revokePublicUrl: String
    val shareOriginal: String
    val localFilesHint: String
    val publicFilesHint: String
    val renameFile: String
    val purgeFileTitle: String
    fun purgeFileMessage(name: String): String
    val filterAll: String
    val filterImages: String
    val filterVideo: String
    val filterAudio: String
    val filterFiles: String
    val attachment: String
    val fileNotFound: String
    val nameEmpty: String
    val localNoPublicLink: String

    // Sign in
    val wrongCredentials: String
    val signInTitle: String
    val turnOnSyncTitle: String
    val continueMigration: String
    val syncNode: String
    val regionCn: String
    val regionGlobal: String
    fun officialRegion(region: String): String
    val customServerEllipsis: String
    val nodeUnreachable: String
    val signInHint: String
    fun promoteHint(name: String): String
    val register: String
    val login: String
    val firstAdmin: String
    val registrationClosed: String
    val registerAndSync: String
    val promoteNote: String
    fun migrationRejected(n: Int): String
    fun migrationFilesFailed(n: Int): String
    fun migrationInterrupted(error: String): String
    val preparing: String
    fun uploadingFiles(done: Int, total: Int): String
    fun uploadingNotes(done: Int, total: Int): String
    val migrationDone: String
    val customServer: String
    val insecureWarning: String
    val customServerHint: String
    val useServer: String
    val cannotConnect: String
    val httpWarning: String
    val createAccount: String
    val usernameOrEmail: String
    val emailOptional: String
    val displayNameOptional: String
    val password: String
    val passwordMin8: String

    // Updates
    fun aboutNewer(newer: String): String
    fun aboutVersion(version: String): String
    val aboutAndUpdates: String
    val updateAvailable: String
    fun versionReleased(version: String): String
    fun currentVersionIs(version: String): String
    fun downloading(progress: String): String
    val savingAndInstalling: String
    val updateReady: String
    val debugUpdateHint: String
    val webUpdateHint: String
    val updateHint: String
    fun downloadFailed(error: String): String
    fun installFailed(error: String): String
    val downloadInBackground: String
    val installing: String
    val installNow: String
    val downloadFromWebsite: String
    val updateNow: String
    val cancelDownload: String
    val websiteDownload: String
    val skipVersion: String
    val later: String
    val aboutTagline: String
    val version: String
    val checkForUpdates: String
    val checking: String
    fun downloadingVersion(version: String): String
    fun newVersion(version: String): String
    val checkFailed: String
    val upToDate: String
    val anonymousStats: String
    val anonymousStatsHint: String
    val website: String
    fun upToDateVersion(version: String): String
    fun checkUpdateFailed(error: String): String
    val allowInstall: String
    val apkInvalid: String
    val signatureMismatch: String
    val noStorage: String
    val installBlocked: String
    val installIncomplete: String

    // Errors and checks (core)
    fun unreachable(detail: String): String
    val notIvynote: String
    val tooManyAttempts: String
    fun serverUnavailable(status: Int): String
    fun requestFailedStatus(status: Int): String
    val accountNotEmpty: String
    val accountUnavailable: String
    val sessionExpired: String
    val usernameRule: String
    val passwordTooShort: String
    val passwordTooLong: String
    val serverEmpty: String
    val serverInvalid: String
    fun unsupportedScheme(scheme: String): String
    fun insecureRemote(host: String): String
}

object ZhStrings : Strings {
    override val locale: Locale = Locale.SIMPLIFIED_CHINESE

    override val appName = "常青笔记"
    override val slogan = "本地优先的 Markdown 笔记"
    override val tagline = "像常春藤一样，你的笔记彼此相连、四季常青。"
    override val name = "名称"
    override val save = "保存"
    override val cancel = "取消"
    override val create = "创建"
    override val delete = "删除"
    override val remove = "移除"
    override val rename = "重命名"
    override val open = "打开"
    override val retry = "重试"
    override val done = "完成"
    override val back = "返回"
    override val more = "更多"
    override val clear = "清除"
    override val ok = "好"
    override val copied = "已复制"
    override val restore = "恢复"
    override val deleteForever = "永久删除"
    override val untitled = "无标题"
    override val unnamed = "未命名"
    override val note = "笔记"
    override val notes = "笔记"
    override val tags = "标签"
    override val trash = "回收站"
    override val pinned = "置顶"
    override val needsNetwork = "需要联网"
    override val requestFailed = "请求失败"
    override val cannotReadFile = "无法读取文件"

    override val language = "语言 · Language"
    override val followSystem = "跟随系统"
    override fun followSystemWith(language: String) = "跟随系统（$language）"
    override val languageAccountHint = "保存在你的账户中，在登录这个账户的所有设备上生效。"
    override val languageDeviceHint = "只对这台设备生效。登录同步账户后，以账户的语言设置为准。"

    override val justNow = "刚刚"
    override fun yesterday(time: String) = "昨天 $time"
    override fun weekday(dayOfWeek: Int) = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")[(dayOfWeek - 1).mod(7)]
    override fun monthDay(month: Int, day: Int) = "${month}月${day}日"
    override fun yearMonthDay(year: Int, month: Int, day: Int) = "${year}年${month}月${day}日"
    override val dateTimePattern = "yyyy年M月d日 HH:mm"
    override val monthDayPattern = "M月d日"

    override val localUnsynced = "仅保存在本机 · 未同步"
    override val downloadingNotes = "正在下载笔记…"
    override val syncing = "同步中…"
    override val offline = "离线"
    override fun offlinePending(n: Int) = "离线 · $n 条待同步"
    override val syncError = "同步出错"
    override fun pending(n: Int) = "$n 条待同步"
    override fun synced(ago: String) = "已同步 · $ago"
    override val neverSynced = "尚未同步"
    override fun blocked(n: Int) = "$n 条更改被服务器拒绝"

    override val libraries = "笔记库"
    override val welcome = "本地优先的 Markdown 笔记，在手机、电脑和网页间同步。"
    override val pickLibrary = "选择要打开的笔记库"
    override val connect = "登录云同步账户"
    override val connectHint = "在多台设备间同步，支持官方节点和自建服务器"
    override val newLocal = "新建本地笔记库"
    override val newLocalHint = "只保存在这台设备，无需账户，立即可用"
    override val newLocalMessage = "笔记只保存在这台设备上，不会同步。"
    override val canConvert = "本地笔记库可以随时开启同步：注册新账户后，内容会自动上传。"
    override val startCloud = "开始使用 · 多端同步"
    override val startCloudHint = "登录或注册，笔记在手机、电脑和网页间自动同步"
    override val localOnly = "不登录，仅在本机使用"
    override val defaultLibraryName = "我的笔记"
    override val renameLibrary = "重命名笔记库"
    override val removeLibrary = "移除笔记库"
    override fun removeLocalMessage(name: String) = "将永久删除「$name」里的全部笔记，无法恢复。"
    override fun removeServerMessage(name: String) = "将从这台设备删除「$name」的本地副本和登录状态。服务器上的笔记不受影响，但尚未同步的更改会丢失。"
    override val turnOnSync = "开启同步"
    override val migratedHint = "已迁移到云端，确认无误后可以移除"
    override val cannotOpenData = "无法打开本地数据"
    override val backToLibraries = "返回笔记库列表"

    override val account = "账户"
    override val username = "用户名"
    override val email = "邮箱"
    override val role = "角色"
    override val admin = "管理员"
    override val user = "用户"
    override fun syncSection(library: String) = "同步 · $library"
    override val server = "服务器"
    override val status = "状态"
    override val thisDevice = "本设备"
    override val syncNow = "立即同步"
    override val openAdmin = "打开管理后台"
    override val signingOut = "正在退出…"
    override val signOut = "退出登录"
    override val signOutHint = "退出后，本设备上这个笔记库的副本会被删除；服务器上的数据不受影响。重新登录后会再次下载。"
    override val signOutTitle = "退出登录？"
    override fun signOutUnsynced(n: Int) = "还有 $n 条更改没有同步到服务器，退出后会丢失。"
    override val signOutMessage = "这台设备的登录令牌会被撤销，本地数据会被删除。"

    override fun uploadRefused(name: String, message: String) = "「$name」未能上传：$message"
    override val conflictTitle = "内容冲突"
    override fun conflictMessage(title: String) = "「$title」在其他设备上也改动了同一处。服务器保留了它的版本，你的修改已另存为冲突副本。"
    override val openCopy = "打开副本"
    override val newNote = "新建笔记"
    override val newFolder = "新建笔记本"
    override val newSubfolder = "新建子笔记本"
    override val renameFolder = "重命名笔记本"
    override val deleteFolderTitle = "删除笔记本？"
    override fun deleteFolderMessage(name: String) = "「$name」的子笔记本会一起删除，其中的笔记移到回收站。"
    override val renameNote = "重命名笔记"
    override val emptyTrashTitle = "清空回收站？"
    override val emptyTrashMessage = "回收站中的全部笔记将被永久删除，此操作无法撤销。"
    override val emptyTrash = "清空回收站"
    override val switchLibrary = "切换笔记库"
    override val accountAndSync = "账户与同步…"
    override val turnOnSyncEllipsis = "开启多端同步…"
    override val mediaLibrary = "媒体库"
    override val manageLibraries = "管理笔记库…"
    override val searchNotes = "搜索笔记"
    override val sortAndNew = "排序与新建"
    override fun showMore(next: Int, hidden: Int) = "再显示 $next 篇（共 $hidden 篇未显示）"
    override val collapse = "收起"
    override val expand = "展开"
    override val sort = "排序"
    override val sortUpdated = "更新时间"
    override val sortCreated = "创建时间"
    override val sortTitle = "标题"
    override val createFolderRow = "新建笔记本"
    override val editTagEllipsis = "编辑标签…"
    override val pin = "置顶"
    override val unpin = "取消置顶"
    override val moveToEllipsis = "移动到…"
    override val moveTo = "移动到"
    override val duplicateNote = "复制笔记"
    override val duplicateSuffix = " 副本"
    override val moveToTrash = "移到回收站"
    override val noNotebook = "无笔记本"
    override fun noResults(query: String) = "没有找到「$query」"

    override val share = "分享"
    override val outline = "大纲"
    override val tagsEllipsis = "标签…"
    override val backlinks = "反向链接"
    override fun backlinksMenu(n: Int) = "反向链接（$n）"
    override fun backlinksCount(n: Int) = "$n 条反向链接"
    override val history = "历史版本"
    override val noteInTrash = "这篇笔记在回收站中"
    override val formatPhoto = "图片"
    override val formatCamera = "拍照"
    override val formatAttachment = "附件"
    override val formatHeading = "标题"
    override val formatBold = "加粗"
    override val formatItalic = "斜体"
    override val formatTask = "待办"
    override val formatList = "列表"
    override val formatOrdered = "编号"
    override val formatQuote = "引用"
    override val formatCode = "代码"
    override val formatWikiLink = "双链"
    override val formatLink = "链接"
    override val formatStrike = "删除线"
    override val undo = "撤销"
    override val hideKeyboard = "收起键盘"
    override val noHeadings = "还没有标题"
    override val noHeadingsHint = "以 # 开头的行会出现在这里。"
    override val noBacklinks = "还没有笔记链接到这里"
    override fun noBacklinksHint(title: String) = "在其他笔记中输入 [[$title]] 即可链接到这篇笔记。"

    override fun tagColor(color: String) = when (color) {
        "green" -> "绿"
        "blue" -> "蓝"
        "amber" -> "琥珀"
        "pink" -> "粉"
        "purple" -> "紫"
        "gray" -> "灰"
        else -> "无"
    }
    override val searchOrCreateTag = "搜索或创建标签"
    override fun maxTags(n: Int) = "一篇笔记最多 $n 个标签"
    override fun createTag(name: String) = "创建「$name」"
    override val editTag = "编辑标签"
    override val noTags = "还没有标签。输入名称即可创建。"
    override val tagExists = "已有同名标签"
    override val color = "颜色"
    override val deleteTag = "删除标签"
    override val deleteTagTitle = "删除标签？"
    override fun deleteTagMessage(name: String) = "「#$name」会从所有笔记上移除，笔记本身不受影响。"

    override val restoreVersion = "恢复此版本"
    override val localNoHistory = "本地笔记库没有历史版本"
    override val localNoHistoryHint = "历史版本保存在同步服务器上。"
    override val historyLoadFailed = "无法载入历史版本"
    override val noHistory = "暂无历史版本"
    override val noHistoryHint = "编辑并同步后，这里会出现保存过的版本。"
    override val unknownDevice = "未知设备"
    override val currentVersion = "当前版本"
    override val restoreVersionTitle = "恢复此版本？"
    override val restoreVersionMessage = "当前内容会被替换，但仍会保留在历史版本中。"
    override val restoredVersion = "已恢复为历史版本"

    override val offlineNoPublicLinks = "离线中，附件没有替换为公网链接"
    override val shareNote = "分享笔记"
    override val localNoShare = "本地笔记库不能生成公开链接，可以复制或导出笔记。"
    override val shareNeedsNetwork = "分享链接需要联网。"
    override val publicLink = "公开链接"
    override val publicLinkHint = "拿到链接和访问密码的人可以阅读这篇笔记"
    override val legacyShare = "这个链接是在要求密码之前创建的，设置密码后才能继续访问。"
    override val needPassword = "为保护你的隐私，公开链接必须设置至少 6 位的访问密码。"
    override val newPasswordPlaceholder = "新密码（至少 6 位）"
    override val generate = "随机生成"
    override val createLink = "生成链接"
    override val copyLink = "复制链接"
    override val shareExpired = "分享已过期"
    override val copyPassword = "复制密码"
    override val passwordSet = "已设置访问密码"
    override val changePassword = "修改密码"
    override val copyLinkAndPassword = "复制链接和密码"
    override val expires = "有效期"
    override fun views(n: Int) = "$n 次访问"
    override val copyOrExport = "复制或导出"
    override val copyAsHint = "附件会自动公开并替换为公网链接，方便粘贴到博客、公众号等地方。"
    override val copyAsOffline = "离线时附件不会替换为公网链接。"
    override val copyMarkdown = "复制 Markdown"
    override val sendToApp = "发送到其他应用"
    override val exportMd = "导出 .md 文件"
    override val neverExpires = "永久有效"
    override fun until(date: String) = "至 $date"
    override fun days(n: Int) = "$n 天"
    override fun shareMessage(title: String, link: String, password: String) = "$title\n链接：$link\n密码：$password"
    override fun sharePasswordMin(n: Int) = "密码至少 $n 位"
    override fun sharePasswordMax(n: Int) = "密码最多 $n 字节"

    override val fileTooLarge = "文件超过服务器的上传上限"
    override fun queuedUpload(name: String) = "离线中，「$name」将在联网后上传"
    override val duplicateMerged = "重复文件已合并"
    override fun cannotAddFile(error: String) = "无法添加文件：$error"
    override val noCamera = "没有可用的相机应用"
    override val noFilePicker = "没有可用的文件选择器"
    override val upload = "上传"
    override val photoOrVideo = "图片或视频"
    override val camera = "拍照"
    override val file = "文件"
    override val searchFiles = "搜索文件名"
    override val mediaOffline = "离线中，显示的是本机同步下来的文件信息，修改需要联网。"
    override fun orphanBanner(n: Int, days: Int) = "$n 个文件没有被任何笔记引用，将在 $days 天后自动移入回收站。"
    override fun orphanBannerNoDays(n: Int) = "$n 个文件没有被任何笔记引用。"
    override val mediaTrashEmpty = "回收站是空的"
    override val mediaEmpty = "还没有文件。在笔记中插入图片或附件即可自动加入媒体库。"
    override val isPublic = "已公开"
    override fun refs(n: Int) = "被 $n 篇引用"
    override val unreferenced = "未引用"
    override val cannotGetFile = "无法取得文件，请检查网络"
    override val type = "类型"
    override val size = "大小"
    override val uploadedAt = "上传于"
    override val publicUrl = "公网链接"
    override val publicPlaceholder = "已公开（/r/…）"
    override val trashedOrphan = "未引用，已自动移入"
    override val trashedManual = "已移入"
    override val noNotesReference = "没有笔记引用它"
    override fun referencedBy(n: Int) = "被 $n 篇笔记引用"
    override val copyReference = "复制引用"
    override val copyPublicUrl = "复制公网链接"
    override val makePublicUrl = "生成公网链接"
    override val revokePublicUrl = "撤销公网链接"
    override val shareOriginal = "分享或保存原文件"
    override val localFilesHint = "本地笔记库的文件只保存在这台设备上。"
    override val publicFilesHint = "公开的文件不再受登录保护，拿到链接的人都能打开。"
    override val renameFile = "重命名文件"
    override val purgeFileTitle = "永久删除文件？"
    override fun purgeFileMessage(name: String) = "「$name」将被永久删除，引用它的笔记会显示为缺失。此操作无法撤销。"
    override val filterAll = "全部"
    override val filterImages = "图片"
    override val filterVideo = "视频"
    override val filterAudio = "音频"
    override val filterFiles = "文件"
    override val attachment = "附件"
    override val fileNotFound = "文件不存在"
    override val nameEmpty = "名称不能为空"
    override val localNoPublicLink = "本地笔记库没有公网链接"

    override val wrongCredentials = "用户名或密码错误"
    override val signInTitle = "登录常青笔记"
    override val turnOnSyncTitle = "开启多端同步"
    override val continueMigration = "继续迁移"
    override val syncNode = "同步节点"
    override val regionCn = "中国大陆"
    override val regionGlobal = "全球"
    override fun officialRegion(region: String) = "常青笔记 · $region"
    override val customServerEllipsis = "自定义服务器…"
    override val nodeUnreachable = "暂时无法连接这个节点"
    override val signInHint = "笔记先保存在本机，联网时自动同步到你的所有设备。"
    override fun promoteHint(name: String) = "注册一个新账户，「$name」会上传到云端，之后在所有设备上同步。"
    override val register = "注册"
    override val login = "登录"
    override val firstAdmin = "这台服务器还没有账户。你创建的第一个账户将成为管理员。"
    override val registrationClosed = "这台服务器没有开放注册。"
    override val registerAndSync = "注册并开启同步"
    override val promoteNote = "本地笔记库只能迁移到新注册的账户。已有账户的话，可以先登录，再在网页版或桌面端的「设置 → 导入」中导入本笔记库导出的内容。"
    override fun migrationRejected(n: Int) = "$n 项未能迁移，原内容仍保留在本地笔记库中。"
    override fun migrationFilesFailed(n: Int) = "$n 个文件未能上传，原文件仍保留在本地笔记库中。"
    override fun migrationInterrupted(error: String) = "迁移中断：$error。账户已经创建，点「继续迁移」会从中断处继续，已上传的内容不会重复。"
    override val preparing = "正在准备…"
    override fun uploadingFiles(done: Int, total: Int) = "正在上传文件 $done/$total"
    override fun uploadingNotes(done: Int, total: Int) = "正在上传笔记 $done/$total"
    override val migrationDone = "迁移完成，正在打开…"
    override val customServer = "自定义服务器"
    override val insecureWarning = "使用 http 连接时，密码和笔记以明文传输，请只在可信的局域网内使用。"
    override val customServerHint = "连接你自己或团队部署的常青笔记服务器。"
    override val useServer = "使用这个服务器"
    override val cannotConnect = "无法连接服务器"
    override val httpWarning = "使用 http 连接，密码和笔记以明文传输。"
    override val createAccount = "创建账户"
    override val usernameOrEmail = "用户名或邮箱"
    override val emailOptional = "邮箱（可选）"
    override val displayNameOptional = "显示名（可选）"
    override val password = "密码"
    override val passwordMin8 = "密码（至少 8 位）"

    // Updates
    override fun aboutNewer(newer: String) = "关于常青笔记 · 有新版本 $newer"
    override fun aboutVersion(version: String) = "关于常青笔记 · $version"
    override val aboutAndUpdates = "关于与检查更新…"
    override val updateAvailable = "发现新版本"
    override fun versionReleased(version: String) = "常青笔记 $version 已发布"
    override fun currentVersionIs(version: String) = "当前版本 $version"
    override fun downloading(progress: String) = "正在下载… $progress"
    override val savingAndInstalling = "正在保存笔记并打开安装程序…"
    override val updateReady = "新版本已下载并校验，按系统提示完成安装即可，笔记会先保存。"
    override val debugUpdateHint = "调试版无法直接更新，请前往官网下载正式版。"
    override val webUpdateHint = "请前往官网下载新版本。"
    override val updateHint = "下载完成后由系统安装，笔记会先保存。首次更新需要允许常青笔记安装应用。"
    override fun downloadFailed(error: String) = "下载失败：$error。可以重试或前往官网下载。"
    override fun installFailed(error: String) = "安装失败：$error。可以重试或前往官网下载。"
    override val downloadInBackground = "后台下载"
    override val installing = "正在安装…"
    override val installNow = "立即安装"
    override val downloadFromWebsite = "前往官网下载"
    override val updateNow = "立即更新"
    override val cancelDownload = "取消下载"
    override val websiteDownload = "官网下载"
    override val skipVersion = "跳过此版本"
    override val later = "稍后"
    override val aboutTagline = "本地优先的 Markdown 笔记。离线也能写，联网后自动同步。"
    override val version = "版本"
    override val checkForUpdates = "检查更新"
    override val checking = "正在检查…"
    override fun downloadingVersion(version: String) = "正在下载 $version…"
    override fun newVersion(version: String) = "有新版本 $version"
    override val checkFailed = "检查失败"
    override val upToDate = "已是最新版本"
    override val anonymousStats = "匿名使用统计"
    override val anonymousStatsHint = "检查更新时附带一个随机的安装编号（与账号无关），帮助我们了解有多少设备在使用常青笔记。不包含笔记内容或个人信息。"
    override val website = "官网 ivynote.net"
    override fun upToDateVersion(version: String) = "已是最新版本（$version）"
    override fun checkUpdateFailed(error: String) = "检查更新失败：$error"
    override val allowInstall = "请允许「常青笔记」安装应用，然后返回点击「立即安装」"
    override val apkInvalid = "下载的安装包已失效，请重新下载"
    override val signatureMismatch = "新版本与已安装的应用签名不一致，请前往官网下载安装"
    override val noStorage = "存储空间不足"
    override val installBlocked = "安装被系统阻止"
    override val installIncomplete = "安装未完成"

    override fun unreachable(detail: String) = "无法连接服务器：$detail"
    override val notIvynote = "这个地址不是常青笔记服务器"
    override val tooManyAttempts = "尝试次数过多，请 15 分钟后再试"
    override fun serverUnavailable(status: Int) = "服务器暂时不可用（$status）"
    override fun requestFailedStatus(status: Int) = "请求失败（$status）"
    override val accountNotEmpty = "这个账户里已经有笔记。本地笔记库只能迁移到新注册的空账户。"
    override val accountUnavailable = "账户不可用"
    override val sessionExpired = "登录已过期，请重新登录"
    override val usernameRule = "用户名为 2–32 位字母、数字、_ . -，以字母或数字开头"
    override val passwordTooShort = "密码至少 8 个字符"
    override val passwordTooLong = "密码最多 72 字节"
    override val serverEmpty = "请填写服务器地址"
    override val serverInvalid = "服务器地址无效"
    override fun unsupportedScheme(scheme: String) = "不支持的协议：$scheme"
    override fun insecureRemote(host: String) = "$host 不是局域网地址，必须使用 https://"
}

object EnStrings : Strings {
    override val locale: Locale = Locale.ENGLISH

    /** "1 note", "2 notes". */
    private fun count(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

    override val appName = "Ivynote"
    override val slogan = "Local-first Markdown notes"
    override val tagline = "Like ivy, your notes stay connected and evergreen."
    override val name = "Name"
    override val save = "Save"
    override val cancel = "Cancel"
    override val create = "Create"
    override val delete = "Delete"
    override val remove = "Remove"
    override val rename = "Rename"
    override val open = "Open"
    override val retry = "Retry"
    override val done = "Done"
    override val back = "Back"
    override val more = "More"
    override val clear = "Clear"
    override val ok = "OK"
    override val copied = "Copied"
    override val restore = "Restore"
    override val deleteForever = "Delete forever"
    override val untitled = "Untitled"
    override val unnamed = "Unnamed"
    override val note = "Note"
    override val notes = "Notes"
    override val tags = "Tags"
    override val trash = "Trash"
    override val pinned = "Pinned"
    override val needsNetwork = "Needs a connection"
    override val requestFailed = "Request failed"
    override val cannotReadFile = "Could not read the file"

    override val language = "Language · 语言"
    override val followSystem = "System default"
    override fun followSystemWith(language: String) = "System default ($language)"
    override val languageAccountHint = "Saved in your account, on every device signed in to it."
    override val languageDeviceHint = "For this device only. Once you sign in to a synced account, the account’s language applies."

    override val justNow = "just now"
    override fun yesterday(time: String) = "Yesterday $time"
    override fun weekday(dayOfWeek: Int) = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")[(dayOfWeek - 1).mod(7)]
    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    override fun monthDay(month: Int, day: Int) = "${months[(month - 1).mod(12)]} $day"
    override fun yearMonthDay(year: Int, month: Int, day: Int) = "${months[(month - 1).mod(12)]} $day, $year"
    override val dateTimePattern = "MMM d, yyyy HH:mm"
    override val monthDayPattern = "MMM d"

    override val localUnsynced = "On this device only · not synced"
    override val downloadingNotes = "Downloading notes…"
    override val syncing = "Syncing…"
    override val offline = "Offline"
    override fun offlinePending(n: Int) = "Offline · ${count(n, "change")} to sync"
    override val syncError = "Sync error"
    override fun pending(n: Int) = "${count(n, "change")} to sync"
    override fun synced(ago: String) = "Synced · $ago"
    override val neverSynced = "Not synced yet"
    override fun blocked(n: Int) = "${count(n, "change")} refused by the server"

    override val libraries = "Libraries"
    override val welcome = "Local-first Markdown notes that sync across your phone, computer and the web."
    override val pickLibrary = "Pick a library to open"
    override val connect = "Sign in to sync"
    override val connectHint = "Sync across devices, on the official service or your own server"
    override val newLocal = "New local library"
    override val newLocalHint = "Stays on this device, no account, ready right away"
    override val newLocalMessage = "Notes stay on this device and do not sync."
    override val canConvert = "A local library can turn on sync at any time: create an account and its content is uploaded."
    override val startCloud = "Get started · Sync everywhere"
    override val startCloudHint = "Sign in or sign up; notes sync across your phone, computer and the web"
    override val localOnly = "Use on this device only, without an account"
    override val defaultLibraryName = "My notes"
    override val renameLibrary = "Rename library"
    override val removeLibrary = "Remove library"
    override fun removeLocalMessage(name: String) = "All notes in “$name” will be deleted for good. This cannot be undone."
    override fun removeServerMessage(name: String) = "The copy of “$name” on this device and its sign-in will be removed. Notes on the server are not affected, but changes not synced yet are lost."
    override val turnOnSync = "Turn on sync"
    override val migratedHint = "Moved to the cloud; remove it once you have checked"
    override val cannotOpenData = "Could not open the local data"
    override val backToLibraries = "Back to libraries"

    override val account = "Account"
    override val username = "Username"
    override val email = "Email"
    override val role = "Role"
    override val admin = "Admin"
    override val user = "User"
    override fun syncSection(library: String) = "Sync · $library"
    override val server = "Server"
    override val status = "Status"
    override val thisDevice = "This device"
    override val syncNow = "Sync now"
    override val openAdmin = "Open admin console"
    override val signingOut = "Signing out…"
    override val signOut = "Sign out"
    override val signOutHint = "Signing out deletes this library’s copy on this device; the data on the server is not affected and downloads again when you sign back in."
    override val signOutTitle = "Sign out?"
    override fun signOutUnsynced(n: Int) = "${count(n, "change")} not synced to the server yet will be lost."
    override val signOutMessage = "This device’s sign-in token is revoked and the local data deleted."

    override fun uploadRefused(name: String, message: String) = "“$name” could not be uploaded: $message"
    override val conflictTitle = "Conflict"
    override fun conflictMessage(title: String) = "“$title” was changed in the same place on another device. The server kept that version; your edit was saved as a conflict copy."
    override val openCopy = "Open copy"
    override val newNote = "New note"
    override val newFolder = "New notebook"
    override val newSubfolder = "New sub-notebook"
    override val renameFolder = "Rename notebook"
    override val deleteFolderTitle = "Delete notebook?"
    override fun deleteFolderMessage(name: String) = "The sub-notebooks of “$name” are deleted too; its notes go to the trash."
    override val renameNote = "Rename note"
    override val emptyTrashTitle = "Empty trash?"
    override val emptyTrashMessage = "All notes in the trash will be deleted for good. This cannot be undone."
    override val emptyTrash = "Empty trash"
    override val switchLibrary = "Switch library"
    override val accountAndSync = "Account and sync…"
    override val turnOnSyncEllipsis = "Turn on sync…"
    override val mediaLibrary = "Media library"
    override val manageLibraries = "Manage libraries…"
    override val searchNotes = "Search notes"
    override val sortAndNew = "Sort and new"
    override fun showMore(next: Int, hidden: Int) = "Show $next more ($hidden not shown)"
    override val collapse = "Collapse"
    override val expand = "Expand"
    override val sort = "Sort"
    override val sortUpdated = "Last updated"
    override val sortCreated = "Date created"
    override val sortTitle = "Title"
    override val createFolderRow = "New notebook"
    override val editTagEllipsis = "Edit tag…"
    override val pin = "Pin"
    override val unpin = "Unpin"
    override val moveToEllipsis = "Move to…"
    override val moveTo = "Move to"
    override val duplicateNote = "Duplicate"
    override val duplicateSuffix = " copy"
    override val moveToTrash = "Move to trash"
    override val noNotebook = "No notebook"
    override fun noResults(query: String) = "Nothing found for “$query”"

    override val share = "Share"
    override val outline = "Outline"
    override val tagsEllipsis = "Tags…"
    override val backlinks = "Backlinks"
    override fun backlinksMenu(n: Int) = "Backlinks ($n)"
    override fun backlinksCount(n: Int) = count(n, "backlink")
    override val history = "History"
    override val noteInTrash = "This note is in the trash"
    override val formatPhoto = "Photo"
    override val formatCamera = "Take photo"
    override val formatAttachment = "Attachment"
    override val formatHeading = "Heading"
    override val formatBold = "Bold"
    override val formatItalic = "Italic"
    override val formatTask = "Task"
    override val formatList = "List"
    override val formatOrdered = "Numbered list"
    override val formatQuote = "Quote"
    override val formatCode = "Code"
    override val formatWikiLink = "Wiki link"
    override val formatLink = "Link"
    override val formatStrike = "Strikethrough"
    override val undo = "Undo"
    override val hideKeyboard = "Hide keyboard"
    override val noHeadings = "No headings yet"
    override val noHeadingsHint = "Lines starting with # show up here."
    override val noBacklinks = "No notes link here yet"
    override fun noBacklinksHint(title: String) = "Type [[$title]] in another note to link to this one."

    override fun tagColor(color: String) = when (color) {
        "green" -> "Green"
        "blue" -> "Blue"
        "amber" -> "Amber"
        "pink" -> "Pink"
        "purple" -> "Purple"
        "gray" -> "Gray"
        else -> "None"
    }
    override val searchOrCreateTag = "Search or create a tag"
    override fun maxTags(n: Int) = "A note can have at most $n tags"
    override fun createTag(name: String) = "Create “$name”"
    override val editTag = "Edit tag"
    override val noTags = "No tags yet. Type a name to create one."
    override val tagExists = "A tag with this name exists"
    override val color = "Color"
    override val deleteTag = "Delete tag"
    override val deleteTagTitle = "Delete tag?"
    override fun deleteTagMessage(name: String) = "“#$name” is removed from all notes; the notes themselves are not affected."

    override val restoreVersion = "Restore this version"
    override val localNoHistory = "Local libraries have no history"
    override val localNoHistoryHint = "Saved versions are kept on the sync server."
    override val historyLoadFailed = "Could not load the history"
    override val noHistory = "No saved versions yet"
    override val noHistoryHint = "Versions show up here after you edit and sync."
    override val unknownDevice = "Unknown device"
    override val currentVersion = "Current version"
    override val restoreVersionTitle = "Restore this version?"
    override val restoreVersionMessage = "The current text is replaced, but stays in the history."
    override val restoredVersion = "Restored the saved version"

    override val offlineNoPublicLinks = "Offline: attachments were not replaced with public links"
    override val shareNote = "Share note"
    override val localNoShare = "Local libraries cannot make public links; you can copy or export the note."
    override val shareNeedsNetwork = "Sharing needs a connection."
    override val publicLink = "Public link"
    override val publicLinkHint = "Anyone with the link and the password can read this note"
    override val legacyShare = "This link was created before passwords were required. Set one to reopen it."
    override val needPassword = "To protect your privacy, a public link needs a password of at least 6 characters."
    override val newPasswordPlaceholder = "New password (6+ characters)"
    override val generate = "Generate"
    override val createLink = "Create link"
    override val copyLink = "Copy link"
    override val shareExpired = "This share has expired"
    override val copyPassword = "Copy password"
    override val passwordSet = "Password set"
    override val changePassword = "Change password"
    override val copyLinkAndPassword = "Copy link and password"
    override val expires = "Expires"
    override fun views(n: Int) = count(n, "view")
    override val copyOrExport = "Copy or export"
    override val copyAsHint = "Attachments are made public and replaced with their public links, ready to paste into a blog or newsletter."
    override val copyAsOffline = "Offline, attachments are not replaced with public links."
    override val copyMarkdown = "Copy Markdown"
    override val sendToApp = "Send to another app"
    override val exportMd = "Export .md file"
    override val neverExpires = "Never"
    override fun until(date: String) = "Until $date"
    override fun days(n: Int) = count(n, "day")
    override fun shareMessage(title: String, link: String, password: String) = "$title\nLink: $link\nPassword: $password"
    override fun sharePasswordMin(n: Int) = "At least $n characters"
    override fun sharePasswordMax(n: Int) = "At most $n bytes"

    override val fileTooLarge = "The file is larger than the server allows"
    override fun queuedUpload(name: String) = "Offline: “$name” will upload once you are online"
    override val duplicateMerged = "Duplicate file merged"
    override fun cannotAddFile(error: String) = "Could not add the file: $error"
    override val noCamera = "No camera app available"
    override val noFilePicker = "No file picker available"
    override val upload = "Upload"
    override val photoOrVideo = "Photo or video"
    override val camera = "Take photo"
    override val file = "File"
    override val searchFiles = "Search file names"
    override val mediaOffline = "Offline: showing the file details synced to this device; changes need a connection."
    override fun orphanBanner(n: Int, days: Int) = "${count(n, "file")} not used by any note will move to the trash in ${count(days, "day")}."
    override fun orphanBannerNoDays(n: Int) = "${count(n, "file")} not used by any note."
    override val mediaTrashEmpty = "The trash is empty"
    override val mediaEmpty = "No files yet. Images and attachments added to notes show up here."
    override val isPublic = "Public"
    override fun refs(n: Int) = "In ${count(n, "note")}"
    override val unreferenced = "Unused"
    override val cannotGetFile = "Could not get the file; check your connection"
    override val type = "Type"
    override val size = "Size"
    override val uploadedAt = "Uploaded"
    override val publicUrl = "Public link"
    override val publicPlaceholder = "Public (/r/…)"
    override val trashedOrphan = "Unused, moved automatically"
    override val trashedManual = "Moved"
    override val noNotesReference = "No note uses it"
    override fun referencedBy(n: Int) = "Used in ${count(n, "note")}"
    override val copyReference = "Copy reference"
    override val copyPublicUrl = "Copy public link"
    override val makePublicUrl = "Make public link"
    override val revokePublicUrl = "Revoke public link"
    override val shareOriginal = "Share or save the original"
    override val localFilesHint = "Files of a local library stay on this device."
    override val publicFilesHint = "Public files are no longer protected by sign-in: anyone with the link can open them."
    override val renameFile = "Rename file"
    override val purgeFileTitle = "Delete file forever?"
    override fun purgeFileMessage(name: String) = "“$name” will be deleted for good; notes using it will show it as missing. This cannot be undone."
    override val filterAll = "All"
    override val filterImages = "Images"
    override val filterVideo = "Video"
    override val filterAudio = "Audio"
    override val filterFiles = "Files"
    override val attachment = "attachment"
    override val fileNotFound = "File not found"
    override val nameEmpty = "The name cannot be empty"
    override val localNoPublicLink = "Local libraries have no public links"

    override val wrongCredentials = "Wrong username or password"
    override val signInTitle = "Sign in to Ivynote"
    override val turnOnSyncTitle = "Turn on sync"
    override val continueMigration = "Continue"
    override val syncNode = "Sync service"
    override val regionCn = "Mainland China"
    override val regionGlobal = "Global"
    override fun officialRegion(region: String) = "Ivynote · $region"
    override val customServerEllipsis = "Custom server…"
    override val nodeUnreachable = "This service cannot be reached right now"
    override val signInHint = "Notes are saved on this device first and sync to all your devices when online."
    override fun promoteHint(name: String) = "Create a new account: “$name” is uploaded to the cloud and syncs on all your devices."
    override val register = "Sign up"
    override val login = "Sign in"
    override val firstAdmin = "This server has no accounts yet. The first account you create becomes its admin."
    override val registrationClosed = "This server does not accept sign-ups."
    override val registerAndSync = "Sign up and turn on sync"
    override val promoteNote = "A local library can only move to a newly created account. With an existing account, sign in and import this library’s export in Settings → Import on the web or desktop app."
    override fun migrationRejected(n: Int) = "${count(n, "item")} could not be moved; the local library still has everything."
    override fun migrationFilesFailed(n: Int) = "${count(n, "file")} could not be uploaded; the local library still has the originals."
    override fun migrationInterrupted(error: String) = "Moving stopped: $error. The account exists; tap Continue to resume where it stopped, without uploading anything twice."
    override val preparing = "Preparing…"
    override fun uploadingFiles(done: Int, total: Int) = "Uploading files $done/$total"
    override fun uploadingNotes(done: Int, total: Int) = "Uploading notes $done/$total"
    override val migrationDone = "Done, opening…"
    override val customServer = "Custom server"
    override val insecureWarning = "Over http, passwords and notes travel unencrypted. Use it only on a local network you trust."
    override val customServerHint = "Connect to an Ivynote server you or your team run."
    override val useServer = "Use this server"
    override val cannotConnect = "Cannot reach the server"
    override val httpWarning = "Connected over http: passwords and notes travel unencrypted."
    override val createAccount = "Create account"
    override val usernameOrEmail = "Username or email"
    override val emailOptional = "Email (optional)"
    override val displayNameOptional = "Display name (optional)"
    override val password = "Password"
    override val passwordMin8 = "Password (8+ characters)"

    // Updates
    override fun aboutNewer(newer: String) = "About Ivynote · new version $newer"
    override fun aboutVersion(version: String) = "About Ivynote · $version"
    override val aboutAndUpdates = "About and updates…"
    override val updateAvailable = "New version available"
    override fun versionReleased(version: String) = "Ivynote $version is out"
    override fun currentVersionIs(version: String) = "Current version $version"
    override fun downloading(progress: String) = "Downloading… $progress"
    override val savingAndInstalling = "Saving notes and opening the installer…"
    override val updateReady = "The new version is downloaded and verified. Follow the system prompt to install; your notes are saved first."
    override val debugUpdateHint = "Debug builds cannot update in place; download the release from the website."
    override val webUpdateHint = "Download the new version from the website."
    override val updateHint = "Once downloaded, the system installs it; your notes are saved first. The first update asks you to allow Ivynote to install apps."
    override fun downloadFailed(error: String) = "Download failed: $error. Try again or download from the website."
    override fun installFailed(error: String) = "Install failed: $error. Try again or download from the website."
    override val downloadInBackground = "Download in background"
    override val installing = "Installing…"
    override val installNow = "Install now"
    override val downloadFromWebsite = "Download from website"
    override val updateNow = "Update now"
    override val cancelDownload = "Cancel download"
    override val websiteDownload = "Website"
    override val skipVersion = "Skip this version"
    override val later = "Later"
    override val aboutTagline = "Local-first Markdown notes. Write offline; they sync once you are online."
    override val version = "Version"
    override val checkForUpdates = "Check for updates"
    override val checking = "Checking…"
    override fun downloadingVersion(version: String) = "Downloading $version…"
    override fun newVersion(version: String) = "New version $version"
    override val checkFailed = "Check failed"
    override val upToDate = "Up to date"
    override val anonymousStats = "Anonymous usage statistics"
    override val anonymousStatsHint = "Update checks carry a random install id (not tied to your account) so we know how many devices use Ivynote. No note content or personal data is sent."
    override val website = "Website ivynote.net"
    override fun upToDateVersion(version: String) = "Up to date ($version)"
    override fun checkUpdateFailed(error: String) = "Could not check for updates: $error"
    override val allowInstall = "Allow Ivynote to install apps, then come back and tap Install now"
    override val apkInvalid = "The downloaded package is no longer valid; download it again"
    override val signatureMismatch = "The new version is signed differently from the installed app; download it from the website"
    override val noStorage = "Not enough storage"
    override val installBlocked = "The system blocked the install"
    override val installIncomplete = "The install did not finish"

    override fun unreachable(detail: String) = "Cannot reach the server: $detail"
    override val notIvynote = "This address is not an Ivynote server"
    override val tooManyAttempts = "Too many attempts; try again in 15 minutes"
    override fun serverUnavailable(status: Int) = "The server is unavailable right now ($status)"
    override fun requestFailedStatus(status: Int) = "Request failed ($status)"
    override val accountNotEmpty = "This account already has notes. A local library can only move to a new, empty account."
    override val accountUnavailable = "This account is not available"
    override val sessionExpired = "Your session has expired; please sign in again"
    override val usernameRule = "Usernames have 2–32 letters, digits, _ . or -, starting with a letter or digit"
    override val passwordTooShort = "The password needs at least 8 characters"
    override val passwordTooLong = "The password can be at most 72 bytes"
    override val serverEmpty = "Enter the server address"
    override val serverInvalid = "The server address is not valid"
    override fun unsupportedScheme(scheme: String) = "Unsupported protocol: $scheme"
    override fun insecureRemote(host: String) = "$host is not on the local network; use https://"
}
