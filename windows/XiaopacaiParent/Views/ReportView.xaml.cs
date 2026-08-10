using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Microsoft.Win32;

namespace XiaopacaiParent.Views;

/// <summary>
/// [TASK-D3-01] 小趴菜使用报告视图
///
/// 展示儿童端使用统计的可视化报告（日报/周报）。
/// 包含分类分布图、每日趋势图、Top应用排行等模块。
/// 支持导出为文本文件。
/// </summary>
public partial class ReportView : Page
{
    private readonly ReportService _reportService;
    private string _currentMode = "daily";  // daily / weekly
    private string _selectedDeviceId = "";
    private JsonDocument? _currentReport;

    /// <summary>分类颜色映射（品牌色）</summary>
    private static readonly Dictionary<string, string> CategoryColors = new()
    {
        ["game"] = "#E53935",    // 游戏 - 红色
        ["social"] = "#FF9800",  // 社交 - 橙色
        ["video"] = "#9C27B0",   // 视频 - 紫色
        ["study"] = "#4CAF50",   // 学习 - 绿色
        ["other"] = "#607D8B",   // 其他 - 灰蓝
    };

    /// <summary>分类中文标签</summary>
    private static readonly Dictionary<string, string> CategoryLabels = new()
    {
        ["game"] = "🎮 游戏",
        ["social"] = "💬 社交",
        ["video"] = "📺 视频",
        ["study"] = "📚 学习",
        ["other"] = "📱 其他",
    };

    public ReportView(ReportService reportService)
    {
        InitializeComponent();
        _reportService = reportService;

        LoadDevices();
        DailyBtn.IsDefault = true;
        LoadReport();
    }

    /// <summary>
    /// 加载已同步设备列表
    /// </summary>
    private void LoadDevices()
    {
        var devices = _reportService.GetDeviceIds();
        DeviceSelector.Items.Clear();
        DeviceSelector.Items.Add("全部设备");
        foreach (var id in devices)
            DeviceSelector.Items.Add(id);

        DeviceSelector.SelectedIndex = 0;
        if (devices.Count > 0)
            _selectedDeviceId = devices[0];
    }

    // === 报告模式切换 ===

    private void OnDailyReportClick(object sender, RoutedEventArgs e)
    {
        _currentMode = "daily";
        SubtitleText.Text = "儿童端使用时长日报";
        LoadReport();
    }

    private void OnWeeklyReportClick(object sender, RoutedEventArgs e)
    {
        _currentMode = "weekly";
        SubtitleText.Text = "儿童端使用时长周报（7天）";
        LoadReport();
    }

    private void OnDeviceChanged(object sender, SelectionChangedEventArgs e)
    {
        if (DeviceSelector.SelectedIndex == 0)
            _selectedDeviceId = _reportService.GetDeviceIds().FirstOrDefault() ?? "";
        else if (DeviceSelector.SelectedItem is string id)
            _selectedDeviceId = id;

        LoadReport();
    }

    // === 报告加载与渲染 ===

