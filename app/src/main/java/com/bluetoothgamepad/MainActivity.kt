package com.bluetoothgamepad

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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
    private lateinit var registerButton: Button

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

    // Report ID 1: 16 buttons + X, Y, Rx, Ry.
    private val descriptor = byteArrayOf(
        0x05, 0x01,
        0x09, 0x05,
        0xA1.toByte(), 0x01,
        0x85.toByte(), 0x01,

        0x05, 0x09,
        0x19, 0x01,
        0x29, 0x10,
        0x15, 0x00,
        0x25, 0x01,
        0x95.toByte(), 0x10,
        0x75, 0x01,
        0x81.toByte(), 0x02,

        0x05, 0x01,
        0x09, 0x30, // X
        0x09, 0x31, // Y
        0x09, 0x33, // Rx
        0x09, 0x34, // Ry
        0x15, 0x81.toByte(),
        0x25, 0x7F,
        0x75, 0x08,
        0x95.toByte(), 0x04,
        0x81.toByte(), 0x02,
        0xC0.toByte()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(18, 20, 27)
        window.navigationBarColor = Color.rgb(18, 20, 27)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(14, 14, 14, 12)
            setBackgroundColor(Color.rgb(24, 27, 35))
        }

        status = TextView(this).apply {
            text = "BLUETOOTH GAMEPAD • NOT CONNECTED"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(4, 8, 4, 8)
        }
        root.addView(status)

        registerButton = Button(this).apply {
            text = "REGISTER / CONNECT"
            setOnClickListener { connectHid() }
        }
        root.addView(registerButton)

        root.addView(View(this), LinearLayout.LayoutParams(1, 12))

        // LEFT STICK + D-PAD
        val leftPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val leftStick = AnalogStick("LEFT STICK") { x, y ->
            xAxis = x
            yAxis = -y
            sendInputReport()
        }

        leftPanel.addView(
            leftStick,
            LinearLayout.LayoutParams(0, 220, 1f)
        )

        val dpad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        dpad.addView(gameButton("▲", 5))

        val dpadMiddle = LinearLayout(this).apply {
            gravity = Gravity.CENTER
        }

        dpadMiddle.addView(gameButton("◀", 7))
        dpadMiddle.addView(gameButton("●", 11))
        dpadMiddle.addView(gameButton("▶", 8))

        dpad.addView(dpadMiddle)
        dpad.addView(gameButton("▼", 6))

        leftPanel.addView(
            dpad,
            LinearLayout.LayoutParams(0, 220, 1f)
        )

        root.addView(leftPanel)

        // SELECT + START
        val centerButtons = LinearLayout(this).apply {
            gravity = Gravity.CENTER
        }

        centerButtons.addView(gameButton("SELECT", 9))
        centerButtons.addView(gameButton("START", 10))
        root.addView(centerButtons)

        // RIGHT STICK + FACE BUTTONS
        val rightPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val rightStick = AnalogStick("RIGHT STICK") { x, y ->
            rxAxis = -y
            ryAxis = x
            sendInputReport()
        }

        rightPanel.addView(
            rightStick,
            LinearLayout.LayoutParams(0, 220, 1f)
        )

        val face = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        face.addView(gameButton("Y", 4))

        val faceMiddle = LinearLayout(this).apply {
            gravity = Gravity.CENTER
        }

        faceMiddle.addView(gameButton("X", 3))
        faceMiddle.addView(gameButton("B", 2))
        face.addView(faceMiddle)
        face.addView(gameButton("A", 1))

        rightPanel.addView(
            face,
            LinearLayout.LayoutParams(0, 220, 1f)
        )

        root.addView(rightPanel)

        val note = TextView(this).apply {
            text = "Hold buttons • Drag sticks • Release to recenter"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(4, 8, 4, 4)
        }

        root.addView(note)
        setContentView(root)
    }

    private fun gameButton(label: String, number: Int): Button {
        return Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            minWidth = 48
            minHeight = 48

            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        setButton(number, true)
                        view.isPressed = true
                        true
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        setButton(number, false)
                        view.isPressed = false
                        true
                    }

                    else -> true
                }
            }
        }
    }

    private fun setButton(number: Int, pressed: Boolean) {
        val mask = 1 shl (number - 1)

        buttons = if (pressed) {
            buttons or mask
        } else {
            buttons and mask.inv()
        }

        sendInputReport()
    }

    private fun sendInputReport() {
        val device = connectedDevice ?: return
        val service = hid ?: return
        if (!registered) return

        // Keep this exact order: buttons, X, Y, Rx, Ry.
        val report = byteArrayOf(
            (buttons and 0xFF).toByte(),
            ((buttons ushr 8) and 0xFF).toByte(),
            xAxis.toByte(),
            yAxis.toByte(),
            rxAxis.toByte(),
            ryAxis.toByte()
        )

        try {
            val sent = service.sendReport(device, 1, report)
            Log.d("GamepadHID","X=$xAxis Y=$yAxis Rx=$rxAxis Ry=$ryAxis sent=$sent")

            if (!sent) {
                Log.w("GamepadHID", "sendReport returned false")
            }
        } catch (e: Exception) {
            Log.e("GamepadHID", "Failed to send report", e)
        }
    }

    private inner class AnalogStick(
        private val label: String,
        private val onAxis: (Int, Int) -> Unit
    ) : View(this@MainActivity) {

        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(57, 63, 77)
        }

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(115, 128, 151)
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(60, 145, 255)
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY
            textSize = 28f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        private var dx = 0f
        private var dy = 0f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val cx = width / 2f
            val cy = height / 2f
            val radius = min(width, height) * 0.31f
            val knobRadius = radius * 0.42f

            canvas.drawText(label, cx, 22f, textPaint)
            canvas.drawCircle(cx, cy, radius, basePaint)
            canvas.drawCircle(cx, cy, radius, ringPaint)

            canvas.drawLine(
                cx - radius, cy,
                cx + radius, cy,
                ringPaint
            )

            canvas.drawLine(
                cx, cy - radius,
                cx, cy + radius,
                ringPaint
            )

            canvas.drawCircle(
                cx + dx, cy + dy,
                knobRadius, knobPaint
            )
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val radius = (min(width, height) * 0.31f)
                .coerceAtLeast(1f)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> {
                    var nx = event.x - width / 2f
                    var ny = event.y - height / 2f

                    val distance = sqrt(nx * nx + ny * ny)

                    if (distance > radius) {
                        nx = nx / distance * radius
                        ny = ny / distance * radius
                    }

                    dx = nx
                    dy = ny

                    // Explicit independent horizontal and vertical values.
                    val x = (nx / radius * 127f)
                        .toInt().coerceIn(-127, 127)

                    val y = (-ny / radius * 127f)
                        .toInt().coerceIn(-127, 127)

                    Log.d(
                        "GamepadStick",
                        "$label: x=$x, y=$y"
                    )

                    onAxis(x, y)
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    dx = 0f
                    dy = 0f

                    Log.d("GamepadStick", "$label: released")

                    onAxis(0, 0)
                    invalidate()
                    return true
                }
            }

            return true
        }
    }

    private fun connectHid() {
        if (connecting || registered) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )

            val missing = permissions.filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }

            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), 100)
                status.text = "Grant Bluetooth permissions, then register again."
                return
            }
        }

        try {
            val manager =
                getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = manager.adapter

            if (adapter == null || !adapter.isEnabled) {
                status.text = "Enable Bluetooth first."
                return
            }

            connecting = true
            registerButton.isEnabled = false
            status.text = "Opening Bluetooth HID service..."

            val accepted = adapter.getProfileProxy(
                this,
                object : BluetoothProfile.ServiceListener {

                    override fun onServiceConnected(
                        profile: Int,
                        proxy: BluetoothProfile
                    ) {
                        if (
                            profile != BluetoothProfile.HID_DEVICE ||
                            proxy !is BluetoothHidDevice
                        ) {
                            connecting = false
                            registerButton.isEnabled = true
                            status.text = "HID Device profile unavailable."
                            return
                        }

                        hid = proxy
                        registerGamepad()
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        hid = null
                        connectedDevice = null
                        registered = false
                        connecting = false

                        handler.post {
                            registerButton.isEnabled = true
                            status.text = "HID service disconnected."
                        }
                    }
                },
                BluetoothProfile.HID_DEVICE
            )

            if (!accepted) {
                connecting = false
                registerButton.isEnabled = true
                status.text = "Could not open HID service."
            }
        } catch (e: Exception) {
            connecting = false
            registerButton.isEnabled = true
            status.text = "Bluetooth error: ${e.message}"
        }
    }

    private fun registerGamepad() {
        val service = hid ?: return

        try {
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "Bluetooth Gamepad",
                "Bluetooth gamepad for Mac",
                "Gamepad",
                0x08,
                descriptor
            )

            val callback = object : BluetoothHidDevice.Callback() {

                override fun onAppStatusChanged(
                    pluggedDevice: BluetoothDevice?,
                    registeredNow: Boolean
                ) {
                    registered = registeredNow
                    connecting = false

                    handler.post {
                        registerButton.isEnabled = !registeredNow

                        status.text = if (registeredNow) {
                            "REGISTERED • Connect from Mac Bluetooth settings"
                        } else {
                            "Registration stopped. Check for another HID app."
                        }
                    }

                    if (registeredNow && pluggedDevice != null) {
                        connectedDevice = pluggedDevice
                    }
                }

                override fun onConnectionStateChanged(
                    device: BluetoothDevice?,
                    state: Int
                ) {
                    handler.post {
                        when (state) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                connectedDevice = device
                                status.text = "MAC CONNECTED • Gamepad ready"
                                sendInputReport()
                            }

                            BluetoothProfile.STATE_CONNECTING -> {
                                status.text = "Connecting to Mac..."
                            }

                            BluetoothProfile.STATE_DISCONNECTED -> {
                                if (connectedDevice == device) {
                                    connectedDevice = null
                                }

                                status.text = if (registered) {
                                    "Registered • Waiting for Mac"
                                } else {
                                    "Disconnected"
                                }
                            }
                        }
                    }
                }
            }

            val accepted = service.registerApp(
                sdp, null, null, executor, callback
            )

            if (accepted) {
                status.text = "Registration requested..."
            } else {
                connecting = false
                registerButton.isEnabled = true
                status.text =
                    "Registration rejected. Another HID app may be active."
            }
        } catch (e: Exception) {
            connecting = false
            registerButton.isEnabled = true
            status.text = "Registration error: ${e.message}"
        }
    }

    override fun onDestroy() {
        try {
            hid?.let {
                (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager)
                    .adapter?.closeProfileProxy(
                        BluetoothProfile.HID_DEVICE,
                        it
                    )
            }
        } catch (_: Exception) {
        }

        executor.shutdown()
        super.onDestroy()
    }
}