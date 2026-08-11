@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.xiaopacai.child.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.data.database.AppCategoryDao
import com.xiaopacai.child.ui.theme.XiaopacaiTheme
import com.xiaopacai.child.util.AppCategoryHelper
import com.xiaopacai.child.util.DbPassphraseProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [TASK-OPT-12-P2] 应用分类设置页（需求1）
 *
 * 列出已安装应用 + 当前分类（game/social/video/learning/other），
 * 支持单条修改分类（source 变为 manual，覆盖关键词规则默认值）。
 *
 * 数据来源：app_category 表（SQLCipher）。
 * 首次打开时触发已安装应用扫描，按关键词规则生成默认分类。
 */

/** 可选分类列表（V3 统一口径） */
private val CATEGORY_OPTIONS = listOf("game", "social", "video", "learning", "other")

/** 分类中文名映射 */
private val CATEGORY_LABELS = mapOf(
    "game" to "游戏",
    "social" to "社交",
    "video" to "视频",
    "learning" to "学习",
    "other" to "其他"
)

/** 应用分类条目（UI 数据模型） */
private data class CategoryItem(
    val packageName: String,
    val appName: String,
    var category: String,
    val source: String
)

/**
 * 应用分类设置页 Activity 包装
 */
class AppCategoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XiaopacaiTheme(darkTheme = false) {
                AppCategoryScreen(onBack = { finish() })
            }
        }
    }
}

/**
 * 应用分类设置页主体
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppCategoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // 应用分类列表（异步加载：先初始化默认分类，再读全量数据）
    var items by remember { mutableStateOf<List<CategoryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // 顶部栏
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("应用分类设置", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (loading) {
                // 加载中
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 说明卡片
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "管理各应用的分类，家长可通过分类策略控制使用时长",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "共 ${items.size} 个应用",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }

                    items(items, key = { it.packageName }) { item ->
                        CategoryRow(
                            item = item,
                            onChange = { newCategory ->
                                updateCategory(context, item.packageName, item.appName, newCategory) { success ->
                                    if (success) {
                                        // 更新本地 UI 状态
                                        items = items.map {
                                            if (it.packageName == item.packageName) {
                                                it.copy(category = newCategory, source = "manual")
                                            } else it
                                        }
                                        Toast.makeText(context, "已修改「${item.appName}」分类", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "修改失败，请重试", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // 页面加载：初始化分类表并读取全量数据
    LaunchedEffect(Unit) {
        val passphrase = DbPassphraseProvider.getPassphrase(context)
        withContext(Dispatchers.IO) {
            // 1. 扫描已安装应用，生成默认分类（幂等）
            AppCategoryHelper.ensureInitialized(context, passphrase)
            // 2. 读取全量分类数据
            val dao = AppCategoryDao(com.xiaopacai.child.XiaopacaiApp.instance.database)
            val installed = AppCategoryHelper.getInstalledPackages(context)
            val rows = dao.getAll(passphrase)
            items = rows.mapNotNull { row ->
                val packageName = row["packageName"]?.toString() ?: return@mapNotNull null
                val appName = row["appName"]?.toString() ?: packageName
                CategoryItem(
                    packageName = packageName,
                    appName = appName,
                    category = row["category"]?.toString() ?: "other",
                    source = row["source"]?.toString() ?: "default"
                )
            }.filter { it.packageName in installed }.sortedBy { it.appName }
        }
        loading = false
    }
}

/**
 * 单条应用分类行：应用名 + 包名 + 分类下拉选择
 */
@Composable
private fun CategoryRow(
    item: CategoryItem,
    onChange: (String) -> Unit
) {
    // 下拉展开状态
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 应用名 + 包名
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.appName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.packageName + if (item.source == "manual") "（手动设置）" else "",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 分类下拉框
            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = CATEGORY_LABELS[item.category] ?: item.category,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(end = 2.dp)
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "选择分类",
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    CATEGORY_OPTIONS.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        CATEGORY_LABELS[option] ?: option,
                                        fontSize = 13.sp
                                    )
                                    if (option == item.category) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            "✓",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            },
                            onClick = {
                                expanded = false
                                if (option != item.category) {
                                    onChange(option)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 更新分类到数据库（source → manual）
 */
private fun updateCategory(
    context: android.content.Context,
    packageName: String,
    appName: String,
    category: String,
    onResult: (Boolean) -> Unit
) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
        try {
            val passphrase = DbPassphraseProvider.getPassphrase(context)
            val dao = AppCategoryDao(com.xiaopacai.child.XiaopacaiApp.instance.database)
            val updated = dao.updateCategory(packageName, category, passphrase)
            withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(updated) }
        } catch (e: Exception) {
            withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(false) }
        }
    }
}
