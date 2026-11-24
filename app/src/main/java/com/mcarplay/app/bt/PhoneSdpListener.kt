package com.mcarplay.app.bt

import java.io.Closeable

/** 两项手机 SDP 公告共用一个生命周期；旧监听失败或迟到的恢复任务不能撤销、复活新一轮。 */
internal class PhoneSdpListener<T : Closeable>(
    private val open: () -> T,
    private val onOpened: (T) -> Unit,
    private val onClosed: () -> Unit,
) {
    private var requested = false
    private var generation = 0L
    private var recoveryAttempts = 0
    private var records: T? = null

    val isRunning: Boolean
        @Synchronized get() = records != null

    @Synchronized
    fun isCurrent(expected: T): Boolean = records === expected

    @Synchronized
    fun start(): Boolean {
        if (records != null) return true
        requested = true
        generation++
        recoveryAttempts = 0
        return openIfNeeded()
    }

    /** 蓝牙关闭时撤销公告，但保留用户本轮的连接意图；重新开启后才恢复。 */
    @Synchronized
    fun adapterUnavailable() {
        generation++
        recoveryAttempts = 0
        closeRecords()
    }

    /** 任一监听结束都同时退役两项公告。仅当前实例可以发起一次恢复。 */
    @Synchronized
    fun failed(
        expected: T,
        beforeClose: () -> Unit = {},
    ): Long? {
        if (records !== expected) return null
        generation++
        beforeClose()
        closeRecords()
        return recoveryToken()
    }

    @Synchronized
    fun recoveryToken(): Long? = generation.takeIf { requested && records == null && recoveryAttempts < MAX_RECOVERY_ATTEMPTS }

    /** false 表示已停止、已由其他任务恢复，或令牌属于旧轮次。 */
    @Synchronized
    fun recover(token: Long): Boolean {
        if (!requested || token != generation || records != null || recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) return false
        recoveryAttempts++
        return openIfNeeded()
    }

    /**
     * 配对状态变化后强制重发布：关闭当前两项公告再重新注册。
     *
     * 部分车机在配对瞬间抓取手机的 SDP 服务表并缓存；若那一刻公告尚未就绪，
     * 之后即便车机在设备列表里点选手机也不会发起 iAP2 回连。重新注册能让
     * 系统刷新 EIR/SDP，车机下次查询才有机会看到 …cafe。
     *
     * 重发布不消耗也不返还失败恢复额度（它由配对事件触发，与失败恢复无关）；
     * generation 递增使旧监听的迟到失败不能撤销新一轮公告。
     */
    @Synchronized
    fun republish(): Boolean {
        if (!requested) return false
        generation++
        closeRecords()
        return openIfNeeded()
    }

    @Synchronized
    fun stop() {
        requested = false
        generation++
        closeRecords()
    }

    private fun openIfNeeded(): Boolean {
        if (records != null) return true
        val opened =
            try {
                open()
            } catch (error: Exception) {
                onClosed()
                throw error
            }
        records = opened
        try {
            onOpened(opened)
        } catch (error: Exception) {
            closeRecords()
            throw error
        }
        return true
    }

    private fun closeRecords() {
        val old = records ?: return
        records = null
        try {
            old.close()
        } finally {
            onClosed()
        }
    }

    companion object {
        const val MAX_RECOVERY_ATTEMPTS = 3
    }
}
