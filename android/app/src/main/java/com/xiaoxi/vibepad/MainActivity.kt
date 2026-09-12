package com.xiaoxi.vibepad

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.xiaoxi.vibepad.input.InputSink
import com.xiaoxi.vibepad.input.HelperHealth
import com.xiaoxi.vibepad.input.HidModifiers
import com.xiaoxi.vibepad.input.MicrophoneStreamer
import com.xiaoxi.vibepad.input.RemoteApp
import com.xiaoxi.vibepad.input.RemoteDataListener
import com.xiaoxi.vibepad.input.TouchBarFrame
import com.xiaoxi.vibepad.input.WifiInputSink
import com.xiaoxi.vibepad.ui.NoOpInputSink
import com.xiaoxi.vibepad.ui.VibePadView
import com.xiaoxi.vibepad.system.KioskController

class MainActivity : Activity() {
    private var inputSink: InputSink = NoOpInputSink
    private var vibePadView: VibePadView? = null
    private var wifiSink: WifiInputSink? = null
    private var microphoneStreamer: MicrophoneStreamer? = null
    private var kioskController: KioskController? = null
    private var pairingCodeDialog: AlertDialog? = null
    private var lastWifiState = WifiInputSink.State(WifiInputSink.Status.STARTING)
    private var microphonePressHeld = false
    private var typelessSessionStarted = false
    private var typelessStartTapInFlight = false
    private var typelessStopRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kioskController = KioskController(this).also { it.start() }
        vibePadView = VibePadView(
            context = this,
            sinkProvider = { inputSink },
            touchBarSinkProvider = { wifiSink },
            onSettingsClick = ::showVibePadSettings,
            onMicrophonePressStart = ::beginMicrophonePress,
            onMicrophonePressEnd = ::endMicrophonePress,
        ).also(::setContentView)

