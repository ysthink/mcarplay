package com.mcarplay.app.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat

/** 只观察本轮系统广播，不请求配对、不改变蓝牙配置，也不读取 PIN 等配对材料。 */
internal class BluetoothStateMonitor(
    private val context: Context,
    private val diagnostics: BluetoothDiagnostics,
    private val epoch: Long,
    private val targetProvider: () -> TargetDevicePrefs.Target?,
    private val onAdapterStateChanged: (Int) -> Unit = {},
    /** 目标车机配对完成（BOND_BONDED）时触发，供手机侧重发 SDP 公告。 */
    private val onTargetBonded: (String) -> Unit = {},
    private val isCurrent: () -> Boolean,
) {
    private var registered = false
    private val receiver =
        object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (!isCurrent()) return
                if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    adapterSnapshot()
                    onAdapterStateChanged(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR))
                    return
                }
                if (intent.action == BluetoothAdapter.ACTION_SCAN_MODE_CHANGED) {
                    adapterSnapshot()
                    return
                }
                val device = device(intent) ?: return
                val address = runCatching { device.address }.getOrNull() ?: return
                val target = targetProvider()
                val match =
                    when {
                        target == null -> BluetoothDiagnostics.PeerMatch.UNSELECTED
                        TargetDevicePrefs.matches(target, address) -> BluetoothDiagnostics.PeerMatch.TARGET
                        else -> BluetoothDiagnostics.PeerMatch.OTHER
                    }
                when (intent.action) {
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                        val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                        diagnostics.bondChanged(epoch, address, match, previous, state)
                        // 仅目标设备、且确实刚进入 BONDED（避免 BONDING→BONDED 之外的重复广播）时重发公告。
                        if (state == BluetoothDevice.BOND_BONDED &&
                            previous != BluetoothDevice.BOND_BONDED &&
                            match == BluetoothDiagnostics.PeerMatch.TARGET
                        ) {
                            onTargetBonded(address)
                        }
                    }
                    BluetoothDevice.ACTION_ACL_CONNECTED -> diagnostics.acl(epoch, address, match, connected = true)
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> diagnostics.acl(epoch, address, match, connected = false)
                }
            }
        }

    fun start() {
        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter().apply {
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                    addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                    addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
            registered = true
            diagnostics.monitor(epoch, started = true)
        } catch (error: RuntimeException) {
            diagnostics.monitor(epoch, started = false, error = error)
        }
        adapterSnapshot()
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(receiver) }
        diagnostics.monitor(epoch, started = false)
    }

    @SuppressLint("MissingPermission")
    private fun adapterSnapshot() {
        val adapter = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
        diagnostics.adapter(
            epoch,
            runCatching { adapter?.state }.getOrNull(),
            runCatching { adapter?.scanMode }.getOrNull(),
            runCatching { adapter?.isDiscovering }.getOrNull(),
        )
    }

    private fun device(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}
