using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using XiaopacaiParent.Models;
using XiaopacaiParent.Services;

namespace XiaopacaiParent.Views;

/// <summary>
/// [TASK-D2-02] 策略配置页面代码后置
///
/// 处理用户交互：滑块值变更、保存策略、加载模板。
/// 策略数据通过 PolicyEngineService 持久化到加密数据库。
/// </summary>
public partial class PolicyView : Page
{
    private PolicyEngineService? _policyService;

    public PolicyView()
    {
        InitializeComponent();

        // 初始化 Slider 事件（XAML 中没法绑定 ValueChanged）
        DailyLimitSlider.ValueChanged += OnDailyLimitChanged;
        GameLimitSlider.ValueChanged += OnGameLimitChanged;
        SocialLimitSlider.ValueChanged += OnSocialLimitChanged;
        VideoLimitSlider.ValueChanged += OnVideoLimitChanged;

        // 延迟加载策略服务（等待 DatabaseService 就绪）
        Loaded += OnLoaded;
    }

    /// <summary>
    /// 页面加载完成后初始化服务并加载已有策略
    /// </summary>
    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        try
        {
            var dbService = ((App)Application.Current).DatabaseService;
            if (dbService == null) return;

            _policyService = new PolicyEngineService(dbService);

            // 首次使用时创建默认策略
            _policyService.CreateDefaultPolicies();

            // 加载已有策略到 UI
            LoadExistingPolicies();
        }
        catch (Exception ex)
        {
            MessageBox.Show($"策略引擎初始化失败: {ex.Message}", "错误",
                MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    /// <summary>
    /// 从数据库加载策略并填充 UI 控件
    /// </summary>
    private void LoadExistingPolicies()
    {
        if (_policyService == null) return;

        // 每日限额
        var dailyLimit = _policyService.GetPolicy("daily_limit");
        if (dailyLimit != null)
        {
            DailyLimitSlider.Value = dailyLimit.LimitMinutes;
            DailyLimitValue.Text = dailyLimit.LimitMinutes.ToString();
        }

        // 就寝时段
        var sleep = _policyService.GetPolicy("sleep_time");
        if (sleep != null)
        {
            SleepStartBox.Text = sleep.SleepStart;
            SleepEndBox.Text = sleep.SleepEnd;
        }

        // 游戏分类
        var gameLimit = _policyService.GetPolicy("category_limit");
        // 注意：需要按 category 过滤；简化处理取第一个
        // 实际实现中 category_limit 按 category 字段区分
        var categories = _policyService.GetAllPolicies()
            .Where(p => p.PolicyType == "category_limit")
            .ToList();

        foreach (var cat in categories)
        {
            switch (cat.Category)
            {
                case "game":
                    GameLimitSlider.Value = cat.CategoryLimitMinutes;
                    GameLimitValue.Text = cat.CategoryLimitMinutes.ToString();
                    break;
                case "social":
                    SocialLimitSlider.Value = cat.CategoryLimitMinutes;
                    SocialLimitValue.Text = cat.CategoryLimitMinutes.ToString();
                    break;
                case "video":
                    VideoLimitSlider.Value = cat.CategoryLimitMinutes;
                    VideoLimitValue.Text = cat.CategoryLimitMinutes.ToString();
                    break;
            }
        }

        // 黑名单
        var blacklist = _policyService.GetPolicy("blacklist");
        if (blacklist != null && blacklist.PackageNames.Count > 0)
        {
            BlacklistBox.Text = string.Join(Environment.NewLine, blacklist.PackageNames);
        }

        // 白名单
        var whitelist = _policyService.GetPolicy("whitelist");
        if (whitelist != null && whitelist.PackageNames.Count > 0)
        {
            WhitelistBox.Text = string.Join(Environment.NewLine, whitelist.PackageNames);
        }
    }

    // ==================== 每日限额 ====================

    private void OnDailyLimitChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (DailyLimitValue != null)
            DailyLimitValue.Text = ((int)DailyLimitSlider.Value).ToString();
    }

    private void OnSaveDailyLimit(object sender, RoutedEventArgs e)
    {
        if (_policyService == null) return;
        var policy = new PolicyConfig
        {
            PolicyType = "daily_limit",
            LimitMinutes = (int)DailyLimitSlider.Value,
            Label = "家长设置"
        };
        if (_policyService.SavePolicy(policy))
            ShowSuccess("每日限额已保存");
    }

    // ==================== 就寝时段 ====================

    private void OnSaveSleepTime(object sender, RoutedEventArgs e)
    {
        if (_policyService == null) return;
        var policy = new PolicyConfig
        {
            PolicyType = "sleep_time",
            SleepStart = SleepStartBox.Text.Trim(),
            SleepEnd = SleepEndBox.Text.Trim(),
            Label = "家长设置"
        };
        if (_policyService.SavePolicy(policy))
            ShowSuccess("就寝时段已保存");
    }

    // ==================== 分类限额 ====================

    private void OnGameLimitChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (GameLimitValue != null)
            GameLimitValue.Text = ((int)GameLimitSlider.Value).ToString();
    }

