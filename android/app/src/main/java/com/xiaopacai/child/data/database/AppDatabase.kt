package com.xiaopacai.child.data.database

import android.content.Context
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteOpenHelper

/**
 * [TASK-D1-02] 小趴菜本地加密数据库
 *
 * 使用 SQLCipher（BSD 社区版）提供 AES-256 加密存储。
 * 所有儿童端数据（使用记录、策略配置、公告缓存）均加密落盘。
 *
 * 数据库版本管理：
 * - Version 1：初始表结构（时长记录、策略缓存、公告缓存、配对信息）
 * - Version 2：[TASK-ROLE-P1] 新增家长端表（device_registry/policies/announcements/parent_usage_summary）
 * - Version 3：[TASK-OPT-12-P1] 新增应用分类表 app_category；announcements 扩展 requires_ack/acknowledged_at 列
 * - 升级时通过 onUpgrade() 执行迁移 SQL
 */
class AppDatabase private constructor(
    context: Context,
    name: String,
    passphrase: ByteArray
) : SQLiteOpenHelper(context, name, null, DATABASE_VERSION) {

    // SQLCipher 初始化（必须在使用任何数据库操作前调用）
    init {
        SQLiteDatabase.loadLibs(context)
    }

    override fun onCreate(db: SQLiteDatabase?) {
        db ?: return

        // === 表1：应用使用时长记录 ===
        // 记录每个应用每天的累计使用时长（精确到分钟）
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS usage_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                package_name TEXT NOT NULL,           -- 应用包名
                app_name TEXT NOT NULL DEFAULT '',    -- 应用名称（便于展示）
                date TEXT NOT NULL,                   -- 日期（yyyy-MM-dd）
                total_minutes INTEGER NOT NULL DEFAULT 0,  -- 累计使用分钟数
                last_used_at INTEGER NOT NULL DEFAULT 0,   -- 最后一次使用时间戳
                category TEXT NOT NULL DEFAULT 'other',     -- 分类：game/social/video/study/other
                sync_status INTEGER NOT NULL DEFAULT 0,     -- 同步状态：0=未同步 1=已同步
                created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
                updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
                UNIQUE(package_name, date)
            )
        """.trimIndent())

        // === 表2：策略配置缓存 ===
        // 从家长端同步下来的策略本地缓存（断网时使用缓存的策略）
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS policy_cache (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                policy_type TEXT NOT NULL,              -- 策略类型：daily_limit/sleep_time/whitelist/blacklist
                policy_data TEXT NOT NULL,              -- JSON 格式的策略数据
                version INTEGER NOT NULL DEFAULT 1,    -- 策略版本号（用于增量同步）
                applied_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
                UNIQUE(policy_type)
            )
        """.trimIndent())

        // === 表3：公告缓存 ===
        // 从家长端同步的公告本地缓存
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS announcements (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                announcement_id TEXT NOT NULL UNIQUE,   -- 公告唯一 ID（家长端生成）
                title TEXT NOT NULL DEFAULT '',         -- 公告标题
                content TEXT NOT NULL DEFAULT '',       -- 公告正文
                priority INTEGER NOT NULL DEFAULT 0,   -- 优先级：0=普通 1=重要 2=紧急
                is_read INTEGER NOT NULL DEFAULT 0,     -- 是否已读：0=未读 1=已读
                requires_ack INTEGER NOT NULL DEFAULT 0,-- [TASK-OPT-12-P1] 是否需要家长确认：0=否 1=是（紧急公告）
                acknowledged_at INTEGER NOT NULL DEFAULT 0, -- [TASK-OPT-12-P1] 家长确认回执时间戳（0=未确认）
                created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
                expires_at INTEGER NOT NULL DEFAULT 0   -- 过期时间戳（0=永不过期）
            )
        """.trimIndent())

        // === 表4：配对信息 ===
        // 记录与家长端的 P2P 配对信息（证书指纹、IP 等）
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS pairing_info (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                parent_id TEXT NOT NULL UNIQUE,          -- 家长端唯一标识
                parent_name TEXT NOT NULL DEFAULT '',    -- 家长端设备名称
                cert_fingerprint TEXT NOT NULL DEFAULT '',-- 证书指纹（防中间人）
                last_ip TEXT NOT NULL DEFAULT '',        -- 最后已知 IP 地址
                last_connected_at INTEGER NOT NULL DEFAULT 0,  -- 最后连接时间
                is_active INTEGER NOT NULL DEFAULT 1     -- 是否活跃配对
            )
        """.trimIndent())

        // === 表5：每日使用汇总 ===
        // 每日总使用时长快照（便于快速查询与报告生成）
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS daily_summary (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT NOT NULL UNIQUE,               -- 日期（yyyy-MM-dd）
                total_minutes INTEGER NOT NULL DEFAULT 0,-- 当日总使用分钟数
                game_minutes INTEGER NOT NULL DEFAULT 0, -- 游戏类分钟数
                study_minutes INTEGER NOT NULL DEFAULT 0,-- 学习类分钟数
                limit_minutes INTEGER NOT NULL DEFAULT 0,-- 当日限额（从策略获取）
                limit_exceeded INTEGER NOT NULL DEFAULT 0,-- 是否超限：0=否 1=是
                stop_mode TEXT NOT NULL DEFAULT 'none'   -- 停用模式：none/partial/full
            )
        """.trimIndent())

        // === 索引（加速查询） ===
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_usage_date ON usage_records(date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_usage_pkg_date ON usage_records(package_name, date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_announcements_read ON announcements(is_read)")

        // ============================================================
        // [TASK-ROLE-P1] 家长端表（V2 新增）
        // ============================================================
        createParentTables(db)

        // ============================================================
        // [TASK-OPT-12-P1] V3 新增表/列（应用分类、公告扩展列）
        // ============================================================
        createV3Tables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        db ?: return
        // [TASK-ROLE-P1] V1 → V2：新增家长端表
        if (oldVersion < 2) {
            createParentTables(db)
        }
        // [TASK-OPT-12-P1] V2 → V3：新增 app_category 表 + announcements 扩展列
        if (oldVersion < 3) {
            createV3Tables(db)
        }
    }

    /**
     * [TASK-ROLE-P1] 创建家长端专用表
     *
     * 这些表在 onCreate (V2+) 和 onUpgrade (V1→V2) 中都会被调用。
     */
    private fun createParentTables(db: SQLiteDatabase) {
        // === 表6：设备注册表 ===
        // 记录已配对的儿童端设备信息
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS device_registry (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL UNIQUE,           -- 儿童端设备唯一标识
                device_name TEXT NOT NULL DEFAULT '',     -- 设备名称（如型号）
                cert_fingerprint TEXT NOT NULL DEFAULT '',-- 证书 SHA-256 指纹（防中间人）
                last_ip TEXT NOT NULL DEFAULT '',         -- 最后已知 IP
                last_connected_at INTEGER NOT NULL DEFAULT 0, -- 最后连接时间戳
                is_active INTEGER NOT NULL DEFAULT 1,     -- 是否活跃（0=已解绑）
                created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
            )
        """.trimIndent())

        // === 表7：家长端策略管理表 ===
        // 家长端创建和管理的策略（独立于儿童端 policy_cache）
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS parent_policies (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                policy_id TEXT NOT NULL UNIQUE,           -- 策略唯一标识（UUID）
                policy_type TEXT NOT NULL,                -- daily_limit/sleep_time/whitelist/blacklist/category_limit
                policy_name TEXT NOT NULL DEFAULT '',     -- 策略名称（供 UI 显示）
                policy_data TEXT NOT NULL,                -- JSON 格式的策略详情
                target_device_id TEXT NOT NULL DEFAULT '',-- 目标设备 ID（空=全局）
                is_active INTEGER NOT NULL DEFAULT 1,     -- 是否启用
                version INTEGER NOT NULL DEFAULT 1,       -- 版本号
                created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
                updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
            )
        """.trimIndent())

        // === 表8：家长端公告管理表 ===
        // 家长端创建的公告（与儿童端 announcements 表独立）
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS parent_announcements (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                announcement_id TEXT NOT NULL UNIQUE,     -- 公告唯一 ID（UUID）
                title TEXT NOT NULL DEFAULT '',           -- 公告标题
                content TEXT NOT NULL DEFAULT '',         -- 公告正文
                priority INTEGER NOT NULL DEFAULT 0,      -- 优先级：0=普通 1=重要 2=紧急
                status TEXT NOT NULL DEFAULT 'draft',     -- 状态：draft/published/revoked
                target_device_id TEXT NOT NULL DEFAULT '',-- 目标设备 ID（空=全局）
                valid_from INTEGER NOT NULL DEFAULT 0,    -- 生效时间戳
                valid_until INTEGER NOT NULL DEFAULT 0,   -- 过期时间戳（0=永不过期）
                created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
                updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
            )
        """.trimIndent())

        // === 表9：使用时长汇总表（家长端视角） ===
        // 汇总来自各儿童端的使用时长上报
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS parent_usage_summary (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,                  -- 儿童端设备 ID
                package_name TEXT NOT NULL,               -- 应用包名
                app_name TEXT NOT NULL DEFAULT '',        -- 应用名称
                date TEXT NOT NULL,                       -- 日期（yyyy-MM-dd）
                total_minutes INTEGER NOT NULL DEFAULT 0, -- 使用分钟数
                category TEXT NOT NULL DEFAULT 'other',   -- 分类：game/social/video/study/other
                sync_status INTEGER NOT NULL DEFAULT 0,   -- 同步状态
                updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
                UNIQUE(device_id, package_name, date)
            )
        """.trimIndent())

        // === 索引 ===
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_device_registry_active ON device_registry(is_active)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_parent_policies_type ON parent_policies(policy_type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_parent_announcements_status ON parent_announcements(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_parent_usage_device_date ON parent_usage_summary(device_id, date)")
    }

    // ============================================================
    // [TASK-OPT-12-P1] V3 表结构（应用分类）
    // ============================================================

    /**
     * [TASK-OPT-12-P1] 创建 V3 新增的表与列
     *
     * 在 onCreate (V3+) 和 onUpgrade (V2→V3) 中都会被调用：
     * - app_category：儿童端已安装应用的分类设置（default=关键词规则生成，manual=家长手工覆盖）
     * - announcements 扩展列：requires_ack（紧急公告需确认）、acknowledged_at（确认回执时间）
     *
     * 说明：新建库时 announcements 的 CREATE TABLE 已含扩展列；
     * 老库迁移时通过 addColumnIfNotExists() 补充（SQLite 不支持 ADD COLUMN IF NOT EXISTS）。
     */
    private fun createV3Tables(db: SQLiteDatabase) {
        // === 表10：应用分类表 ===
        // 儿童端已安装应用的分类设置（V3 新增）
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS app_category (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                package_name TEXT NOT NULL,
                app_name TEXT NOT NULL DEFAULT '',
                category TEXT NOT NULL DEFAULT 'other',  -- game/social/video/learning/other
                source TEXT NOT NULL DEFAULT 'default',   -- default/manual
                updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
                UNIQUE(package_name)
            )
        """.trimIndent())

        // === 索引 ===
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_app_category_category ON app_category(category)")

        // === announcements 扩展列（迁移专用，新建库已内建于 CREATE TABLE） ===
        addColumnIfNotExists(db, "announcements", "requires_ack", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfNotExists(db, "announcements", "acknowledged_at", "INTEGER NOT NULL DEFAULT 0")
    }

    /**
     * [TASK-OPT-12-P1] 列不存在时才执行 ALTER TABLE ADD COLUMN
     *
     * SQLite 不支持 ADD COLUMN IF NOT EXISTS，通过 PRAGMA table_info 判断。
     *
     * @param table 目标表名
     * @param column 列名
     * @param ddl 列定义（类型 + 默认值等）
     */
    private fun addColumnIfNotExists(db: SQLiteDatabase, table: String, column: String, ddl: String) {
        var exists = false
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == column) {
                    exists = true
                    break
                }
            }
        }
        if (!exists) {
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $ddl")
        }
    }

    /**
     * 获取可写的加密数据库实例
     * 使用传入的 passphrase 打开数据库
     * [TASK-D3-02] 修复 passphrase ByteArray→String 转换 Bug
     */
    fun getWritable(passphrase: ByteArray): SQLiteDatabase {
        return getWritableDatabase(String(passphrase, Charsets.UTF_8))
    }

    /**
     * 获取只读的加密数据库实例
     */
    fun getReadable(passphrase: ByteArray): SQLiteDatabase {
        return getReadableDatabase(String(passphrase, Charsets.UTF_8))
    }

    companion object {
        private const val DATABASE_NAME = "xiaopacai_guardian.db"
        private const val DATABASE_VERSION = 3  // [TASK-OPT-12-P1] V3：app_category 表 + announcements 扩展列

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * 获取数据库单例
         * 使用双重检查锁定保证线程安全
         *
         * @param context 应用上下文
         * @param passphrase 数据库加密密钥
         */
        fun getInstance(context: Context, passphrase: ByteArray): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppDatabase(
                    context.applicationContext,
                    DATABASE_NAME,
                    passphrase
                ).also { INSTANCE = it }
            }
        }
    }
}
