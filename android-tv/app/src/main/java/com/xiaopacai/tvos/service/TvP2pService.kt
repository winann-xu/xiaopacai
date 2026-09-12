// [android-tv] P2P 实时通道服务（长连接宿主）
// 小趴菜 TVOS 版
//
// 为什么做成独立 Service：P2P 长连接需要与守护（TVGuardianForegroundService）、
// 云同步（CloudSyncServiceTV）同级的「被看门狗独立拉起」能力 —— 任何一个组件
// 被系统杀掉后，GuardianWatchdog 每分钟自检会把它们各自拉回来。
//
// 启动入口：GuardianWatchdog.ensureGuardsRunning（开机自启 / 每分钟自检 / 任务被划掉后的快速重启）。
// 重连：指数退避由 TvP2pClient 内部处理，本服务只负责持有进程与生命周期。

package com.xiaopacai.tvos.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.xiaopacai.tvos.p2p.TvP2pClient

class TvP2pService : Service() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "P2P 实时通道服务启动")
        TvP2pClient.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 看门狗每分钟 startService 一次：幂等（连接循环在跑则空转），
        // 万一循环已退出（如被系统回收线程）也能借此自愈重启
        TvP2pClient.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "P2P 实时通道服务销毁（由看门狗重新拉起）")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TvP2pService"
    }
}
