using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using XiaopacaiParent.Services;

namespace XiaopacaiParent.Views;

/// <summary>
/// [TASK-D2-04] 公告管理页面代码后置
///
/// 提供公告的创建、编辑、发送和删除功能。
/// 公告数据通过 AnnouncementService 持久化。
/// </summary>
public partial class AnnouncementView : Page
{
    private AnnouncementService? _service;
    private string? _selectedAnnouncementId;

    public AnnouncementView()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        try
        {
            var dbService = ((App)Application.Current).DatabaseService;
            if (dbService == null) return;

            _service = new AnnouncementService(dbService);
            RefreshList();
        }
        catch (Exception ex)
        {
            MessageBox.Show($"公告服务初始化失败: {ex.Message}", "错误",
                MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    /// <summary>
    /// 刷新公告列表
    /// </summary>
    private void RefreshList()
    {
        if (_service == null) return;

        var announcements = _service.GetAll();
        var displayItems = announcements.Select(a => new AnnouncementDisplayItem
        {
            Id = a.AnnouncementId,
            Title = a.Title,
            ContentPreview = a.Content.Length > 60
                ? a.Content[..60] + "..."
                : a.Content,
            Priority = a.Priority,
            PriorityText = a.Priority switch
            {
                2 => "🔴 紧急",
                1 => "🟠 重要",
                _ => "🔵 普通"
            },
            IsSent = a.IsSent,
            TimeText = (a.IsSent ? "✅ 已发送 · " : "📝 草稿 · ") +
                       DateTimeOffset.FromUnixTimeSeconds(a.CreatedAt)
                           .ToLocalTime()
                           .ToString("MM-dd HH:mm")
        }).ToList();

        AnnouncementList.ItemsSource = displayItems;

        // 空状态提示
        if (EmptyHint != null)
            EmptyHint.Visibility = displayItems.Count == 0
                ? Visibility.Visible : Visibility.Collapsed;
    }

    /// <summary>
    /// 选中公告 → 加载到编辑区
    /// </summary>
    private void OnAnnouncementSelected(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if (sender is not FrameworkElement element) return;
        if (element.DataContext is not AnnouncementDisplayItem item) return;

        _selectedAnnouncementId = item.Id;
        TitleBox.Text = item.Title;
        ContentBox.Text = _service?.GetAll()
            .FirstOrDefault(a => a.AnnouncementId == item.Id)?.Content ?? "";
        PriorityCombo.SelectedIndex = item.Priority;

        StatusText.Text = $"已选中: {item.Title}";
    }

    /// <summary>
    /// 发送公告
    /// </summary>
    private void OnSendAnnouncement(object sender, RoutedEventArgs e)
    {
        if (_service == null) return;

        var title = TitleBox.Text.Trim();
        var content = ContentBox.Text.Trim();

        if (string.IsNullOrEmpty(title) || string.IsNullOrEmpty(content))
        {
            MessageBox.Show("标题和内容不能为空。", "提示",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        if (!string.IsNullOrEmpty(_selectedAnnouncementId))
        {
            // 更新已有公告
            var existing = _service.GetAll()
                .FirstOrDefault(a => a.AnnouncementId == _selectedAnnouncementId);
            if (existing != null)
            {
                existing.Title = title;
                existing.Content = content;
                existing.Priority = PriorityCombo.SelectedIndex;
                existing.IsSent = true;
                _service.Save(existing);
            }
        }
        else
        {
            // 创建新公告
            _service.Create(title, content, PriorityCombo.SelectedIndex);
            _service.MarkSent(
                _service.GetAll().First().AnnouncementId
            );
        }

        StatusText.Text = $"✅ 公告已发送: {title}";
        ClearForm();
        RefreshList();
    }

    /// <summary>
    /// 删除公告
    /// </summary>
    private void OnDeleteAnnouncement(object sender, RoutedEventArgs e)
    {
        if (_service == null || string.IsNullOrEmpty(_selectedAnnouncementId)) return;

        var result = MessageBox.Show("确定要删除这条公告吗？", "确认",
            MessageBoxButton.YesNo, MessageBoxImage.Question);

        if (result == MessageBoxResult.Yes)
        {
            _service.Delete(_selectedAnnouncementId);
            StatusText.Text = "公告已删除";
            ClearForm();
            RefreshList();
        }
    }

    /// <summary>
    /// 清空编辑表单
    /// </summary>
    private void OnClearForm(object sender, RoutedEventArgs e)
    {
        ClearForm();
    }

    private void ClearForm()
    {
        _selectedAnnouncementId = null;
        TitleBox.Text = "";
        ContentBox.Text = "";
        PriorityCombo.SelectedIndex = 0;
        StatusText.Text = "";
    }
}

/// <summary>
/// [TASK-D2-04] 公告列表展示项（简化绑定的视图模型）
/// </summary>
public class AnnouncementDisplayItem
{
    public string Id { get; set; } = string.Empty;
    public string Title { get; set; } = string.Empty;
    public string ContentPreview { get; set; } = string.Empty;
    public int Priority { get; set; }
    public string PriorityText { get; set; } = string.Empty;
    public bool IsSent { get; set; }
    public string TimeText { get; set; } = string.Empty;
}