        val wifi = WifiInputSink(
            context = this,
            statusListener = WifiInputSink.StatusListener(::onWifiStatusChanged),
            remoteDataListener = object : RemoteDataListener {
                override fun onHelperHealth(health: HelperHealth) = runOnUiThread {
                    vibePadView?.setHelperHealth(health)
                }

                override fun onTouchBarFrame(frame: TouchBarFrame) {
                    vibePadView?.setTouchBarFrame(frame)
                }

                override fun onAppCatalogStarted() = runOnUiThread {
                    vibePadView?.beginAppCatalog()
                }

                override fun onRemoteApp(app: RemoteApp) = runOnUiThread {
                    vibePadView?.addRemoteApp(app)
                }

                override fun onAppCatalogFinished() = runOnUiThread {
                    vibePadView?.finishAppCatalog()
                }

                override fun onPairingCode(code: String) = runOnUiThread {
                    showPairingCode(code)
                }

                override fun onPairingMessage(message: String, success: Boolean) = runOnUiThread {
                    if (success) pairingCodeDialog?.dismiss()
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    if (!success && message.contains("菜单栏")) showPairingInstructions(message)
                }
            },
            audioDisconnectListener = { runOnUiThread {
                abortMicrophonePress(MicrophoneStreamer.STOP_REASON_DISCONNECTED)
            }
            },
        ).also { wifiSink = it }
        microphoneStreamer = MicrophoneStreamer(wifi, ::onMicrophoneStateChanged)
        attachInputSink(wifi)
    }

    /**
     * Installs the live network sink without rebuilding the UI; all controls resolve the
     * sink lazily for every input event, so a placeholder can be swapped out later.
     */
    fun attachInputSink(sink: InputSink) {
        inputSink.releaseAll()
        inputSink = sink
        vibePadView?.refreshConnectionState()
    }

    private fun onWifiStatusChanged(state: WifiInputSink.State) {
        runOnUiThread {
            lastWifiState = state
            val label = when (state.status) {
                WifiInputSink.Status.STARTING -> "Wi-Fi 启动中"
                WifiInputSink.Status.DISCOVERING -> "正在查找 Mac"
                WifiInputSink.Status.CONNECTING -> state.detail ?: "Wi-Fi 正在连接"
                WifiInputSink.Status.AUTHENTICATING -> state.detail ?: "正在安全认证"
                WifiInputSink.Status.PAIRING_REQUIRED -> state.detail ?: "需要配对"
                WifiInputSink.Status.PAIRING -> state.detail ?: "正在配对"
                WifiInputSink.Status.CONNECTED -> state.detail ?: "5GHz Wi-Fi 已连接"
                WifiInputSink.Status.DISCONNECTED -> state.detail ?: "Wi-Fi 未连接"
                WifiInputSink.Status.ERROR -> state.detail ?: "Wi-Fi 异常"
                WifiInputSink.Status.CLOSED -> "Wi-Fi 已关闭"
            }
            vibePadView?.setConnectionDetail(label)
            vibePadView?.refreshConnectionState()
            if (state.status == WifiInputSink.Status.CONNECTED) {
                wifiSink?.requestApps()
                Toast.makeText(this, "已切换到 5GHz Wi-Fi", Toast.LENGTH_SHORT).show()
            } else if (microphoneStreamer?.isRecording == true || typelessSessionStarted) {
                abortMicrophonePress(MicrophoneStreamer.STOP_REASON_DISCONNECTED)
            }
        }
    }

    private fun beginMicrophonePress() {
        if (microphonePressHeld) return
        microphonePressHeld = true
        val microphone = microphoneStreamer ?: return
        if (microphone.isRecording) return
        if (wifiSink?.isConnected != true) {
            Toast.makeText(this, "请先连接 Mac Helper", Toast.LENGTH_SHORT).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePressHeld = false
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }
        startMicrophoneAndTypeless()
    }

    private fun endMicrophonePress() {
        if (!microphonePressHeld) return
        microphonePressHeld = false
        stopMicrophoneAndTypeless(MicrophoneStreamer.STOP_REASON_USER)
    }

    private fun startMicrophoneAndTypeless() {
        if (wifiSink?.isConnected != true) {
            Toast.makeText(this, "Mac 已断开，无法启动麦克风", Toast.LENGTH_SHORT).show()
            return
        }
        val microphone = microphoneStreamer ?: return
        if (!microphone.start()) return
        vibePadView?.postDelayed({
            if (microphonePressHeld && microphone.isRecording && wifiSink?.isConnected == true) {
                typelessSessionStarted = true
                typelessStartTapInFlight = true
                sendTypelessTap {
                    typelessStartTapInFlight = false
                    if (typelessStopRequested) {
                        typelessStopRequested = false
                        typelessSessionStarted = false
                        sendTypelessTap()
                    }
                }
            } else if (microphone.isRecording) {
                microphone.stop(MicrophoneStreamer.STOP_REASON_USER)
            }
        }, TYPELESS_START_DELAY_MS)
    }

    private fun stopMicrophoneAndTypeless(reason: Int) {
        microphoneStreamer?.stop(reason)
        if (!typelessSessionStarted) return
        if (typelessStartTapInFlight) {
            typelessStopRequested = true
        } else {
            typelessSessionStarted = false
            sendTypelessTap()
        }
    }

    private fun abortMicrophonePress(reason: Int) {
        microphonePressHeld = false
        typelessSessionStarted = false
        typelessStartTapInFlight = false
        typelessStopRequested = false
        microphoneStreamer?.stop(reason)
    }

    private fun sendTypelessTap(onReleased: (() -> Unit)? = null) {
        if (wifiSink?.isConnected != true) {
            onReleased?.invoke()
            return
        }
        val sink = inputSink
        sink.key(0, HidModifiers.LEFT_ALT, true)
        vibePadView?.postDelayed({
            sink.key(0, HidModifiers.LEFT_ALT, false)
            onReleased?.invoke()
        }, TYPELESS_TAP_MS)
    }

    private fun onMicrophoneStateChanged(state: MicrophoneStreamer.State, detail: String?) {
        runOnUiThread {
            vibePadView?.setMicrophoneState(state)
            if (state == MicrophoneStreamer.State.ERROR && detail != null) {
                Toast.makeText(this, detail, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        microphonePressHeld = false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            vibePadView?.setMicrophoneState(MicrophoneStreamer.State.IDLE)
            Toast.makeText(this, "权限已开启，请按住按钮说话", Toast.LENGTH_SHORT).show()
        } else {
            vibePadView?.setMicrophoneState(MicrophoneStreamer.State.ERROR)
            Toast.makeText(this, "需要麦克风权限才能使用平板听写", Toast.LENGTH_LONG).show()
        }
    }

    private fun showVibePadSettings() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val prefs = getSharedPreferences("vibepad_ui", MODE_PRIVATE)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(8))
            addView(TextView(this@MainActivity).apply {
                text = when {
                    wifiSink?.isConnected == true -> "已连接"
                    wifiSink?.isPaired == true -> "正在建立安全连接"
                    else -> "等待安全配对"
                }
                textSize = 15f
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32)))
            addView(Button(this@MainActivity).apply {
                isAllCaps = false
                text = if (wifiSink?.isPaired == true) "重新配对这台平板" else "配对这台平板"
                setOnClickListener {
                    showPairingInstructions("请先点 Mac 菜单栏的“WP”，选择“允许配对新平板（60 秒）”，然后点下方继续。")
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply {
                bottomMargin = dp(4)
            })
            addView(sensitivityControl(
                label = "鼠标灵敏度",
                hint = "控制单指移动光标的速度",
                initial = prefs.getFloat(PREF_MOUSE_SENSITIVITY, 1f),
                maximum = 2f,
            ) { value -> prefs.edit().putFloat(PREF_MOUSE_SENSITIVITY, value).apply() })
            addView(sensitivityControl(
                label = "滚动灵敏度",
                hint = "控制双指自然滚动和抬手惯性的速度",
                initial = prefs.getFloat(PREF_TRACKPAD_SENSITIVITY, 1f),
                maximum = 4f,
            ) { value -> prefs.edit().putFloat(PREF_TRACKPAD_SENSITIVITY, value).apply() })
            addView(TextView(this@MainActivity).apply {
                text = "触控操作说明"
                textSize = 15f
                setPadding(0, dp(8), 0, dp(5))
            })
            addView(TextView(this@MainActivity).apply {
                text = "单指：移动、轻点左键、长按拖动\n" +
                    "双指：自然/惯性滚动、轻点右键、捏合缩放\n" +
                    "三/四指上下：Mission Control / App Exposé\n" +
                    "三/四指左右：切换桌面和全屏 App\n" +
                    "四指张开/捏合：桌面 / App 搜索；轻点：查询"
                textSize = 12f
                alpha = 0.72f
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, 0, 0, dp(4))
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("VibePad 设置")
            .setView(content)
            .setPositiveButton("完成", null)
            .setNegativeButton("退出 VibePad") { _, _ -> showExitConfirmation() }
            .create()
        dialog.setOnShowListener {
            dialog.window?.decorView?.systemUiVisibility = DIALOG_IMMERSIVE_FLAGS
        }
        dialog.show()
    }

    private fun showPairingInstructions(message: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("安全配对")
            .setMessage(message)
            .setPositiveButton("已在 Mac 上打开，继续") { _, _ -> wifiSink?.requestPairing() }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.decorView?.systemUiVisibility = DIALOG_IMMERSIVE_FLAGS
        }
        dialog.show()
    }

    private fun showPairingCode(code: String) {
        pairingCodeDialog?.dismiss()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val codeView = TextView(this).apply {
            text = code.chunked(3).joinToString("  ")
            textSize = 34f
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setPadding(dp(20), dp(24), dp(20), dp(18))
        }
        pairingCodeDialog = AlertDialog.Builder(this)
            .setTitle("核对验证码")
            .setMessage("确认 Mac 弹窗中的 6 位数字与下方完全一致，然后只在 Mac 上点击“允许”。")
            .setView(codeView)
            .setNegativeButton("取消", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.window?.decorView?.systemUiVisibility = DIALOG_IMMERSIVE_FLAGS
                }
                dialog.show()
            }
    }

    private fun sensitivityControl(
        label: String,
        hint: String,
        initial: Float,
        maximum: Float,
        onChanged: (Float) -> Unit,
    ): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val valueText = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 15f
                }, LinearLayout.LayoutParams(0, dp(22), 1f))
                addView(valueText, LinearLayout.LayoutParams(dp(52), dp(22)))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(22)))
            addView(TextView(this@MainActivity).apply {
                text = hint
                textSize = 11f
                alpha = 0.65f
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(18)))
            addView(SeekBar(this@MainActivity).apply {
                max = ((maximum - 0.5f) * 100).toInt()
                progress = ((initial.coerceIn(0.5f, maximum) - 0.5f) * 100).toInt()
                valueText.text = "%.1fx".format(0.5f + progress / 100f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val value = 0.5f + progress / 100f
                        valueText.text = "%.1fx".format(value)
                        if (fromUser) onChanged(value)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(30)))
        }
    }

    private fun showExitConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("退出 VibePad？")
            .setMessage("将中断触控和键盘连接，并恢复安卓系统栏。")
            .setPositiveButton("确认退出") { _, _ ->
                microphonePressHeld = false
                stopMicrophoneAndTypeless(MicrophoneStreamer.STOP_REASON_LIFECYCLE)
                inputSink.releaseAll()
                kioskController?.restoreSystemBars()
                finishAndRemoveTask()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        kioskController?.onWindowFocusChanged(hasFocus)
    }

    @Deprecated("Back is intentionally disabled in VibePad kiosk mode")
    override fun onBackPressed() = Unit

    override fun onPause() {
        microphonePressHeld = false
        if (microphoneStreamer?.isRecording == true || typelessSessionStarted) {
            stopMicrophoneAndTypeless(MicrophoneStreamer.STOP_REASON_LIFECYCLE)
        }
        inputSink.releaseAll()
        super.onPause()
    }

    override fun onDestroy() {
        microphoneStreamer?.stop(MicrophoneStreamer.STOP_REASON_LIFECYCLE)
        inputSink.releaseAll()
        wifiSink?.close()
        pairingCodeDialog?.dismiss()
        kioskController?.stop()
        super.onDestroy()
    }

    private companion object {
        const val PREF_MOUSE_SENSITIVITY = "mouse_sensitivity"
        const val PREF_TRACKPAD_SENSITIVITY = "trackpad_sensitivity"
        const val REQUEST_RECORD_AUDIO = 4101
        const val TYPELESS_START_DELAY_MS = 120L
        const val TYPELESS_TAP_MS = 90L
        const val DIALOG_IMMERSIVE_FLAGS =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}
