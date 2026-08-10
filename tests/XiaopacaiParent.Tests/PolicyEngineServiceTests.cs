using XiaopacaiParent.Models;
using XiaopacaiParent.Services;

namespace XiaopacaiParent.Tests;

/// <summary>
/// [BUG-0810-07] PolicyEngineService 单元测试
///
/// 验证策略引擎核心功能，特别是 category_limit 类型
/// 按 category 维度正确区分，不会互相覆盖。
/// </summary>
public class PolicyEngineServiceTests : IDisposable
{
    private readonly string _testDir;
    private readonly DatabaseService _db;
    private readonly PolicyEngineService _service;

    public PolicyEngineServiceTests()
    {
        // 每个测试使用独立的临时数据库
        _testDir = Path.Combine(Path.GetTempPath(), $"xiaopacai_test_{Guid.NewGuid():N}");
        Directory.CreateDirectory(_testDir);
        _db = new DatabaseService(_testDir, "test_password");
        _service = new PolicyEngineService(_db);
    }

    public void Dispose()
    {
        _db.Dispose();
        try { Directory.Delete(_testDir, recursive: true); } catch { /* 清理失败不影响测试 */ }
    }

    // ==================== SavePolicy 基础功能 ====================

    [Fact]
    public void SavePolicy_NewPolicy_ShouldCreate()
    {
        var policy = new PolicyConfig
        {
            PolicyType = "daily_limit",
            LimitMinutes = 120,
            Label = "测试每日限额"
        };

        var result = _service.SavePolicy(policy);

        Assert.True(result);
        var loaded = _service.GetPolicy("daily_limit");
        Assert.NotNull(loaded);
        Assert.Equal(120, loaded!.LimitMinutes);
        Assert.Equal("测试每日限额", loaded.Label);
    }

    [Fact]
    public void SavePolicy_ExistingPolicy_ShouldUpdate()
    {
        // 首次创建
        var policy1 = new PolicyConfig
        {
            PolicyType = "daily_limit",
            LimitMinutes = 60,
            Label = "v1"
        };
        _service.SavePolicy(policy1);

        // 同类型再次保存 → 应更新而非新增
        var policy2 = new PolicyConfig
        {
            PolicyType = "daily_limit",
            LimitMinutes = 180,
            Label = "v2"
        };
        var result = _service.SavePolicy(policy2);

        Assert.True(result);

        // 验证只有一条记录（版本号递增）
        var all = _service.GetAllPolicies();
        var dailyPolicies = all.Where(p => p.PolicyType == "daily_limit").ToList();
        Assert.Single(dailyPolicies);
        Assert.Equal(180, dailyPolicies[0].LimitMinutes);
        Assert.Equal("v2", dailyPolicies[0].Label);
        Assert.True(dailyPolicies[0].Version >= 2);
    }

    // ==================== category_limit 分类键区分 (BUG-0810-07 核心) ====================

    [Fact]
    public void SavePolicy_CategoryLimitDifferentCategories_ShouldNotOverwrite()
    {
        // 保存游戏分类限额
        var gamePolicy = new PolicyConfig
        {
            PolicyType = "category_limit",
            Category = "game",
            CategoryLimitMinutes = 60,
            Label = "游戏限额"
        };
        _service.SavePolicy(gamePolicy);

        // 保存社交分类限额
        var socialPolicy = new PolicyConfig
        {
            PolicyType = "category_limit",
            Category = "social",
            CategoryLimitMinutes = 90,
            Label = "社交限额"
        };
        _service.SavePolicy(socialPolicy);

        // 保存视频分类限额
        var videoPolicy = new PolicyConfig
        {
            PolicyType = "category_limit",
            Category = "video",
            CategoryLimitMinutes = 120,
            Label = "视频限额"
        };
        _service.SavePolicy(videoPolicy);

        // 验证三条 category_limit 策略共存
        var all = _service.GetAllPolicies();
        var categoryPolicies = all.Where(p => p.PolicyType == "category_limit").ToList();
        Assert.Equal(3, categoryPolicies.Count);

        // 验证各自的限额值没有被覆盖
        var game = categoryPolicies.First(p => p.Category == "game");
        Assert.Equal(60, game.CategoryLimitMinutes);
        Assert.Equal("游戏限额", game.Label);

        var social = categoryPolicies.First(p => p.Category == "social");
        Assert.Equal(90, social.CategoryLimitMinutes);
        Assert.Equal("社交限额", social.Label);

        var video = categoryPolicies.First(p => p.Category == "video");
        Assert.Equal(120, video.CategoryLimitMinutes);
        Assert.Equal("视频限额", video.Label);
    }

    [Fact]
    public void SavePolicy_CategoryLimitSameCategory_ShouldUpdate()
    {
        // 首次保存游戏限额
        var policy1 = new PolicyConfig
        {
            PolicyType = "category_limit",
            Category = "game",
            CategoryLimitMinutes = 60,
            Label = "v1"
        };
        _service.SavePolicy(policy1);

        // 再次保存同分类 → 应更新
        var policy2 = new PolicyConfig
        {
            PolicyType = "category_limit",
            Category = "game",
            CategoryLimitMinutes = 120,
            Label = "v2"
        };
        _service.SavePolicy(policy2);

        var all = _service.GetAllPolicies();
        var gamePolicies = all.Where(p => p.PolicyType == "category_limit" && p.Category == "game").ToList();
        Assert.Single(gamePolicies);
        Assert.Equal(120, gamePolicies[0].CategoryLimitMinutes);
        Assert.Equal("v2", gamePolicies[0].Label);
    }