    /// <summary>
    /// 加载报告数据并渲染所有可视化组件
    /// </summary>
    private void LoadReport()
    {
        try
        {
            var json = _currentMode == "daily"
                ? _reportService.GenerateDailyReport(_selectedDeviceId)
                : _reportService.GenerateWeeklyReport(_selectedDeviceId);

            _currentReport = JsonDocument.Parse(json);
            RenderAll();
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"报告加载失败: {ex.Message}");
            ShowEmptyState();
        }
    }

    /// <summary>
    /// 渲染报告的所有可视化模块
    /// </summary>
    private void RenderAll()
    {
        if (_currentReport == null) return;
        var root = _currentReport.RootElement;

        // 1. 总览卡片
        RenderSummaryCards(root);

        // 2. 分类分布图
        RenderCategoryChart(root);

        // 3. 每日趋势图
        RenderDailyTrend(root);

        // 4. Top 应用排行
        RenderTopApps(root);
    }

    /// <summary>
    /// 渲染顶部摘要卡片
    /// </summary>
    private void RenderSummaryCards(JsonElement root)
    {
        if (_currentMode == "daily")
        {
            var totalHours = root.TryGetProperty("totalHours", out var th) ? th.GetString() ?? "0" : "0";
            var totalMinutes = root.TryGetProperty("totalMinutes", out var tm) ? tm.GetInt64() : 0;

            TotalHoursText.Text = totalHours;
            TotalMinutesSubText.Text = $"{totalMinutes} 分钟";
            AvgDailyText.Text = totalHours;
            AvgDailySubText.Text = "单日统计";
            ExceedDaysText.Text = "—";
        }
        else  // weekly
        {
            var weekHours = root.TryGetProperty("weekTotalHours", out var wh) ? wh.GetString() ?? "0" : "0";
            var weekMinutes = root.TryGetProperty("weekTotalMinutes", out var wm) ? wm.GetInt64() : 0;
            var avgDaily = root.TryGetProperty("averageDailyMinutes", out var adm) ? adm.GetString() ?? "0" : "0";
            var exceedDays = root.TryGetProperty("exceedDays", out var ed) ? ed.GetInt32() : 0;

            TotalHoursText.Text = weekHours;
            TotalMinutesSubText.Text = $"{weekMinutes} 分钟";
            AvgDailyText.Text = avgDaily;
            AvgDailySubText.Text = "分钟/天";
            ExceedDaysText.Text = exceedDays.ToString();
            TotalDaysText.Text = "共 7 天";
        }

        // 趋势箭头
        if (root.TryGetProperty("trend", out var trend))
        {
            var change = trend.TryGetProperty("change", out var ch) ? ch.GetInt64() : 0;
            var changePct = trend.TryGetProperty("changePercent", out var cp) ? cp.GetString() ?? "—" : "—";

            if (change > 0)
            {
                TrendArrowText.Text = "↑";
                TrendArrowText.Foreground = new SolidColorBrush(
                    (Color)ColorConverter.ConvertFromString("#E53935"));
                TrendPercentText.Foreground = new SolidColorBrush(
                    (Color)ColorConverter.ConvertFromString("#E53935"));
            }
            else if (change < 0)
            {
                TrendArrowText.Text = "↓";
                TrendArrowText.Foreground = new SolidColorBrush(
                    (Color)ColorConverter.ConvertFromString("#4CAF50"));
                TrendPercentText.Foreground = new SolidColorBrush(
                    (Color)ColorConverter.ConvertFromString("#4CAF50"));
            }
            else
            {
                TrendArrowText.Text = "→";
            }
            TrendPercentText.Text = $"{changePct}%";
        }
    }

    /// <summary>
    /// 渲染分类分布条形图
    /// </summary>
    private void RenderCategoryChart(JsonElement root)
    {
        CategoryChartPanel.Children.Clear();

        // 确定使用哪个分类汇总 JSON key
        var catKey = _currentMode == "daily" ? "categorySummary" : "weekCategorySummary";
        if (!root.TryGetProperty(catKey, out var catSummary) || catSummary.ValueKind != JsonValueKind.Object)
        {
            CategoryChartPanel.Children.Add(new TextBlock
            {
                Text = "暂无分类数据",
                Foreground = new SolidColorBrush(Colors.Gray),
                FontSize = 14,
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 20, 0, 20)
            });
            return;
        }

        // 计算最大值用于比例缩放
        long maxMinutes = 1;
        var catData = new List<(string Key, string Label, long Minutes, string Hours, string Percent)>();
        foreach (var prop in catSummary.EnumerateObject())
        {
            var mins = prop.Value.TryGetProperty("minutes", out var m) ? m.GetInt64() : 0;
            if (mins > maxMinutes) maxMinutes = mins;
            catData.Add((
                prop.Name,
                CategoryLabels.GetValueOrDefault(prop.Name, $"📱 {prop.Name}"),
                mins,
                prop.Value.TryGetProperty("hours", out var h) ? h.GetString() ?? "0" : "0",
                prop.Value.TryGetProperty("percent", out var p) ? p.GetString() ?? "0%" : "0%"
            ));
        }

        // 按使用时长降序排列
        catData = catData.OrderByDescending(c => c.Minutes).ToList();

        foreach (var (key, label, minutes, hours, percent) in catData)
        {
            var barWidth = maxMinutes > 0 ? (double)minutes / maxMinutes * 100 : 0;
            var color = CategoryColors.GetValueOrDefault(key, "#607D8B");

            var row = new StackPanel { Margin = new Thickness(0, 4, 0, 4) };

            // 分类标签 + 数值
            var header = new Grid { Width = 560 };
            header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(120) });
            header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(120) });

            var labelTb = new TextBlock
            {
                Text = label,
                FontSize = 13,
                FontWeight = FontWeights.Medium,
                VerticalAlignment = VerticalAlignment.Center
            };
            Grid.SetColumn(labelTb, 0);
            header.Children.Add(labelTb);

            // 条形图容器
            var barBorder = new Border
            {
                Height = 28,
                CornerRadius = new CornerRadius(4),
                Background = new SolidColorBrush(Colors.Transparent),
                Margin = new Thickness(4, 0, 4, 0)
            };
            var barFill = new Border
            {
                Height = 28,
                CornerRadius = new CornerRadius(4),
                Background = new SolidColorBrush(
                    (Color)ColorConverter.ConvertFromString(color)),
                Width = Math.Max(barWidth * 3.8, 8),  // 最小8px
                HorizontalAlignment = HorizontalAlignment.Left,
                Opacity = 0.85
            };
            barBorder.Child = barFill;
            Grid.SetColumn(barBorder, 1);
            header.Children.Add(barBorder);

            var valueTb = new TextBlock
            {
                Text = $"{hours}h ({percent}%)",
                FontSize = 12,
                Foreground = new SolidColorBrush(Colors.Gray),
                VerticalAlignment = VerticalAlignment.Center,
                HorizontalAlignment = HorizontalAlignment.Right
            };
            Grid.SetColumn(valueTb, 2);
            header.Children.Add(valueTb);

            row.Children.Add(header);
            CategoryChartPanel.Children.Add(row);
        }
    }

    /// <summary>
    /// 渲染每日趋势简易柱状图
    /// </summary>
    private void RenderDailyTrend(JsonElement root)
    {
        DailyTrendPanel.Children.Clear();

        List<(string Date, long Minutes, string Hours)> trendData;

        if (_currentMode == "daily")
        {
            // 日报：显示近7天趋势
            var summaries = _reportService.GetDeviceDailySummaries(_selectedDeviceId, 7);
            trendData = summaries.Select(s => (
                s["date"]?.ToString()?.Substring(5) ?? "",  // MM-DD
                Convert.ToInt64(s["totalMinutes"] ?? 0),
                s["totalHours"]?.ToString() ?? "0"
            )).ToList();
        }
        else
        {
            // 周报：显示7天详情
            if (root.TryGetProperty("dailySummaries", out var ds) && ds.ValueKind == JsonValueKind.Array)
            {
                trendData = ds.EnumerateArray().Select(day => (
                    day.TryGetProperty("date", out var d) ? d.GetString()?.Substring(5) ?? "" : "",
                    day.TryGetProperty("totalMinutes", out var m) ? m.GetInt64() : 0,
                    day.TryGetProperty("totalHours", out var h) ? h.GetString() ?? "0" : "0"
                )).ToList();
            }
            else
            {
                trendData = new();
            }
        }

        if (trendData.Count == 0)
        {
            DailyTrendPanel.Children.Add(new TextBlock
            {
                Text = "暂无趋势数据",
                Foreground = new SolidColorBrush(Colors.Gray),
                FontSize = 14,
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 20, 0, 20)
            });
            return;
        }

        // 计算最大高度
        var maxMins = Math.Max(trendData.Max(d => d.Minutes), 1);

        // 柱状图容器
        var chartGrid = new Grid
        {
            Height = 180,
            Margin = new Thickness(0, 0, 0, 8)
        };

        // 添加水平基线
        for (int i = 0; i < trendData.Count; i++)
        {
            chartGrid.ColumnDefinitions.Add(new ColumnDefinition());
        }

        for (int i = 0; i < trendData.Count; i++)
        {
            var (date, minutes, hours) = trendData[i];
            var barHeight = Math.Max(minutes * 150.0 / maxMins, 4);

            var colStack = new StackPanel
            {
                VerticalAlignment = VerticalAlignment.Bottom,
                HorizontalAlignment = HorizontalAlignment.Center
            };

            // 数值标签
            var valueLabel = new TextBlock
            {
                Text = $"{hours}h",
                FontSize = 10,
                Foreground = new SolidColorBrush(Colors.Gray),
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 0, 0, 2)
            };
            colStack.Children.Add(valueLabel);

            // 柱体
            var bar = new Border
            {
                Width = 40,
                Height = barHeight,
                CornerRadius = new CornerRadius(4, 4, 0, 0),
                Background = new SolidColorBrush(
                    (Color)ColorConverter.ConvertFromString("#1976D2")),
                Opacity = 0.8,
                HorizontalAlignment = HorizontalAlignment.Center,
                ToolTip = $"{date}: {minutes}分钟"
            };
            colStack.Children.Add(bar);

            // 日期标签
            var dateLabel = new TextBlock
            {
                Text = date,
                FontSize = 10,
                Foreground = new SolidColorBrush(Colors.Gray),
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 4, 0, 0)
            };
            colStack.Children.Add(dateLabel);

            Grid.SetColumn(colStack, i);
            chartGrid.Children.Add(colStack);
        }

        DailyTrendPanel.Children.Add(chartGrid);

        // 超时标记
        var exceedDays = new List<int>();
        if (root.TryGetProperty("dailySummaries", out var dds) && dds.ValueKind == JsonValueKind.Array)
        {
            int idx = 0;
            foreach (var day in dds.EnumerateArray())
            {
                if (day.TryGetProperty("limitExceeded", out var le) && le.GetBoolean())
                    exceedDays.Add(idx);
                idx++;
            }
        }
        if (exceedDays.Count > 0)
        {
            var exceedText = new TextBlock
            {
                Text = $"⚠️ 超时天数：{string.Join("、", exceedDays.Select(d => trendData[d].Date))}",
                FontSize = 12,
                Foreground = new SolidColorBrush(
                    (Color)ColorConverter.ConvertFromString("#E53935")),
                Margin = new Thickness(0, 8, 0, 0)
            };
            DailyTrendPanel.Children.Add(exceedText);
        }
    }

    /// <summary>
    /// 渲染 Top 应用排行
    /// </summary>
    private void RenderTopApps(JsonElement root)
    {
        TopAppsPanel.Children.Clear();

        if (!root.TryGetProperty("topApps", out var topApps) || topApps.ValueKind != JsonValueKind.Array)
        {
            TopAppsPanel.Children.Add(new TextBlock
            {
                Text = "暂无应用数据",
                Foreground = new SolidColorBrush(Colors.Gray),
                FontSize = 14,
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 10, 0, 10)
            });
            return;
        }

        var apps = topApps.EnumerateArray().ToList();
        if (apps.Count == 0)
        {
            TopAppsPanel.Children.Add(new TextBlock
            {
                Text = "暂无应用数据",
                Foreground = new SolidColorBrush(Colors.Gray),
                FontSize = 14,
                HorizontalAlignment = HorizontalAlignment.Center
            });
            return;
        }

        // 表头
        var header = new Grid { Margin = new Thickness(0, 0, 0, 8) };
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(40) });
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(100) });
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(100) });

        AddGridText(header, "排名", 0, FontWeights.Bold, Colors.Gray, 12);
        AddGridText(header, "应用名称", 1, FontWeights.Bold, Colors.Gray, 12);
        AddGridText(header, "使用时长", 2, FontWeights.Bold, Colors.Gray, 12);
        AddGridText(header, "分类", 3, FontWeights.Bold, Colors.Gray, 12);
        TopAppsPanel.Children.Add(header);

        // 分隔线
        TopAppsPanel.Children.Add(new Border
        {
            Height = 1,
            Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#E0E0E0")),
            Margin = new Thickness(0, 0, 0, 8)
        });

        // 应用行
        int rank = 0;
        foreach (var app in apps)
        {
            rank++;
            var appName = app.TryGetProperty("appName", out var an) ? an.GetString() ?? "" : "";
            var packageName = app.TryGetProperty("packageName", out var pn) ? pn.GetString() ?? "" : "";
            var minutes = app.TryGetProperty("minutes", out var m) ? m.GetInt64() : 0;
            var category = app.TryGetProperty("category", out var cat) ? cat.GetString() ?? "other" : "other";

            // 排名徽章颜色
            var rankColor = rank switch
            {
                1 => "#FFD700",  // 金色
                2 => "#C0C0C0",  // 银色
                3 => "#CD7F32",  // 铜色
                _ => "#9E9E9E"
            };

            var row = new Grid { Margin = new Thickness(0, 4, 0, 4) };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(40) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(100) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(100) });

            // 排名
            var rankBorder = new Border
            {
                Width = 28,
                Height = 28,
                CornerRadius = new CornerRadius(14),
                Background = new SolidColorBrush(
                    (Color)ColorConverter.ConvertFromString(rankColor)),
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                Child = new TextBlock
                {
                    Text = rank.ToString(),
                    FontSize = 13,
                    FontWeight = FontWeights.Bold,
                    Foreground = new SolidColorBrush(Colors.White),
                    HorizontalAlignment = HorizontalAlignment.Center,
                    VerticalAlignment = VerticalAlignment.Center
                }
            };
            Grid.SetColumn(rankBorder, 0);
            row.Children.Add(rankBorder);

            // 应用名称
            var nameStack = new StackPanel();
            nameStack.Children.Add(new TextBlock
            {
                Text = string.IsNullOrEmpty(appName) ? packageName : appName,
                FontSize = 14,
                FontWeight = FontWeights.Medium,
                VerticalAlignment = VerticalAlignment.Center
            });
            nameStack.Children.Add(new TextBlock
            {
                Text = packageName,
                FontSize = 11,
                Foreground = new SolidColorBrush(Colors.Gray),
                TextTrimming = TextTrimming.CharacterEllipsis
            });
            Grid.SetColumn(nameStack, 1);
            row.Children.Add(nameStack);

            // 使用时长
            AddGridText(row, $"{minutes}分钟", 2, FontWeights.Normal, Colors.Black, 13);

            // 分类标签
            var catLabel = CategoryLabels.GetValueOrDefault(category, $"📱 {category}");
            var catColor = CategoryColors.GetValueOrDefault(category, "#607D8B");
            var catBadge = new Border
            {
                CornerRadius = new CornerRadius(10),
                Padding = new Thickness(8, 2, 8, 2),
                Background = new SolidColorBrush(
                    (Color)ColorConverter.ConvertFromString(catColor)),
                HorizontalAlignment = HorizontalAlignment.Left,
                VerticalAlignment = VerticalAlignment.Center,
                Child = new TextBlock
                {
                    Text = catLabel,
                    FontSize = 11,
                    Foreground = new SolidColorBrush(Colors.White)
                }
            };
            Grid.SetColumn(catBadge, 3);
            row.Children.Add(catBadge);

            TopAppsPanel.Children.Add(row);
        }
    }

    /// <summary>
    /// 辅助：在 Grid 中添加文字
    /// </summary>
    private static void AddGridText(Grid grid, string text, int column,
        FontWeight weight, Color color, double fontSize)
    {
        var tb = new TextBlock
        {
            Text = text,
            FontSize = fontSize,
            FontWeight = weight,
            Foreground = new SolidColorBrush(color),
            VerticalAlignment = VerticalAlignment.Center
        };
        Grid.SetColumn(tb, column);
        grid.Children.Add(tb);
    }

    /// <summary>
    /// 显示空状态
    /// </summary>
    private void ShowEmptyState()
    {
        TotalHoursText.Text = "—";
        TotalMinutesSubText.Text = "暂无数据";
        AvgDailyText.Text = "—";
        ExceedDaysText.Text = "—";
        TrendArrowText.Text = "→";
        TrendPercentText.Text = "—";

        CategoryChartPanel.Children.Clear();
        CategoryChartPanel.Children.Add(new TextBlock
        {
            Text = "等待儿童端同步使用数据后将自动生成报告",
            Foreground = new SolidColorBrush(Colors.Gray),
            FontSize = 14,
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(0, 20, 0, 20)
        });

        DailyTrendPanel.Children.Clear();
        DailyTrendPanel.Children.Add(new TextBlock
        {
            Text = "暂无趋势数据",
            Foreground = new SolidColorBrush(Colors.Gray),
            FontSize = 14,
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(0, 20, 0, 20)
        });

        TopAppsPanel.Children.Clear();
        TopAppsPanel.Children.Add(new TextBlock
        {
            Text = "暂无应用数据",
            Foreground = new SolidColorBrush(Colors.Gray),
            FontSize = 14,
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(0, 10, 0, 10)
        });
    }

    // === 导出报告 ===

    /// <summary>
    /// 导出报告为文本文件
    /// </summary>
    private void OnExportReportClick(object sender, RoutedEventArgs e)
    {
        if (_currentReport == null)
        {
            MessageBox.Show("暂无报告数据可导出。", "导出失败",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        var dialog = new SaveFileDialog
        {
            Filter = "文本文件 (*.txt)|*.txt|JSON 文件 (*.json)|*.json",
            DefaultExt = "txt",
            FileName = _currentMode == "daily"
                ? $"xiaopacai-日报-{DateTime.Now:yyyyMMdd}.txt"
                : $"xiaopacai-周报-{DateTime.Now:yyyyMMdd}.txt"
        };

        if (dialog.ShowDialog() == true)
        {
            try
            {
                if (dialog.FileName.EndsWith(".json"))
                {
                    // 导出原始 JSON
                    System.IO.File.WriteAllText(dialog.FileName,
                        _currentReport.RootElement.GetRawText());
                }
                else
                {
                    // 导出格式化文本
                    var text = FormatReportAsText(_currentReport.RootElement);
                    System.IO.File.WriteAllText(dialog.FileName, text);
                }

                MessageBox.Show($"报告已导出到：\n{dialog.FileName}",
                    "导出成功", MessageBoxButton.OK, MessageBoxImage.Information);
            }
            catch (Exception ex)
            {
                MessageBox.Show($"导出失败：{ex.Message}",
                    "错误", MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }
    }

    /// <summary>
    /// 将报告 JSON 格式化为可读文本
    /// </summary>
    private static string FormatReportAsText(JsonElement root)
    {
        var sb = new System.Text.StringBuilder();
        var reportType = root.TryGetProperty("reportType", out var rt)
            ? rt.GetString() ?? "daily" : "daily";

        sb.AppendLine("========================================");
        if (reportType == "daily")
        {
            sb.AppendLine($"  📊 小趴菜日报 - {GetProp(root, "date")}");
        }
        else
        {
            sb.AppendLine("  📊 小趴菜周报");
            sb.AppendLine($"  {GetProp(root, "startDate")} ~ {GetProp(root, "endDate")}");
        }
        sb.AppendLine("========================================");
        sb.AppendLine();

        if (reportType == "daily")
        {
            sb.AppendLine($"📱 今日总使用时长：{GetProp(root, "totalHours")} 小时");
            sb.AppendLine();

            sb.AppendLine("📂 分类分布：");
            if (root.TryGetProperty("categorySummary", out var cs))
            {
                foreach (var prop in cs.EnumerateObject())
                {
                    var h = prop.Value.TryGetProperty("hours", out var hv) ? hv.GetString() ?? "0" : "0";
                    var p = prop.Value.TryGetProperty("percent", out var pv) ? pv.GetString() ?? "0" : "0";
                    var label = CategoryLabels.GetValueOrDefault(prop.Name, prop.Name);
                    sb.AppendLine($"  • {label}：{h}h ({p}%)");
                }
            }
            sb.AppendLine();

            sb.AppendLine("🏆 使用最多的应用：");
            if (root.TryGetProperty("topApps", out var ta))
            {
                int i = 0;
                foreach (var app in ta.EnumerateArray())
                {
                    if (i++ >= 5) break;
                    var name = app.TryGetProperty("appName", out var an) ? an.GetString() ?? "" : "";
                    var mins = app.TryGetProperty("minutes", out var mv) ? mv.GetInt64() : 0;
                    sb.AppendLine($"  {i}. {name} - {mins}分钟");
                }
            }
        }
        else
        {
            sb.AppendLine($"📱 本周总使用时长：{GetProp(root, "weekTotalHours")} 小时");
            sb.AppendLine($"📊 日均使用时长：{GetProp(root, "averageDailyMinutes")} 分钟");
            sb.AppendLine($"⚠️  超时天数：{GetProp(root, "exceedDays")}/7");
            sb.AppendLine();

            sb.AppendLine("📈 每日趋势：");
            if (root.TryGetProperty("dailySummaries", out var ds))
            {
                foreach (var day in ds.EnumerateArray())
                {
                    var d = day.TryGetProperty("date", out var dv) ? dv.GetString() ?? "" : "";
                    var m = day.TryGetProperty("totalMinutes", out var mv) ? mv.GetInt64() : 0;
                    var bar = new string('█', (int)Math.Min(m / 10, 30));
                    sb.AppendLine($"  {d} |{bar} {m}分钟");
                }
            }
        }

        // 趋势
        if (root.TryGetProperty("trend", out var trend))
        {
            var change = trend.TryGetProperty("change", out var ch) ? ch.GetInt64() : 0;
            var changePct = trend.TryGetProperty("changePercent", out var cp) ? cp.GetString() ?? "—" : "—";
            var arrow = change > 0 ? "↑" : change < 0 ? "↓" : "→";
            sb.AppendLine();
            sb.AppendLine($"📉 趋势对比：{arrow} {Math.Abs(change)}分钟 ({changePct}%)");
        }

        sb.AppendLine();
        sb.AppendLine("========================================");
        sb.AppendLine("  报告由小趴菜自动生成");
        sb.AppendLine($"  生成时间：{DateTime.Now:yyyy-MM-dd HH:mm:ss}");
        sb.AppendLine("========================================");

        return sb.ToString();
    }

    private static string GetProp(JsonElement el, string name)
    {
        return el.TryGetProperty(name, out var val) ? val.ToString() : "—";
    }

    // === 子页面导航 ===

    /// <summary>
    /// 隐藏页面（暂时不需要）
    /// </summary>
    private static string FormatReportAsHtml(JsonElement root)
    {
        // TODO: [D3-01-EXT] HTML 格式报告导出（支持邮件发送）
        return "";
    }
}
