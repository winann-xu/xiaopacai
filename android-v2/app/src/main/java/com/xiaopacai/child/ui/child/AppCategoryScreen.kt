package com.xiaopacai.child.ui.child

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.xiaopacai.child.util.CategoryTaxonomy
import com.xiaopacai.child.util.DbPassphraseProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppCategoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XiaopacaiTheme {
                AppCategoryScreenV2(onBack = { finish() })
            }
        }
    }
}

private val CATEGORY_OPTIONS = CategoryTaxonomy.CATEGORY_OPTIONS
private val CATEGORY_LABELS = CategoryTaxonomy.CATEGORY_LABELS

private data class CategoryItem(
    val packageName: String,
    val appName: String,
    var category: String,
    val source: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppCategoryScreenV2(onBack: () -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<CategoryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var searchText by remember { mutableStateOf("") }
    var autoClassifying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val filteredItems = items.filter {
        searchText.isBlank() ||
            it.appName.contains(searchText, ignoreCase = true) ||
            it.packageName.contains(searchText, ignoreCase = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("应用分类", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (loading) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("正在加载应用列表…", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (items.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("暂无可分类的应用", fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        OutlinedTextField(
                            value = searchText,
                            onValueChange = { searchText = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("搜索应用名或包名") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                        )
                    }
                    item {
                        Button(
                            onClick = {
                                autoClassifying = true
                                scope.launch(Dispatchers.IO) {
                                    val passphrase = DbPassphraseProvider.getPassphrase(context)
                                    AppCategoryHelper.autoClassify(context, passphrase)
                                    val dao = AppCategoryDao(com.xiaopacai.child.XiaopacaiApp.instance.database)
                                    val installed = AppCategoryHelper.getInstalledPackages(context)
                                    val rows = dao.getAll(passphrase)
                                    val reloaded = rows.mapNotNull { row ->
                                        val pkg = row["packageName"]?.toString() ?: return@mapNotNull null
                                        val name = row["appName"]?.toString() ?: pkg
                                        CategoryItem(pkg, name,
                                            row["category"]?.toString() ?: "other",
                                            row["source"]?.toString() ?: "default")
                                    }.filter { it.packageName in installed }.sortedBy { it.appName }
                                    withContext(Dispatchers.Main) { items = reloaded; autoClassifying = false }
                                }
                            },
                            enabled = !autoClassifying,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (autoClassifying) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("自动分类中…")
                            } else {
                                Icon(Icons.Default.Bolt, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("一键自动分类")
                            }
                        }
                    }
                    items(filteredItems, key = { it.packageName }) { item ->
                        CategoryRow(item = item, onChange = { newCategory ->
                            updateCategory(context, item.packageName, item.appName, newCategory) { success ->
                                if (success) {
                                    items = items.map {
                                        if (it.packageName == item.packageName)
                                            it.copy(category = newCategory, source = "manual")
                                        else it
                                    }
                                    Toast.makeText(context, "已修改「${item.appName}」分类", Toast.LENGTH_SHORT).show()
                                }
                            }
                        })
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            val passphrase = DbPassphraseProvider.getPassphrase(context)
            withContext(Dispatchers.IO) {
                AppCategoryHelper.ensureInitialized(context, passphrase)
                val dao = AppCategoryDao(com.xiaopacai.child.XiaopacaiApp.instance.database)
                val installed = AppCategoryHelper.getInstalledPackages(context)
                val rows = dao.getAll(passphrase)
                items = rows.mapNotNull { row ->
                    val pkg = row["packageName"]?.toString() ?: return@mapNotNull null
                    CategoryItem(pkg, row["appName"]?.toString() ?: pkg,
                        row["category"]?.toString() ?: "other",
                        row["source"]?.toString() ?: "default")
                }.filter { it.packageName in installed }.sortedBy { it.appName }
            }
        } catch (_: Exception) {}
        loading = false
    }
}

@Composable
private fun CategoryRow(item: CategoryItem, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.appName, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.packageName + if (item.source == "manual") "（手动）" else "",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Box {
                OutlinedButton(onClick = { expanded = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                    Text(CATEGORY_LABELS[item.category] ?: item.category, fontSize = 13.sp)
                    Icon(Icons.Default.KeyboardArrowDown, "选择分类", Modifier.size(18.dp))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    CATEGORY_OPTIONS.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(CATEGORY_LABELS[option] ?: option, fontSize = 13.sp) },
                            onClick = { expanded = false; if (option != item.category) onChange(option) }
                        )
                    }
                }
            }
        }
    }
}

private fun updateCategory(
    context: android.content.Context,
    packageName: String, appName: String, category: String,
    onResult: (Boolean) -> Unit
) {
    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
        try {
            val passphrase = DbPassphraseProvider.getPassphrase(context)
            val dao = AppCategoryDao(com.xiaopacai.child.XiaopacaiApp.instance.database)
            val updated = dao.updateCategory(packageName, category, passphrase)
            withContext(Dispatchers.Main) { onResult(updated) }
        } catch (_: Exception) {
            withContext(Dispatchers.Main) { onResult(false) }
        }
    }
}