    // ==================== GetPolicy 分类查询 ====================

    [Fact]
    public void GetPolicy_WithCategory_ShouldReturnCorrectPolicy()
    {
        _service.CreateDefaultPolicies();

        // 查询游戏分类限额
        var gamePolicy = _service.GetPolicy("category_limit", "", "game");
        Assert.NotNull(gamePolicy);
        Assert.Equal("game", gamePolicy!.Category);
        Assert.Equal(60, gamePolicy.CategoryLimitMinutes);

        // 查询社交分类限额
        var socialPolicy = _service.GetPolicy("category_limit", "", "social");
        Assert.NotNull(socialPolicy);
        Assert.Equal("social", socialPolicy!.Category);
        Assert.Equal(90, socialPolicy.CategoryLimitMinutes);
    }

    [Fact]
    public void GetPolicy_WithoutCategory_ShouldReturnAnyCategoryLimit()
    {
        _service.CreateDefaultPolicies();

        // 不指定 category → 返回任意 category_limit（LIMIT 1）
        var policy = _service.GetPolicy("category_limit");
        Assert.NotNull(policy);
        Assert.Equal("category_limit", policy!.PolicyType);
    }

    // ==================== 非 category_limit 策略不受影响 ====================

    [Fact]
    public void SavePolicy_NonCategoryPolicies_ShouldStillWork()
    {
        // 每日限额
        _service.SavePolicy(new PolicyConfig
        {
            PolicyType = "daily_limit",
            LimitMinutes = 150,
            Label = "每日"
        });

        // 就寝时段
        _service.SavePolicy(new PolicyConfig
        {
            PolicyType = "sleep_time",
            SleepStart = "22:00",
            SleepEnd = "06:00",
            Label = "就寝"
        });

        // 黑名单
        _service.SavePolicy(new PolicyConfig
        {
            PolicyType = "blacklist",
            PackageNames = new List<string> { "com.example.app" },
            Label = "黑名单"
        });

        var all = _service.GetAllPolicies();
        Assert.Equal(3, all.Count);

        var daily = _service.GetPolicy("daily_limit");
        Assert.NotNull(daily);
        Assert.Equal(150, daily!.LimitMinutes);

        var sleep = _service.GetPolicy("sleep_time");
        Assert.NotNull(sleep);
        Assert.Equal("22:00", sleep!.SleepStart);

        var blacklist = _service.GetPolicy("blacklist");
        Assert.NotNull(blacklist);
        Assert.Single(blacklist!.PackageNames);
    }

    // ==================== CreateDefaultPolicies ====================

    [Fact]
    public void CreateDefaultPolicies_ShouldCreateAllDefaults()
    {
        _service.CreateDefaultPolicies();

        var all = _service.GetAllPolicies();
        Assert.Equal(4, all.Count);  // daily_limit, sleep_time, game, social

        Assert.Contains(all, p => p.PolicyType == "daily_limit");
        Assert.Contains(all, p => p.PolicyType == "sleep_time");
        Assert.Contains(all, p => p.PolicyType == "category_limit" && p.Category == "game");
        Assert.Contains(all, p => p.PolicyType == "category_limit" && p.Category == "social");
    }

    [Fact]
    public void CreateDefaultPolicies_Idempotent_ShouldNotDuplicate()
    {
        _service.CreateDefaultPolicies();
        _service.CreateDefaultPolicies();  // 第二次调用应跳过

        var all = _service.GetAllPolicies();
        // 不应重复创建
        Assert.Equal(4, all.Count);
    }

    // ==================== DeactivatePolicy ====================

    [Fact]
    public void DeactivatePolicy_ShouldSoftDelete()
    {
        _service.SavePolicy(new PolicyConfig
        {
            PolicyType = "daily_limit",
            LimitMinutes = 120,
            Label = "test"
        });

        var result = _service.DeactivatePolicy("daily_limit");
        Assert.True(result);

        // 停用后 GetPolicy 应返回 null（仅查询活跃策略）
        var loaded = _service.GetPolicy("daily_limit");
        Assert.Null(loaded);

        // 但 GetAllPolicies 不应返回停用策略
        var all = _service.GetAllPolicies();
        Assert.Empty(all);
    }

    [Fact]
    public void DeactivatePolicy_CategoryLimit_ShouldTargetCorrectCategory()
    {
        _service.SavePolicy(new PolicyConfig
        {
            PolicyType = "category_limit",
            Category = "game",
            CategoryLimitMinutes = 60,
            Label = "game"
        });
        _service.SavePolicy(new PolicyConfig
        {
            PolicyType = "category_limit",
            Category = "social",
            CategoryLimitMinutes = 90,
            Label = "social"
        });

        // 停用游戏分类
        _service.DeactivatePolicy("category_limit", "", "game");

        // 游戏策略应不可见
        var gamePolicy = _service.GetPolicy("category_limit", "", "game");
        Assert.Null(gamePolicy);

        // 社交策略应仍可见
        var socialPolicy = _service.GetPolicy("category_limit", "", "social");
        Assert.NotNull(socialPolicy);
        Assert.Equal(90, socialPolicy!.CategoryLimitMinutes);
    }
}
