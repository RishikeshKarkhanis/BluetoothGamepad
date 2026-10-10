package com.bluetoothgamepad

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var registerButton: TextView
    private lateinit var statusDot: View

    private var hid: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private var registered = false
    private var connecting = false

    private var buttons = 0
    private var xAxis = 0
    private var yAxis = 0
    private var rxAxis = 0
    private var ryAxis = 0

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    // Keep the proven HID descriptor/report layout unchanged.
    private val descriptor = byteArrayOf(
        0x05, 0x01, 0x09, 0x05, 0xA1.toByte(), 0x01,
        0x85.toByte(), 0x01,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x10,
        0x15, 0x00, 0x25, 0x01, 0x95.toByte(), 0x10,
        0x75, 0x01, 0x81.toByte(), 0x02,
        0x05, 0x01,
        0x09, 0x30, // X
        0x09, 0x31, // Y
        0x09, 0x33, // Rx
        0x09, 0x34, // Ry
        0x15, 0x81.toByte(), 0x25, 0x7F,
        0x75, 0x08, 0x95.toByte(), 0x04,
        0x81.toByte(), 0x02, 0xC0.toByte()
    )

    private val bg = Color.rgb(13, 16, 23)
    private val panel = Color.rgb(24, 29, 39)
    private val muted = Color.rgb(151, 161, 179)
    private val accent = Color.rgb(86, 151, 255)

    private lateinit var pageRoot: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        showConnectionPage()
    }

    private fun makeRoot(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setBackgroundColor(bg)
    }

    private fun addBrand(root: LinearLayout, subtitle: String, showBack: Boolean = false) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = TextView(this).apply {
            text = "✦"; textSize = 24f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); background = rounded(Color.rgb(47, 58, 78), 14)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        row.addView(logo)
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        titleBox.addView(text("DUALSENSE", 17f, Color.WHITE, true))
        titleBox.addView(text(subtitle, 9f, muted, true).apply { letterSpacing = 0.13f })
        row.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        if (showBack) {
            val back = text("← CONNECTION", 10f, accent, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(9), dp(10), dp(9))
                background = rounded(Color.rgb(27, 47, 77), 18)
                setOnClickListener { showConnectionPage() }
            }
            row.addView(back)
        } else {
            row.addView(text("BETA", 10f, accent, true).apply {
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = rounded(Color.rgb(27, 47, 77), 20)
            })
        }
        root.addView(row)
    }

    private fun showConnectionPage() {
        val root = makeRoot()
        addBrand(root, "BLUETOOTH GAMEPAD")
        addGap(root, 18)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(24), dp(20), dp(24))
            background = rounded(panel, 24, Color.rgb(44, 52, 67), dp(1))
        }
        hero.addView(text("YOUR MAC. YOUR CONTROLLER.", 18f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
        addGap(hero, 8)
        hero.addView(text("Connect over Bluetooth HID, then switch to the\ncontroller screen to play.", 12f, muted, false).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER })
        root.addView(hero, LinearLayout.LayoutParams(-1, 0, 1f))
        addGap(root, 12)

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = rounded(panel, 18, Color.rgb(44, 52, 67), dp(1))
        }
        statusDot = View(this).apply {
            background = rounded(Color.rgb(239, 174, 74), 20)
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9))
        }
        statusCard.addView(statusDot)
        status = text("NOT CONNECTED", 12f, Color.WHITE, true).apply { setPadding(dp(10), 0, 0, 0) }
        statusCard.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        statusCard.addView(text("BLUETOOTH HID", 10f, muted, true))
        root.addView(statusCard)
        addGap(root, 8)

        registerButton = text("CONNECT TO MAC     →", 13f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = rounded(Color.rgb(42, 105, 211), 16)
            isClickable = true; isFocusable = true
            setOnClickListener { connectHid() }
        }
        root.addView(registerButton, LinearLayout.LayoutParams(-1, -2))
        addGap(root, 8)
        val openPad = text("OPEN CONTROLLER  →", 12f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.rgb(39, 44, 55), 16, Color.rgb(65, 75, 94), dp(1))
            setOnClickListener { showControllerPage() }
        }
        root.addView(openPad, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        pageRoot = root
    }

    private fun showControllerPage() {
        // Dedicated controller screen: full white canvas, no dark branding/header strip.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.WHITE)
        }

        val controllerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        root.addView(controllerPanel, LinearLayout.LayoutParams(-1, -1))

        val panelHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        panelHeader.addView(text("CONTROLLER", 11f, Color.rgb(70, 78, 92), true), LinearLayout.LayoutParams(0, -2, 1f))
        panelHeader.addView(text("● LIVE INPUT", 9f, Color.rgb(36, 132, 88), true))
        controllerPanel.addView(panelHeader)
        addGap(controllerPanel, 4)

        val touchpad = View(this).apply {
            background = rounded(Color.rgb(39, 44, 55), 20, Color.rgb(115, 122, 135), dp(1))
            layoutParams = LinearLayout.LayoutParams(dp(150), dp(36)).apply { gravity = Gravity.CENTER }
        }
        controllerPanel.addView(touchpad)
        addGap(controllerPanel, 5)

        val shoulderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        shoulderRow.addView(psPill("L2", 7))
        shoulderRow.addView(View(this), LinearLayout.LayoutParams(dp(6), dp(1)))
        shoulderRow.addView(psPill("L1", 5))
        shoulderRow.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f))
        shoulderRow.addView(psPill("R1", 6))
        shoulderRow.addView(View(this), LinearLayout.LayoutParams(dp(6), dp(1)))
        shoulderRow.addView(psPill("R2", 8))
        controllerPanel.addView(shoulderRow)
        addGap(controllerPanel, 4)

        val upper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dpadBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        // D-pad uses dedicated button bits 13-16; shoulder buttons remain separate.
        dpadBox.addView(psButton("↑", 13, Color.rgb(48, 55, 69), 34))
        val dpadMiddle = LinearLayout(this).apply { gravity = Gravity.CENTER }
        dpadMiddle.addView(psButton("←", 14, Color.rgb(48, 55, 69), 34))
        dpadMiddle.addView(View(this), LinearLayout.LayoutParams(dp(28), dp(28)))
        dpadMiddle.addView(psButton("→", 15, Color.rgb(48, 55, 69), 34))
        dpadBox.addView(dpadMiddle)
        dpadBox.addView(psButton("↓", 16, Color.rgb(48, 55, 69), 34))
        upper.addView(dpadBox, LinearLayout.LayoutParams(0, -2, 1f))

        val faceBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        faceBox.addView(psButton("△", 4, Color.rgb(53, 165, 125), 34))
        val faceMiddle = LinearLayout(this).apply { gravity = Gravity.CENTER }
        faceMiddle.addView(psButton("□", 3, Color.rgb(226, 91, 150), 34))
        faceMiddle.addView(View(this), LinearLayout.LayoutParams(dp(6), dp(6)))
        faceMiddle.addView(psButton("○", 2, Color.rgb(236, 93, 101), 34))
        faceBox.addView(faceMiddle)
        faceBox.addView(psButton("×", 1, Color.rgb(73, 143, 237), 34))
        upper.addView(faceBox, LinearLayout.LayoutParams(0, -2, 1f))
        controllerPanel.addView(upper)
        addGap(controllerPanel, 2)

        val sticks = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val leftStick = AnalogStick("L", true) { x, y ->
            xAxis = x
            yAxis = -y // Keep the GTA SA-tested mapping unchanged.
            sendInputReport()
        }
        val rightStick = AnalogStick("R", true) { x, y ->
            // Invert the previous right-stick mapping while preserving the axis swap.
            rxAxis = -y
            ryAxis = x
            sendInputReport()
        }
        sticks.addView(leftStick, LinearLayout.LayoutParams(0, -1, 1f))
        sticks.addView(rightStick, LinearLayout.LayoutParams(0, -1, 1f))
        controllerPanel.addView(sticks, LinearLayout.LayoutParams(-1, 0, 1f))
        addGap(controllerPanel, 2)

        val stickClickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        stickClickRow.addView(psPill("L3", 11))
        stickClickRow.addView(View(this), LinearLayout.LayoutParams(dp(18), dp(1)))
        stickClickRow.addView(psPill("SELECT", 9))
        stickClickRow.addView(View(this), LinearLayout.LayoutParams(dp(10), dp(1)))
        stickClickRow.addView(psPill("START", 10))
        stickClickRow.addView(View(this), LinearLayout.LayoutParams(dp(18), dp(1)))
        stickClickRow.addView(psPill("R3", 12))
        controllerPanel.addView(stickClickRow)

        setContentView(root)
        pageRoot = root
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null, strokeWidth: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (strokeColor != null && strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

    private fun text(value: String, size: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun addGap(parent: LinearLayout, height: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun psPill(label: String, number: Int): View = TextView(this).apply {
        text = label
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(54, 63, 79))
        background = rounded(Color.rgb(216, 221, 230), 14, Color.rgb(186, 193, 205), dp(1))
        setPadding(dp(15), dp(9), dp(15), dp(9))
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { setButton(number, true); view.alpha = 0.65f; true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { setButton(number, false); view.alpha = 1f; true }
                else -> true
            }
        }
    }

    private fun psButton(label: String, number: Int, color: Int, size: Int): View = FaceButton(label, number, color, size)

    private inner class FaceButton(
        private val label: String,
        private val number: Int,
        private val fillColor: Int,
        size: Int
    ) : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val side = dp(size)
        init { layoutParams = LinearLayout.LayoutParams(side, side).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) } }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height / 2f
            paint.color = Color.argb(35, 0, 0, 0); paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy + dp(2), min(width, height) * 0.46f, paint)
            paint.color = fillColor
            canvas.drawCircle(cx, cy, min(width, height) * 0.45f, paint)
            paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            paint.textSize = dp(21).toFloat()
            canvas.drawText(label, cx, cy - (paint.ascent() + paint.descent()) / 2f, paint)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { setButton(number, true); alpha = 0.72f; invalidate(); return true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { setButton(number, false); alpha = 1f; invalidate(); return true }
            }
            return true
        }
    }

    private inner class AnalogStick(
        private val label: String,
        private val lightTheme: Boolean,
        private val onAxis: (Int, Int) -> Unit
    ) : View(this@MainActivity) {
        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
        private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; textSize = dp(11).toFloat(); typeface = Typeface.DEFAULT_BOLD
        }
        private var dx = 0f
        private var dy = 0f
        private var activePointerId = MotionEvent.INVALID_POINTER_ID

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f + dp(5)
            val radius = min(width * 0.22f, height * 0.39f)
            val knobRadius = radius * 0.56f
            labelPaint.color = Color.rgb(87, 96, 112)
            canvas.drawText(if (label == "L") "LEFT STICK" else "RIGHT STICK", cx, dp(13).toFloat(), labelPaint)
            basePaint.color = Color.rgb(210, 216, 226)
            canvas.drawCircle(cx, cy, radius, basePaint)
            ringPaint.color = Color.rgb(160, 169, 184)
            canvas.drawCircle(cx, cy, radius, ringPaint)
            ringPaint.color = Color.rgb(181, 189, 201)
            canvas.drawLine(cx - radius, cy, cx + radius, cy, ringPaint)
            canvas.drawLine(cx, cy - radius, cx, cy + radius, ringPaint)
            basePaint.color = Color.rgb(170, 179, 193)
            canvas.drawCircle(cx + dx, cy + dy, knobRadius + dp(3), basePaint)
            knobPaint.color = Color.rgb(48, 55, 68)
            canvas.drawCircle(cx + dx, cy + dy, knobRadius, knobPaint)
            ringPaint.color = Color.rgb(91, 101, 119)
            canvas.drawCircle(cx + dx, cy + dy, knobRadius, ringPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val radius = min(width * 0.22f, height * 0.39f).coerceAtLeast(1f)

            fun resetStick() {
                dx = 0f
                dy = 0f
                activePointerId = MotionEvent.INVALID_POINTER_ID
                onAxis(0, 0)
                Log.d("GamepadStick", "$label released; neutral axes sent")
                invalidate()
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activePointerId = event.getPointerId(0)
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    // Keep tracking the original finger; extra fingers must not hijack the stick.
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex < 0) {
                        resetStick()
                        return true
                    }

                    var nx = event.getX(pointerIndex) - width / 2f
                    var ny = event.getY(pointerIndex) - (height / 2f + dp(5))
                    val distance = sqrt(nx * nx + ny * ny)
                    if (distance > radius) {
                        nx = nx / distance * radius
                        ny = ny / distance * radius
                    }
                    dx = nx
                    dy = ny

                    var x = (nx / radius * 127f).toInt().coerceIn(-127, 127)
                    var y = (-ny / radius * 127f).toInt().coerceIn(-127, 127)

                    // Small center deadzone prevents tiny touch offsets from drifting the camera.
                    if (sqrt((x * x + y * y).toFloat()) < 9f) {
                        x = 0
                        y = 0
                    }

                    Log.d("GamepadStick", "$label: x=$x, y=$y, pointer=$activePointerId")
                    onAxis(x, y)
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    val liftedPointerId = event.getPointerId(event.actionIndex)
                    if (liftedPointerId == activePointerId) {
                        resetStick()
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    resetStick()
                    return true
                }
            }
            return true
        }

    }

    private fun setButton(number: Int, pressed: Boolean) {
        val mask = 1 shl (number - 1)
        buttons = if (pressed) buttons or mask else buttons and mask.inv()
        sendInputReport()
    }

    private fun sendInputReport() {
        val device = connectedDevice ?: return
        val service = hid ?: return
        if (!registered) return
        val report = byteArrayOf(
            (buttons and 0xFF).toByte(),
            ((buttons ushr 8) and 0xFF).toByte(),
            xAxis.toByte(), yAxis.toByte(), rxAxis.toByte(), ryAxis.toByte()
        )
        try {
            val sent = service.sendReport(device, 1, report)
            Log.d("GamepadHID", "REPORT X=$xAxis Y=$yAxis Rx=$rxAxis Ry=$ryAxis sent=$sent")
            if (!sent) Log.w("GamepadHID", "sendReport returned false")
        } catch (e: Exception) {
            Log.e("GamepadHID", "Failed to send report", e)
        }
    }

    private fun connectHid() {
        if (connecting || registered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), 100)
                status.text = "Grant Bluetooth permissions, then tap connect again."
                return
            }
        }
        try {
            val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = manager.adapter
            if (adapter == null || !adapter.isEnabled) {
                status.text = "Bluetooth is off — enable it first."
                return
            }
            connecting = true
            registerButton.isEnabled = false
            registerButton.alpha = 0.7f
            status.text = "OPENING HID SERVICE…"
            statusDot.background = rounded(Color.rgb(239, 174, 74), 20)
            val accepted = adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile != BluetoothProfile.HID_DEVICE || proxy !is BluetoothHidDevice) {
                        connecting = false
                        handler.post { registerButton.isEnabled = true; registerButton.alpha = 1f; status.text = "HID PROFILE UNAVAILABLE" }
                        return
                    }
                    hid = proxy
                    registerGamepad()
                }
                override fun onServiceDisconnected(profile: Int) {
                    hid = null; connectedDevice = null; registered = false; connecting = false
                    handler.post {
                        registerButton.isEnabled = true; registerButton.alpha = 1f
                        status.text = "HID SERVICE DISCONNECTED"
                        statusDot.background = rounded(Color.rgb(230, 91, 101), 20)
                    }
                }
            }, BluetoothProfile.HID_DEVICE)
            if (!accepted) {
                connecting = false; registerButton.isEnabled = true; registerButton.alpha = 1f
                status.text = "COULD NOT OPEN HID SERVICE"
            }
        } catch (e: Exception) {
            connecting = false; registerButton.isEnabled = true; registerButton.alpha = 1f
            status.text = "BLUETOOTH ERROR: ${e.message}"
        }
    }

    private fun registerGamepad() {
        val service = hid ?: return
        try {
            val sdp = BluetoothHidDeviceAppSdpSettings("Bluetooth Gamepad", "Bluetooth gamepad for Mac", "Gamepad", 0x08, descriptor)
            val callback = object : BluetoothHidDevice.Callback() {
                override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registeredNow: Boolean) {
                    registered = registeredNow
                    connecting = false
                    handler.post {
                        registerButton.isEnabled = !registeredNow
                        registerButton.alpha = if (registeredNow) 0.55f else 1f
                        status.text = if (registeredNow) "REGISTERED • CONNECT FROM MAC BLUETOOTH" else "REGISTRATION STOPPED • TRY AGAIN"
                        statusDot.background = rounded(if (registeredNow) Color.rgb(57, 190, 130) else Color.rgb(239, 174, 74), 20)
                    }
                    if (registeredNow && pluggedDevice != null) connectedDevice = pluggedDevice
                }
                override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                    handler.post {
                        when (state) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                connectedDevice = device
                                status.text = "MAC CONNECTED • READY TO PLAY"
                                statusDot.background = rounded(Color.rgb(57, 190, 130), 20)
                                sendInputReport()
                            }
                            BluetoothProfile.STATE_CONNECTING -> {
                                status.text = "CONNECTING TO MAC…"
                                statusDot.background = rounded(Color.rgb(239, 174, 74), 20)
                            }
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                if (connectedDevice == device) connectedDevice = null
                                status.text = if (registered) "REGISTERED • WAITING FOR MAC" else "DISCONNECTED"
                                statusDot.background = rounded(Color.rgb(239, 174, 74), 20)
                            }
                        }
                    }
                }
            }
            val accepted = service.registerApp(sdp, null, null, executor, callback)
            if (accepted) status.text = "REGISTRATION REQUESTED…"
            else {
                connecting = false; registerButton.isEnabled = true; registerButton.alpha = 1f
                status.text = "REGISTRATION REJECTED • CHECK OTHER HID APPS"
            }
        } catch (e: Exception) {
            connecting = false; registerButton.isEnabled = true; registerButton.alpha = 1f
            status.text = "REGISTRATION ERROR: ${e.message}"
        }
    }

    override fun onDestroy() {
        try {
            hid?.let {
                (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it)
            }
        } catch (_: Exception) { }
        executor.shutdown()
        super.onDestroy()
    }
}