    private void OnSocialLimitChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (SocialLimitValue != null)
            SocialLimitValue.Text = ((int)SocialLimitSlider.Value).ToString();
    }

    private void OnVideoLimitChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (VideoLimitValue != null)
            VideoLimitValue.Text = ((int)VideoLimitSlider.Value).ToString();
    }

    private void OnSaveGameCategory(object sender, RoutedEventArgs e)
    {
        SaveCategoryLimit("game", (int)GameLimitSlider.Value, "🎮 游戏");
    }

    private void OnSaveSocialCategory(object sender, RoutedEventArgs e)
    {
        SaveCategoryLimit("social", (int)SocialLimitSlider.Value, "💬 社交");
    }

    private void OnSaveVideoCategory(object sender, RoutedEventArgs e)
    {
        SaveCategoryLimit("video", (int)VideoLimitSlider.Value, "🎬 视频");
    }

    private void SaveCategoryLimit(string category, int limitMinutes, string label)
    {
        if (_policyService == null) return;
        var policy = new PolicyConfig
        {
            PolicyType = "category_limit",
            Category = category,
            CategoryLimitMinutes = limitMinutes,
            Label = label
        };
        if (_policyService.SavePolicy(policy))
            ShowSuccess($"{label}分类限额已保存（{limitMinutes}分钟）");
    }

    // ==================== 黑名单 ====================

    private void OnSaveBlacklist(object sender, RoutedEventArgs e)
    {
        if (_policyService == null) return;
        var packages = ParsePackageList(BlacklistBox.Text);
        var policy = new PolicyConfig
        {
            PolicyType = "blacklist",
            PackageNames = packages,
            Label = "家长设置"
        };
        if (_policyService.SavePolicy(policy))
            ShowSuccess($"黑名单已保存（{packages.Count} 个应用）");
    }

    private void OnLoadBlacklistTemplate(object sender, RoutedEventArgs e)
    {
        // 常用游戏/社交应用黑名单模板
        BlacklistBox.Text = string.Join(Environment.NewLine, new[]
        {
            "com.tencent.tmgp.sgame",      // 王者荣耀
            "com.tencent.tmgp.pubgmhd",    // 和平精英
            "com.netease.uc",              // 荒野行动
            "com.mojang.minecraftpe",      // 我的世界国际版
            "com.tencent.tmgp.cf",         // 穿越火线
            "com.supercell.clashofclans",  // 部落冲突
            "com.roblox.client"            // Roblox
        });
    }

    // ==================== 白名单 ====================

    private void OnSaveWhitelist(object sender, RoutedEventArgs e)
    {
        if (_policyService == null) return;
        var packages = ParsePackageList(WhitelistBox.Text);
        var policy = new PolicyConfig
        {
            PolicyType = "whitelist",
            PackageNames = packages,
            Label = "家长设置"
        };
        if (_policyService.SavePolicy(policy))
            ShowSuccess($"白名单已保存（{packages.Count} 个应用）");
    }

    private void OnLoadWhitelistTemplate(object sender, RoutedEventArgs e)
    {
        // 学习类应用白名单模板
        WhitelistBox.Text = string.Join(Environment.NewLine, new[]
        {
            "com.android.phone",           // 电话
            "com.android.contacts",        // 联系人
            "com.android.mms",             // 短信
            "com.android.calculator2",     // 计算器
            "com.android.deskclock",       // 时钟
            "org.wikipedia",               // 维基百科
            "com.duolingo",                // 多邻国
            "com.microsoft.office.word",   // Word
            "com.google.android.apps.docs" // Google Docs
        });
    }

    // ==================== 工具方法 ====================

    /// <summary>
    /// 解析文本框中的包名列表（按行分割，忽略空行和注释）
    /// </summary>
    private static List<string> ParsePackageList(string text)
    {
        return text.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries)
            .Select(line => line.Trim())
            .Where(line => !string.IsNullOrEmpty(line) && !line.StartsWith("#"))
            .ToList();
    }

    /// <summary>
    /// 显示成功提示
    /// </summary>
    private void ShowSuccess(string message)
    {
        MessageBox.Show(message, "策略保存",
            MessageBoxButton.OK, MessageBoxImage.Information);
    }
}
