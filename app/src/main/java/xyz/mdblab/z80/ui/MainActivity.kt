package xyz.mdblab.z80.ui

import xyz.mdblab.z80.service.MdbService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import xyz.mdblab.z80.data.entities.Slot
import xyz.mdblab.z80.ui.MainViewModel
import xyz.mdblab.z80.ui.ProductAdapter
import java.util.Locale
import xyz.mdblab.z80.R
import androidx.lifecycle.lifecycleScope
import xyz.mdblab.z80.utils.RootHelper
import xyz.mdblab.z80.AdminReceiver
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: ProductAdapter
    
    // UI References
    private lateinit var headerLayout: android.view.View
    private lateinit var tvStatus: TextView
    private lateinit var tvConnectionStatus: TextView
    private lateinit var videoViewIdle: android.view.TextureView
    private var mediaPlayer: android.media.MediaPlayer? = null
    private lateinit var rvProducts: RecyclerView
    private lateinit var paymentSelectionLayout: android.view.View
    private lateinit var employeeLoginLayout: android.view.View
    private lateinit var outOfServiceLayout: android.view.View
    
    // HTTP Server
    private var syncServer: xyz.mdblab.z80.services.SyncServer? = null
    
    // Payment Panel Refs
    private lateinit var tvSelectedProductInfo: TextView
    private lateinit var btnPayCard: Button
    private lateinit var btnPayQr: Button
    private lateinit var btnEmployeeLogin: Button
    private lateinit var btnCancelSelection: Button
    
    // Login Panel Refs
    private lateinit var btnDoLogin: Button
    private lateinit var btnCancelLogin: Button
    private lateinit var etEmployeeId: android.widget.EditText
    private lateinit var tvEmployeeStatus: TextView

    // QR Display Refs
    private lateinit var qrDisplayLayout: android.view.View
    private lateinit var ivQrCode: ImageView
    private lateinit var tvQrProductInfo: TextView
    private lateinit var tvQrStatus: TextView
    private lateinit var pbQrWaiting: ProgressBar
    private lateinit var btnCancelQr: Button

    // Void/Refund Display Refs
    private lateinit var voidLayout: android.view.View
    private lateinit var ivVoidIcon: ImageView
    private lateinit var tvVoidTitle: TextView
    private lateinit var tvVoidMessage: TextView
    private lateinit var pbVoidProcessing: ProgressBar
    private lateinit var tvVoidStatus: TextView
    private lateinit var btnInitiateVoid: Button
    private lateinit var btnRetryVoid: Button
    private lateinit var btnCancelVoid: Button

    // Dispensing Display Refs
    private lateinit var dispensingLayout: android.view.View
    private lateinit var tvDispensingTitle: TextView
    private lateinit var tvDispensingProduct: TextView
    private lateinit var tvDispensingStatus: TextView
    private lateinit var tvDispensingSubtitle: TextView
    private lateinit var pbDispensing: ProgressBar
    private lateinit var ivDispensingIcon: ImageView

    // Countdown Timer Refs
    private lateinit var countdownLayout: android.view.View
    private lateinit var pbCountdown: ProgressBar
    private lateinit var tvCountdownSeconds: TextView

    // Card Tap Screen Refs
    private lateinit var pressButtonPosLayout: android.view.View
    private lateinit var cardTapLayout: android.view.View
    private lateinit var tvCardTapProduct: TextView
    private lateinit var ivCardIcon: ImageView
    private lateinit var ivContactless: ImageView
    private lateinit var ivPosTerminal: ImageView
    private lateinit var tvCardTapCountdown: TextView
    private lateinit var pbCardTapCountdown: ProgressBar
    private lateinit var tvCardTapStatus: TextView
    private lateinit var btnCancelCardTap: Button
    private var cardAnimator: android.animation.ObjectAnimator? = null

    private val mdbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "xyz.mdblab.z80.VM_EVENT") {
                val event = intent.getStringExtra("event") ?: "UNKNOWN"
                val extra = intent.getStringExtra("extra") ?: ""
                Log.d("MainActivity", "RX Broadcast: $event $extra")
                
                when (event) {
                    "PAY" -> {
                        val parts = extra.split(",")
                        val priceStr = parts.find { it.startsWith("price=") }?.substringAfter("=") ?: "0"
                        val rawItemStr = parts.find { it.startsWith("item=") }?.substringAfter("=") ?: "-1"
                        val rawItem = rawItemStr.toIntOrNull() ?: -1
                        val itemStr = if (rawItem != -1) (rawItem + 10).toString() else "-1"
                        viewModel.onMdbMessage("VEND_REQUEST: Item:$itemStr,Price:$priceStr")
                    }
                    "VEND_RESULT" -> {
                        if (extra == "VEND_SUCCESS") viewModel.onMdbMessage("VEND_SUCCESS")
                        else if (extra == "VEND_FAILURE") viewModel.onMdbMessage("VEND_FAILURE")
                    }
                    "STATUS" -> {
                        if (extra == "SESSION_IDLE") viewModel.onMdbMessage("SESSION_COMPLETE")
                    }
                }
            }
        }
    }

    // Kiosk Mode Variables
    private var secretClickCount = 0
    private var lastClickTime = 0L
    private val CLICK_RESET_TIME = 3000L // 3 seconds to click 7 times
    private val SECRET_TAP_COUNT = 7
    private val MAINTENANCE_PIN = "3561" 
    private lateinit var hiddenExitView: android.view.View
    private var isMaintenanceMode = false // Flag to prevent auto-re-entry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Register MDB receiver here (not in onResume) so it survives onPause —
        // UsbAutoGrantService can pop com.android.settings on top of us when it
        // needs to toggle wireless debugging / ethernet tethering, and we must
        // not lose VEND_REQUEST broadcasts during that ~25s window.
        val mdbFilter = IntentFilter(MdbService.ACTION_VM_EVENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(mdbReceiver, mdbFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mdbReceiver, mdbFilter)
        }

        // Initialize Views
        setupKioskExiter()
        headerLayout = findViewById(R.id.headerLayout)
        tvStatus = findViewById(R.id.tvStatus)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        videoViewIdle = findViewById(R.id.videoViewIdle)
        rvProducts = findViewById(R.id.rvProducts)
        paymentSelectionLayout = findViewById(R.id.paymentSelectionLayout)
        employeeLoginLayout = findViewById(R.id.employeeLoginLayout)
        outOfServiceLayout = findViewById(R.id.outOfServiceLayout)
        
        tvSelectedProductInfo = findViewById(R.id.tvSelectedProductInfo)
        btnPayCard = findViewById(R.id.btnPayCard)
        btnPayQr = findViewById(R.id.btnPayQr)
        btnEmployeeLogin = findViewById(R.id.btnEmployeeLogin)
        btnCancelSelection = findViewById(R.id.btnCancelSelection)
        btnDoLogin = findViewById(R.id.btnDoLogin)
        btnCancelLogin = findViewById(R.id.btnCancelLogin)
        etEmployeeId = findViewById(R.id.etEmployeeId)
        tvEmployeeStatus = findViewById(R.id.tvEmployeeStatus)

        // QR Display
        qrDisplayLayout = findViewById(R.id.qrDisplayLayout)
        ivQrCode = findViewById(R.id.ivQrCode)
        tvQrProductInfo = findViewById(R.id.tvQrProductInfo)
        tvQrStatus = findViewById(R.id.tvQrStatus)
        pbQrWaiting = findViewById(R.id.pbQrWaiting)
        btnCancelQr = findViewById(R.id.btnCancelQr)

        // Void/Refund Display
        voidLayout = findViewById(R.id.voidLayout)
        ivVoidIcon = findViewById(R.id.ivVoidIcon)
        tvVoidTitle = findViewById(R.id.tvVoidTitle)
        tvVoidMessage = findViewById(R.id.tvVoidMessage)
        pbVoidProcessing = findViewById(R.id.pbVoidProcessing)
        tvVoidStatus = findViewById(R.id.tvVoidStatus)
        btnInitiateVoid = findViewById(R.id.btnInitiateVoid)
        btnRetryVoid = findViewById(R.id.btnRetryVoid)
        btnCancelVoid = findViewById(R.id.btnCancelVoid)

        // Dispensing Display
        dispensingLayout = findViewById(R.id.dispensingLayout)
        tvDispensingTitle = findViewById(R.id.tvDispensingTitle)
        tvDispensingProduct = findViewById(R.id.tvDispensingProduct)
        tvDispensingStatus = findViewById(R.id.tvDispensingStatus)
        tvDispensingSubtitle = findViewById(R.id.tvDispensingSubtitle)
        pbDispensing = findViewById(R.id.pbDispensing)
        ivDispensingIcon = findViewById(R.id.ivDispensingIcon)

        // Countdown Timer
        countdownLayout = findViewById(R.id.countdownLayout)
        pbCountdown = findViewById(R.id.pbCountdown)
        tvCountdownSeconds = findViewById(R.id.tvCountdownSeconds)

        // Card Tap Screen
        pressButtonPosLayout = findViewById(R.id.pressButtonPosLayout)
        cardTapLayout = findViewById(R.id.cardTapLayout)
        tvCardTapProduct = findViewById(R.id.tvCardTapProduct)
        ivCardIcon = findViewById(R.id.ivCardIcon)
        ivContactless = findViewById(R.id.ivContactless)
        ivPosTerminal = findViewById(R.id.ivPosTerminal)
        tvCardTapCountdown = findViewById(R.id.tvCardTapCountdown)
        pbCardTapCountdown = findViewById(R.id.pbCardTapCountdown)
        tvCardTapStatus = findViewById(R.id.tvCardTapStatus)
        btnCancelCardTap = findViewById(R.id.btnCancelCardTap)

        // Setup RecyclerView
        adapter = ProductAdapter(emptyList()) { slot ->
            // Manual click from Grid (Debug capability)
            viewModel.selectSlot(slot)
        }
        rvProducts.layoutManager = GridLayoutManager(this, 3)
        rvProducts.adapter = adapter

        // Setup Video with TextureView for full-screen center-crop
        videoViewIdle.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                val cachedVideo = java.io.File(filesDir, "promo_video.mp4")
                val uri = if (cachedVideo.exists() && cachedVideo.length() > 0) {
                    android.net.Uri.fromFile(cachedVideo)
                } else {
                    android.net.Uri.parse("android.resource://$packageName/${R.raw.promo_video}")
                }
                playVideo(android.view.Surface(surface), uri)
            }
            override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, w: Int, h: Int) {
                mediaPlayer?.let { adjustVideoScale(it) }
            }
            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                mediaPlayer?.release()
                mediaPlayer = null
                return true
            }
            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
        }

        // Observe promo video updates from backend
        viewModel.promoVideoPath.observe(this) { path ->
            if (path != null) {
                val file = java.io.File(path)
                if (file.exists() && videoViewIdle.isAvailable) {
                    playVideo(android.view.Surface(videoViewIdle.surfaceTexture), android.net.Uri.fromFile(file))
                }
            }
        }

        // Observe ViewModel
        viewModel.statusText.observe(this) { status ->
            tvStatus.text = status
            if (qrDisplayLayout.visibility == android.view.View.VISIBLE) {
                tvQrStatus.text = status
                if (status.contains("Approved") || status.contains("Failed") || status.contains("Error")) {
                    pbQrWaiting.visibility = android.view.View.GONE
                }
            }
        }
        
        viewModel.slots.observe(this) { slots ->
            adapter.updateList(slots)
        }
        
        viewModel.isConnected.observe(this) { connected ->
            outOfServiceLayout.visibility = if (connected) android.view.View.GONE else android.view.View.VISIBLE
        }

        viewModel.uiState.observe(this) { state ->
            renderUiState(state)
        }

        // Observe Countdown State
        viewModel.countdownState.observe(this) { countdown ->
            val currentState = viewModel.uiState.value
            if (currentState is MainViewModel.UiState.ShowingCardTap && countdown.isActive) {
                pbCardTapCountdown.progress = (countdown.progress * 1000).toInt()
                tvCardTapCountdown.text = "${countdown.remainingSeconds}s remaining"
                val color = when {
                    countdown.remainingSeconds <= 10 -> Color.parseColor("#D32F2F") 
                    countdown.remainingSeconds <= 30 -> Color.parseColor("#FF9800") 
                    else -> getColor(R.color.teal_700) 
                }
                pbCardTapCountdown.progressTintList = android.content.res.ColorStateList.valueOf(color)
                tvCardTapCountdown.setTextColor(color)
            }
            else if (countdown.isActive && countdown.remainingSeconds > 0 &&
                     (currentState is MainViewModel.UiState.ProductSelected ||
                      currentState is MainViewModel.UiState.VoidPending ||
                      currentState is MainViewModel.UiState.RefundRequired)) {
                countdownLayout.visibility = android.view.View.VISIBLE
                pbCountdown.progress = (countdown.progress * 1000).toInt()
                tvCountdownSeconds.text = "${countdown.remainingSeconds}s restantes"
                val color = when {
                    countdown.remainingSeconds <= 5 -> Color.parseColor("#D32F2F") 
                    countdown.remainingSeconds <= 10 -> Color.parseColor("#FF9800") 
                    else -> getColor(R.color.teal_700) 
                }
                pbCountdown.progressTintList = android.content.res.ColorStateList.valueOf(color)
                tvCountdownSeconds.setTextColor(color)
            } else {
                countdownLayout.visibility = android.view.View.GONE
            }
        }

        // Button Listeners
        btnPayCard.setOnClickListener { viewModel.onPaymentMethodSelected("card") }
        btnPayQr.setOnClickListener { viewModel.onPaymentMethodSelected("qr") }
        btnEmployeeLogin.setOnClickListener { viewModel.onEmployeeLoginClicked() }
        btnCancelSelection.setOnClickListener { viewModel.onTimeout() }
        btnCancelLogin.setOnClickListener { viewModel.onTimeout() }
        btnDoLogin.setOnClickListener {
            val pin = etEmployeeId.text.toString().trim()
            if (pin.isNotEmpty()) {
                viewModel.submitEmployeePIN(pin)
            }
        }

        // Observe employee login status
        viewModel.employeeStatus.observe(this) { status ->
            if (status != null) {
                tvEmployeeStatus.text = status
                tvEmployeeStatus.visibility = android.view.View.VISIBLE
            } else {
                tvEmployeeStatus.visibility = android.view.View.GONE
            }
        } 
        btnCancelQr.setOnClickListener { viewModel.onPaymentCancelled() } 
        btnCancelCardTap.setOnClickListener { viewModel.onPaymentCancelled() } 

        // Void/Refund Listeners
        btnInitiateVoid.setOnClickListener { viewModel.initiateRefund() }
        btnRetryVoid.setOnClickListener { viewModel.initiateRefund() } 
        btnCancelVoid.setOnClickListener { viewModel.onVoidCancelled() } 

        // Start MDB Service
        startMdbService()
        
        // Start Sync Server
        try {
            val dao = xyz.mdblab.z80.data.AppDatabase.getDatabase(application).vendingDao()
            val repo = xyz.mdblab.z80.data.repository.VendingRepository(dao)
            syncServer = xyz.mdblab.z80.services.SyncServer(repo, kotlinx.coroutines.GlobalScope)
            syncServer?.start()
            Log.i("MainActivity", "SyncServer started on port 3001")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start SyncServer", e)
        }
        
    }
    
    private fun setupKioskExiter() {
        val rootView = findViewById<android.view.ViewGroup>(android.R.id.content)
        hiddenExitView = android.view.View(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(200, 200) 
            setBackgroundColor(Color.TRANSPARENT) 
            setOnClickListener {
                handleSecretClick()
            }
        }
        rootView.addView(hiddenExitView)
    }

    private fun handleSecretClick() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime > CLICK_RESET_TIME) {
            secretClickCount = 0
        }
        
        lastClickTime = currentTime
        secretClickCount++
        
        if (secretClickCount >= SECRET_TAP_COUNT) {
            showMaintenancePinDialog()
            secretClickCount = 0
        }
    }

    private fun showMaintenancePinDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter Maintenance PIN"
        }
        
        AlertDialog.Builder(this)
            .setTitle("Maintenance Mode")
            .setView(input)
            .setPositiveButton("Enter") { _, _ ->
                val pin = input.text.toString()
                if (pin == MAINTENANCE_PIN) {
                    showMaintenanceActionsDialog()
                } else {
                    Toast.makeText(this, "Invalid PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMaintenanceActionsDialog() {
        val actions = arrayOf("Exit Kiosk Mode", "Open Settings", "Reset Network (Root)", "Restart ADB (5555)")
        
        AlertDialog.Builder(this)
            .setTitle("Maintenance Actions")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> exitKioskMode()
                    1 -> {
                        exitKioskMode()
                        startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                    }
                    2 -> {
                        Toast.makeText(this, "Applying Root configs...", Toast.LENGTH_LONG).show()
                        lifecycleScope.launch {
                            val success = RootHelper.setupFullNetwork()
                            val msg = if (success) "Network Reset OK" else "Network Reset FAILED"
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                    3 -> {
                         Toast.makeText(this, "Restarting ADB...", Toast.LENGTH_SHORT).show()
                         lifecycleScope.launch {
                             RootHelper.restartAdbd()
                         }
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun exitKioskMode() {
        try {
            stopLockTask()
            isMaintenanceMode = true
            window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
            Toast.makeText(this, "Maintenance Mode: Unlocked", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to stop lock task", e)
             Toast.makeText(this, "Not in Lock Task mode", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enterKioskMode() {
        if (isMaintenanceMode) return

        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val componentName = android.content.ComponentName(this, AdminReceiver::class.java)
            
            if (dpm.isDeviceOwnerApp(packageName)) {
                dpm.setLockTaskPackages(componentName, arrayOf(packageName, "com.android.settings", "com.llamalab.automate", "com.android.systemui"))
                startLockTask()
            } else {
                 startLockTask()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to enter kiosk mode", e)
        }
    }
    
    // mdbReceiver is now registered in onCreate / unregistered in onDestroy so it survives
    // the onPause that fires whenever UsbAutoGrantService pops com.android.settings.

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isMaintenanceMode) {
            hideSystemUI()
            if (::hiddenExitView.isInitialized) {
                hiddenExitView.postDelayed({ enterKioskMode() }, 500)
            }
        }
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    private fun renderUiState(state: MainViewModel.UiState) {
        val rootView = findViewById<android.view.ViewGroup>(android.R.id.content)
        android.transition.TransitionManager.beginDelayedTransition(rootView)

        qrDisplayLayout.visibility = android.view.View.GONE
        voidLayout.visibility = android.view.View.GONE
        cardTapLayout.visibility = android.view.View.GONE
        dispensingLayout.visibility = android.view.View.GONE

        stopCardTapAnimation()

        val showCountdown = state is MainViewModel.UiState.ProductSelected ||
                            state is MainViewModel.UiState.VoidPending ||
                            state is MainViewModel.UiState.RefundRequired
        if (!showCountdown) {
            countdownLayout.visibility = android.view.View.GONE
        }

        when (state) {
            is MainViewModel.UiState.Idle -> {
                headerLayout.visibility = android.view.View.GONE
                videoViewIdle.visibility = android.view.View.VISIBLE
                paymentSelectionLayout.visibility = android.view.View.GONE
                employeeLoginLayout.visibility = android.view.View.GONE
                rvProducts.visibility = android.view.View.GONE 

                if (mediaPlayer?.isPlaying != true) {
                    mediaPlayer?.start()
                }
            }
            is MainViewModel.UiState.ProductSelected -> {
                headerLayout.visibility = android.view.View.VISIBLE
                videoViewIdle.visibility = android.view.View.GONE
                paymentSelectionLayout.visibility = android.view.View.VISIBLE
                employeeLoginLayout.visibility = android.view.View.GONE
                rvProducts.visibility = android.view.View.GONE 

                val discount = viewModel.getActiveDiscount()
                if (discount > 0) {
                    val discounted = state.slot.price * (1 - discount / 100.0)
                    val discountedFormatted = String.format("%.2f", discounted)
                    tvSelectedProductInfo.text = "${state.slot.productName} - $${discountedFormatted} (${discount}% dto - ${viewModel.getEmployeeName() ?: ""})"
                } else {
                    tvSelectedProductInfo.text = "${state.slot.productName} - $${state.slot.price}"
                }
            }
            is MainViewModel.UiState.ShowingQR -> {
                headerLayout.visibility = android.view.View.GONE
                videoViewIdle.visibility = android.view.View.GONE
                paymentSelectionLayout.visibility = android.view.View.GONE
                employeeLoginLayout.visibility = android.view.View.GONE
                rvProducts.visibility = android.view.View.GONE
                qrDisplayLayout.visibility = android.view.View.VISIBLE

                tvQrProductInfo.text = "${state.slot.productName} - $${state.slot.price}"
                tvQrStatus.text = "Waiting for payment..."
                pbQrWaiting.visibility = android.view.View.VISIBLE

                generateQrCode(state.qrData)?.let { bitmap ->
                    ivQrCode.setImageBitmap(bitmap)
                } ?: run {
                    Log.e("MainActivity", "Failed to generate QR code")
                    tvQrStatus.text = "Error generating QR code"
                }
            }
            is MainViewModel.UiState.RefundRequired -> {
                headerLayout.visibility = android.view.View.GONE
                videoViewIdle.visibility = android.view.View.GONE
                paymentSelectionLayout.visibility = android.view.View.GONE
                employeeLoginLayout.visibility = android.view.View.GONE
                rvProducts.visibility = android.view.View.GONE
                cardTapLayout.visibility = android.view.View.GONE
                pressButtonPosLayout.visibility = android.view.View.GONE
                dispensingLayout.visibility = android.view.View.GONE
                voidLayout.visibility = android.view.View.VISIBLE

                tvVoidTitle.text = "⚠️ Producto No Dispensado"
                tvVoidMessage.text = "🔄 INSTRUCCIONES DE REINTEGRO:\n\n" +
                        "⚠️ Usá LA MISMA TARJETA\n\n" +
                        "📟 1. Presioná BOTÓN VERDE del POS\n" +
                        "💳 2. Acercá tu tarjeta\n" +
                        "✅ 3. Confirmá con BOTÓN VERDE\n\n" +
                        "Apretá 'Iniciar Devolución' cuando estés listo"
                ivVoidIcon.setImageResource(android.R.drawable.ic_dialog_alert)
                ivVoidIcon.setColorFilter(Color.parseColor("#FF9800"))
                pbVoidProcessing.visibility = android.view.View.GONE
                tvVoidStatus.visibility = android.view.View.GONE
                btnInitiateVoid.visibility = android.view.View.VISIBLE
                btnInitiateVoid.text = "Iniciar Devolución"
                btnRetryVoid.visibility = android.view.View.GONE
                btnCancelVoid.visibility = android.view.View.VISIBLE
                btnCancelVoid.text = "Cancelar"
            }
            is MainViewModel.UiState.RefundInstructions -> {
                headerLayout.visibility = android.view.View.GONE
                videoViewIdle.visibility = android.view.View.GONE
                paymentSelectionLayout.visibility = android.view.View.GONE
                employeeLoginLayout.visibility = android.view.View.GONE
                rvProducts.visibility = android.view.View.GONE
                cardTapLayout.visibility = android.view.View.GONE
                pressButtonPosLayout.visibility = android.view.View.GONE
                dispensingLayout.visibility = android.view.View.GONE
                voidLayout.visibility = android.view.View.GONE

                // Reuse the PressButtonOnPOS layout with refund styling
                pressButtonPosLayout.visibility = android.view.View.VISIBLE
                pressButtonPosLayout.setBackgroundColor(Color.parseColor("#FFF3E0"))

                findViewById<TextView>(R.id.tvPressButtonTitle).apply {
                    text = "Presioná el botón VERDE\nen el POS"
                    setTextColor(Color.parseColor("#E65100"))
                }
                findViewById<TextView>(R.id.tvPressButtonSubtitle).apply {
                    text = "para iniciar la devolución"
                    setTextColor(Color.parseColor("#FF9800"))
                }
                findViewById<TextView>(R.id.tvPressButtonProductInfo).text = "Luego acercá tu tarjeta"
                findViewById<TextView>(R.id.tvWaitingPosButton).apply {
                    text = "Esperando..."
                    setTextColor(Color.parseColor("#E65100"))
                }
                findViewById<ProgressBar>(R.id.pbWaitingPosButton).indeterminateTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#FF9800"))
                findViewById<android.widget.ImageView>(R.id.ivHandPointing).apply {
                    setColorFilter(Color.parseColor("#E65100"))
                    val bounce = android.view.animation.TranslateAnimation(0f, 0f, 0f, 30f).apply {
                        duration = 600
                        repeatCount = android.view.animation.Animation.INFINITE
                        repeatMode = android.view.animation.Animation.REVERSE
                    }
                    startAnimation(bounce)
                }
            }
            is MainViewModel.UiState.VoidPending -> {
                headerLayout.visibility = android.view.View.GONE
                videoViewIdle.visibility = android.view.View.GONE
                paymentSelectionLayout.visibility = android.view.View.GONE
                employeeLoginLayout.visibility = android.view.View.GONE
                rvProducts.visibility = android.view.View.GONE
                cardTapLayout.visibility = android.view.View.GONE
                pressButtonPosLayout.visibility = android.view.View.GONE
                dispensingLayout.visibility = android.view.View.GONE
                voidLayout.visibility = android.view.View.VISIBLE

                tvVoidTitle.text = "Error al Dispensar"
                tvVoidMessage.text = "Tu pago fue procesado pero el producto no pudo ser dispensado.\n\nPara iniciar el reintegro:\n1. Apretá el botón verde del POS\n2. Acercá la misma tarjeta con la que pagaste\n3. Apretá 'Iniciar Devolución' cuando estés listo\n\nTendrás 30 segundos para completar el proceso."
                ivVoidIcon.setImageResource(android.R.drawable.ic_dialog_alert)
                ivVoidIcon.setColorFilter(Color.parseColor("#FF9800"))
                pbVoidProcessing.visibility = android.view.View.GONE
                tvVoidStatus.visibility = android.view.View.GONE
                btnInitiateVoid.visibility = android.view.View.VISIBLE
                btnRetryVoid.visibility = android.view.View.GONE
                btnCancelVoid.visibility = android.view.View.VISIBLE
            }
            is MainViewModel.UiState.VoidProcessing -> {
                headerLayout.visibility = android.view.View.GONE
                videoViewIdle.visibility = android.view.View.GONE
                paymentSelectionLayout.visibility = android.view.View.GONE
                employeeLoginLayout.visibility = android.view.View.GONE
                rvProducts.visibility = android.view.View.GONE
                cardTapLayout.visibility = android.view.View.GONE
                pressButtonPosLayout.visibility = android.view.View.GONE
                dispensingLayout.visibility = android.view.View.GONE
                voidLayout.visibility = android.view.View.VISIBLE

                tvVoidTitle.text = "Procesando Devolución"
                tvVoidMessage.text = "Acercá tu tarjeta al terminal POS cuando se te indique."
                pbVoidProcessing.visibility = android.view.View.VISIBLE
                tvVoidStatus.visibility = android.view.View.VISIBLE
                tvVoidStatus.text = "Esperando tarjeta..."
                btnInitiateVoid.visibility = android.view.View.GONE
                btnRetryVoid.visibility = android.view.View.GONE
                btnCancelVoid.visibility = android.view.View.GONE
            }
            is MainViewModel.UiState.VoidSuccess -> {
                headerLayout.visibility = android.view.View.GONE
                videoViewIdle.visibility = android.view.View.GONE
                paymentSelectionLayout.visibility = android.view.View.GONE
                employeeLoginLayout.visibility = android.view.View.GONE
                rvProducts.visibility = android.view.View.GONE
                cardTapLayout.visibility = android.view.View.GONE
                pressButtonPosLayout.visibility = android.view.View.GONE
                dispensingLayout.visibility = android.view.View.GONE
                voidLayout.visibility = android.view.View.VISIBLE

                tvVoidTitle.text = "Devolución Completada"
                tvVoidMessage.text = state.message
                ivVoidIcon.setImageResource(android.R.drawable.ic_dialog_info)
                ivVoidIcon.setColorFilter(Color.parseColor("#4CAF50"))
                pbVoidProcessing.visibility = android.view.View.GONE
                tvVoidStatus.visibility = android.view.View.VISIBLE
                tvVoidStatus.text = "El dinero será devuelto a tu tarjeta."
                btnInitiateVoid.visibility = android.view.View.GONE
                btnRetryVoid.visibility = android.view.View.GONE
                btnCancelVoid.visibility = android.view.View.GONE
            }
            is MainViewModel.UiState.VoidFailed -> {
                headerLayout.visibility = android.view.View.GONE
                videoViewIdle.visibility = android.view.View.GONE
                paymentSelectionLayout.visibility = android.view.View.GONE
                employeeLoginLayout.visibility = android.view.View.GONE
                rvProducts.visibility = android.view.View.GONE
                cardTapLayout.visibility = android.view.View.GONE
                pressButtonPosLayout.visibility = android.view.View.GONE
                dispensingLayout.visibility = android.view.View.GONE
                voidLayout.visibility = android.view.View.VISIBLE

                tvVoidTitle.text = "Error en Devolución"
                tvVoidMessage.text = state.message
                ivVoidIcon.setImageResource(android.R.drawable.ic_delete)
                ivVoidIcon.setColorFilter(Color.parseColor("#D32F2F"))
                pbVoidProcessing.visibility = android.view.View.GONE
                tvVoidStatus.visibility = android.view.View.GONE
                btnInitiateVoid.visibility = android.view.View.GONE
                btnRetryVoid.visibility = if (state.canRetry) android.view.View.VISIBLE else android.view.View.GONE
                btnCancelVoid.visibility = android.view.View.VISIBLE
            }
            is MainViewModel.UiState.EmployeeLogin -> {
                headerLayout.visibility = android.view.View.VISIBLE
                videoViewIdle.visibility = android.view.View.GONE
                paymentSelectionLayout.visibility = android.view.View.GONE
                employeeLoginLayout.visibility = android.view.View.VISIBLE
                etEmployeeId.text.clear()
                tvEmployeeStatus.visibility = android.view.View.GONE
            }
            is MainViewModel.UiState.PressButtonOnPOS -> {
                headerLayout.visibility = android.view.View.GONE
                videoViewIdle.visibility = android.view.View.GONE
                paymentSelectionLayout.visibility = android.view.View.GONE
                employeeLoginLayout.visibility = android.view.View.GONE
                rvProducts.visibility = android.view.View.GONE
                cardTapLayout.visibility = android.view.View.GONE
                pressButtonPosLayout.visibility = android.view.View.GONE
                pressButtonPosLayout.visibility = android.view.View.VISIBLE

                findViewById<TextView>(R.id.tvPressButtonProductInfo).text =
                    "${state.slot.productName} - $${state.slot.price}"

                // Animate the hand icon bouncing
                val handIcon = findViewById<android.widget.ImageView>(R.id.ivHandPointing)
                val bounce = android.view.animation.TranslateAnimation(0f, 0f, 0f, 30f).apply {
                    duration = 600
                    repeatCount = android.view.animation.Animation.INFINITE
                    repeatMode = android.view.animation.Animation.REVERSE
                }
                handIcon.startAnimation(bounce)
            }
            is MainViewModel.UiState.ShowingCardTap -> {
                headerLayout.visibility = android.view.View.GONE
                videoViewIdle.visibility = android.view.View.GONE
                paymentSelectionLayout.visibility = android.view.View.GONE
                employeeLoginLayout.visibility = android.view.View.GONE
                rvProducts.visibility = android.view.View.GONE
                pressButtonPosLayout.visibility = android.view.View.GONE
                cardTapLayout.visibility = android.view.View.VISIBLE

                tvCardTapProduct.text = "${state.slot.productName} - $${state.slot.price}"
                tvCardTapStatus.text = "Acerca tu tarjeta..."

                startCardTapAnimation()
            }
            is MainViewModel.UiState.Dispensing -> {
                headerLayout.visibility = android.view.View.GONE
                videoViewIdle.visibility = android.view.View.GONE
                paymentSelectionLayout.visibility = android.view.View.GONE
                employeeLoginLayout.visibility = android.view.View.GONE
                rvProducts.visibility = android.view.View.GONE
                cardTapLayout.visibility = android.view.View.GONE
                pressButtonPosLayout.visibility = android.view.View.GONE
                voidLayout.visibility = android.view.View.GONE
                dispensingLayout.visibility = android.view.View.VISIBLE

                tvDispensingTitle.text = "✅ ¡Pago Aprobado!"
                tvDispensingProduct.text = "${state.slot.productName} - $${state.slot.price}"
                tvDispensingStatus.text = "🎁 Dispensando tu producto..."
                tvDispensingSubtitle.text = "⏳ Por favor esperá mientras preparamos tu producto..."
                pbDispensing.visibility = android.view.View.VISIBLE
                ivDispensingIcon.setImageResource(android.R.drawable.ic_menu_send)
                ivDispensingIcon.setColorFilter(Color.parseColor("#4CAF50"))
            }
            is MainViewModel.UiState.DispenseSuccess -> {
                headerLayout.visibility = android.view.View.GONE
                videoViewIdle.visibility = android.view.View.GONE
                paymentSelectionLayout.visibility = android.view.View.GONE
                employeeLoginLayout.visibility = android.view.View.GONE
                rvProducts.visibility = android.view.View.GONE
                cardTapLayout.visibility = android.view.View.GONE
                pressButtonPosLayout.visibility = android.view.View.GONE
                voidLayout.visibility = android.view.View.GONE
                dispensingLayout.visibility = android.view.View.VISIBLE

                tvDispensingTitle.text = "¡Listo!"
                tvDispensingProduct.text = state.slot.productName
                tvDispensingStatus.text = "¡Producto dispensado!"
                tvDispensingSubtitle.text = "¡Gracias por su compra!"
                pbDispensing.visibility = android.view.View.GONE
                ivDispensingIcon.setImageResource(android.R.drawable.ic_menu_agenda)
                ivDispensingIcon.setColorFilter(Color.parseColor("#2E7D32"))
            }
            is MainViewModel.UiState.DispenseFailed -> {
                headerLayout.visibility = android.view.View.GONE
                videoViewIdle.visibility = android.view.View.GONE
                paymentSelectionLayout.visibility = android.view.View.GONE
                employeeLoginLayout.visibility = android.view.View.GONE
                rvProducts.visibility = android.view.View.GONE
                cardTapLayout.visibility = android.view.View.GONE
                pressButtonPosLayout.visibility = android.view.View.GONE
                dispensingLayout.visibility = android.view.View.GONE
                voidLayout.visibility = android.view.View.VISIBLE

                tvVoidTitle.text = "Error al Dispensar"
                ivVoidIcon.setImageResource(android.R.drawable.ic_dialog_alert)
                ivVoidIcon.setColorFilter(Color.parseColor("#FF9800"))
                pbVoidProcessing.visibility = android.view.View.GONE
                tvVoidStatus.visibility = android.view.View.GONE
                btnInitiateVoid.visibility = android.view.View.VISIBLE
                btnRetryVoid.visibility = android.view.View.GONE
                btnCancelVoid.visibility = android.view.View.VISIBLE
            }
            is MainViewModel.UiState.ProcessingPayment -> {
                headerLayout.visibility = android.view.View.VISIBLE
                paymentSelectionLayout.visibility = android.view.View.GONE
                tvStatus.text = "Processing Payment..."
            }
            else -> {}
        }
    }

    private fun generateQrCode(data: String, size: Int = 512): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            Log.e("MainActivity", "QR generation error", e)
            null
        }
    }

    override fun onPause() {
        super.onPause()
        // mdbReceiver stays registered through onPause (see onCreate for the rationale).
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(mdbReceiver) } catch (_: Exception) {}
        mediaPlayer?.release()
        mediaPlayer = null
        syncServer?.stop()
    }

    private fun playVideo(surface: android.view.Surface, uri: android.net.Uri) {
        mediaPlayer?.release()
        mediaPlayer = android.media.MediaPlayer().apply {
            setSurface(surface)
            setDataSource(this@MainActivity, uri)
            isLooping = true
            setOnPreparedListener { mp ->
                adjustVideoScale(mp)
                mp.start()
            }
            prepareAsync()
        }
    }

    private fun adjustVideoScale(mp: android.media.MediaPlayer) {
        val videoWidth = mp.videoWidth.toFloat()
        val videoHeight = mp.videoHeight.toFloat()
        val viewWidth = videoViewIdle.width.toFloat()
        val viewHeight = videoViewIdle.height.toFloat()

        if (videoWidth == 0f || videoHeight == 0f || viewWidth == 0f || viewHeight == 0f) return

        val scaleX: Float
        val scaleY: Float

        // Center-crop: scale to fill, then center
        val videoAspect = videoWidth / videoHeight
        val viewAspect = viewWidth / viewHeight

        if (videoAspect > viewAspect) {
            // Video is wider — scale by height, crop sides
            scaleX = (viewHeight * videoAspect) / viewWidth
            scaleY = 1f
        } else {
            // Video is taller — scale by width, crop top/bottom
            scaleX = 1f
            scaleY = (viewWidth / videoAspect) / viewHeight
        }

        val matrix = android.graphics.Matrix()
        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
        videoViewIdle.setTransform(matrix)
    }

    private fun startMdbService() {
        val intent = Intent(this, MdbService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private val activeAnimators = mutableListOf<android.animation.Animator>()

    private fun startCardTapAnimation() {
        stopCardTapAnimation()

        val contactlessScaleX = android.animation.ObjectAnimator.ofFloat(
            ivContactless, "scaleX", 1f, 1.2f, 1f
        ).apply {
            duration = 1200
            repeatCount = android.animation.ObjectAnimator.INFINITE
        }
        contactlessScaleX.start()
        activeAnimators.add(contactlessScaleX)

        val contactlessScaleY = android.animation.ObjectAnimator.ofFloat(
            ivContactless, "scaleY", 1f, 1.2f, 1f
        ).apply {
            duration = 1200
            repeatCount = android.animation.ObjectAnimator.INFINITE
        }
        contactlessScaleY.start()
        activeAnimators.add(contactlessScaleY)

        val contactlessAlpha = android.animation.ObjectAnimator.ofFloat(
            ivContactless, "alpha", 1f, 0.5f, 1f
        ).apply {
            duration = 1200
            repeatCount = android.animation.ObjectAnimator.INFINITE
        }
        contactlessAlpha.start()
        activeAnimators.add(contactlessAlpha)

        val cardMoveX = android.animation.ObjectAnimator.ofFloat(
            ivCardIcon, "translationX",
            0f, 120f, 140f, 140f, 120f, 0f  
        ).apply {
            duration = 2400
            repeatCount = android.animation.ObjectAnimator.INFINITE
        }
        cardMoveX.start()
        activeAnimators.add(cardMoveX)

        val cardMoveY = android.animation.ObjectAnimator.ofFloat(
            ivCardIcon, "translationY",
            0f, -120f, -140f, -140f, -120f, 0f 
        ).apply {
            duration = 2400
            repeatCount = android.animation.ObjectAnimator.INFINITE
        }
        cardMoveY.start()
        activeAnimators.add(cardMoveY)
        
        val cardAlpha = android.animation.ObjectAnimator.ofFloat(
            ivCardIcon, "alpha",
            0f, 1f, 1f, 1f, 0f 
        ).apply {
            duration = 2400
            repeatCount = android.animation.ObjectAnimator.INFINITE
        }
        cardAlpha.start()
        activeAnimators.add(cardAlpha)
    }

    private fun stopCardTapAnimation() {
        for (animator in activeAnimators) {
            animator.cancel()
        }
        activeAnimators.clear()

        // Reset properties
        ivContactless.scaleX = 1f
        ivContactless.scaleY = 1f
        ivContactless.alpha = 1f

        ivCardIcon.translationX = 0f
        ivCardIcon.translationY = 0f
        ivCardIcon.alpha = 0f
    }

}
