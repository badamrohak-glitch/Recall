
package com.recall.app

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.min

class FloatingRecallService : Service() {

    private lateinit var windowManager: WindowManager

    private var floatingView: LinearLayout? = null
    private var logoView: ImageView? = null
    private var logoContainer: FrameLayout? = null
    private var closeButton: TextView? = null
    private var menuView: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var expanded = false
    private var isDismissing = false
    private var dockedLeft = false
    private var editMode = false

    private var panelWidthAnimator: ValueAnimator? = null

    private val longPressHandler = Handler(Looper.getMainLooper())

    private val positionPreferences by lazy {
        getSharedPreferences(
            "recall_floating_position",
            Context.MODE_PRIVATE
        )
    }

    private val purple = Color.rgb(192, 132, 252)

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        windowManager =
            getSystemService(Context.WINDOW_SERVICE) as WindowManager

        dockedLeft = positionPreferences.getBoolean("left_side", false)

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                screenOffReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenOffReceiver, filter)
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_TOGGLE -> {
                if (floatingView != null) {
                    dismissFloatingButton()
                } else {
                    showFloatingButton()
                }
            }

            else -> {
                if (floatingView == null) {
                    showFloatingButton()
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun dp(value: Float): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun getSafeY(value: Int): Int {

        val screenHeight = resources.displayMetrics.heightPixels

        val minimumY = dp(100f)
        val maximumY = (screenHeight - dp(166f)).coerceAtLeast(minimumY)

        return value.coerceIn(minimumY, maximumY)
    }

    private fun saveDockPosition() {

        layoutParams?.let { params ->

            positionPreferences.edit()
                .putBoolean("left_side", dockedLeft)
                .putInt("vertical_position", params.y)
                .apply()
        }
    }

    private fun arrangeChildrenForSide(
        root: LinearLayout,
        logoFrame: FrameLayout,
        menu: LinearLayout
    ) {

        root.removeAllViews()

        val iconViews = (0 until menu.childCount).map {
            menu.getChildAt(it)
        }

        val desiredOrder = if (dockedLeft) {
            listOf(
                MenuIcon.FOLDER,
                MenuIcon.SEARCH,
                MenuIcon.PRINT_SCREEN
            )
        } else {
            listOf(
                MenuIcon.PRINT_SCREEN,
                MenuIcon.SEARCH,
                MenuIcon.FOLDER
            )
        }

        val orderedViews = desiredOrder.mapNotNull { item ->
            iconViews.firstOrNull { it.tag == item }
        }

        menu.removeAllViews()

        orderedViews.forEach { child ->
            menu.addView(child)
        }

        val logoParams = LinearLayout.LayoutParams(
            dp(62f),
            dp(62f)
        )

        if (dockedLeft) {
            root.addView(menu)
            root.addView(logoFrame, logoParams)
        } else {
            root.addView(logoFrame, logoParams)
            root.addView(menu)
        }
    }

    private fun updateDockSide(newLeftSide: Boolean) {

        if (dockedLeft == newLeftSide) return

        val root = floatingView ?: return
        val logoFrame = logoContainer ?: return
        val menu = menuView ?: return
        val params = layoutParams ?: return

        dockedLeft = newLeftSide

        arrangeChildrenForSide(
            root,
            logoFrame,
            menu
        )

        params.gravity = Gravity.TOP or
                if (dockedLeft) Gravity.LEFT else Gravity.RIGHT

        params.x = dp(6f)

        root.background = if (expanded) {
            createExpandedPanelBackground()
        } else {
            createPanelBackground()
        }

        try {
            windowManager.updateViewLayout(root, params)
        } catch (_: Exception) {
        }
    }

    private fun installDragListener(logoFrame: FrameLayout) {

        val touchSlop = dp(8f)

        var downRawX = 0f
        var downRawY = 0f
        var startingY = 0
        var dragging = false
        var longPressTriggered = false

        var pendingLongPress: Runnable? = null

        logoFrame.setOnTouchListener { view, event ->

            val params = layoutParams
                ?: return@setOnTouchListener false

            when (event.action) {

                MotionEvent.ACTION_DOWN -> {

                    downRawX = event.rawX
                    downRawY = event.rawY

                    startingY = params.y

                    dragging = false
                    longPressTriggered = false

                    pendingLongPress?.let {
                        longPressHandler.removeCallbacks(it)
                    }

                    val longPressAction = Runnable {

                        if (!dragging && !isDismissing) {

                            longPressTriggered = true
                            editMode = true

                            closeButton?.visibility = View.VISIBLE

                            closeButton?.animate()
                                ?.alpha(1f)
                                ?.scaleX(1f)
                                ?.scaleY(1f)
                                ?.setDuration(180)
                                ?.start()
                        }
                    }

                    pendingLongPress = longPressAction

                    longPressHandler.postDelayed(
                        longPressAction,
                        350L
                    )

                    true
                }

                MotionEvent.ACTION_MOVE -> {

                    val deltaX = event.rawX - downRawX
                    val deltaY = event.rawY - downRawY

                    if (!dragging &&
                        (
                                abs(deltaX) > touchSlop ||
                                        abs(deltaY) > touchSlop
                                )
                    ) {

                        dragging = true

                        pendingLongPress?.let {
                            longPressHandler.removeCallbacks(it)
                        }

                        if (expanded) {
                            collapseMenuForDragging()
                        }
                    }

                    if (dragging) {

                        val screenWidth =
                            resources.displayMetrics.widthPixels

                        val newLeftSide =
                            event.rawX < screenWidth / 2f

                        updateDockSide(newLeftSide)

                        params.y = getSafeY(
                            startingY + deltaY.toInt()
                        )

                        try {
                            windowManager.updateViewLayout(
                                floatingView,
                                params
                            )
                        } catch (_: Exception) {
                        }
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {

                    pendingLongPress?.let {
                        longPressHandler.removeCallbacks(it)
                    }

                    if (dragging) {

                        val screenWidth =
                            resources.displayMetrics.widthPixels

                        updateDockSide(
                            event.rawX < screenWidth / 2f
                        )

                        params.y = getSafeY(params.y)

                        try {
                            windowManager.updateViewLayout(
                                floatingView,
                                params
                            )
                        } catch (_: Exception) {
                        }

                        saveDockPosition()

                    } else if (longPressTriggered) {

                        // Long press reveals the close button.

                    } else {

                        view.performClick()
                    }

                    dragging = false

                    true
                }

                MotionEvent.ACTION_CANCEL -> {

                    pendingLongPress?.let {
                        longPressHandler.removeCallbacks(it)
                    }

                    dragging = false

                    true
                }

                else -> true
            }
        }

        logoFrame.setOnClickListener {

            if (editMode) {
                hideCloseButton()
            } else {
                toggleMenu()
            }
        }
    }

    private fun hideCloseButton() {

        editMode = false

        closeButton?.animate()
            ?.alpha(0f)
            ?.scaleX(0.7f)
            ?.scaleY(0.7f)
            ?.setDuration(140)
            ?.withEndAction {
                closeButton?.visibility = View.GONE
            }
            ?.start()
    }

    private fun showFloatingButton() {

        if (floatingView != null || isDismissing) return

        val root = LinearLayout(this).apply {

            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            background = createPanelBackground()

            clipChildren = false
            clipToPadding = false

            alpha = 0.35f
            scaleX = 0.72f
            scaleY = 0.72f
        }

        val logoBackground = GradientDrawable().apply {

            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(27f).toFloat()

            setColor(
                Color.argb(85, 192, 132, 252)
            )
        }

        val logoDrawable = InsetDrawable(
            getDrawable(R.mipmap.ic_launcher),
            dp(27f)
        )

        val logo = ImageView(this).apply {

            setImageDrawable(logoDrawable)

            scaleType = ImageView.ScaleType.FIT_CENTER

            background = logoBackground

            setPadding(0, 0, 0, 0)

            elevation = dp(5f).toFloat()

            alpha = 1f
        }

        val logoFrame = FrameLayout(this).apply {

            clipChildren = false
            clipToPadding = false

            addView(
                logo,
                FrameLayout.LayoutParams(
                    dp(54f),
                    dp(54f),
                    Gravity.CENTER
                )
            )
        }

        val close = TextView(this).apply {

            text = "×"
            textSize = 20f

            setTextColor(Color.WHITE)

            gravity = Gravity.CENTER
            includeFontPadding = false

            background = GradientDrawable().apply {

                shape = GradientDrawable.OVAL

                setColor(Color.rgb(45, 25, 65))

                setStroke(
                    dp(1.5f),
                    purple
                )
            }

            elevation = dp(10f).toFloat()

            alpha = 0f

            scaleX = 0.7f
            scaleY = 0.7f

            visibility = View.GONE

            isClickable = true
            isFocusable = true

            contentDescription = "Close Recall popup"

            setOnClickListener {
                dismissFloatingButton()
            }
        }

        val closeParams = FrameLayout.LayoutParams(
            dp(23f),
            dp(23f),
            Gravity.TOP or Gravity.END
        )

        logoFrame.addView(
            close,
            closeParams
        )

        val menu = LinearLayout(this).apply {

            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            visibility = View.GONE
            alpha = 0f

            clipChildren = false
            clipToPadding = false
        }

        val menuItems = listOf(
            MenuIcon.PRINT_SCREEN,
            MenuIcon.SEARCH,
            MenuIcon.FOLDER
        )

        menuItems.forEach { item ->

            val iconView = OutlineIconView(
                this,
                item
            )

            val iconContainer = FrameLayout(this).apply {

                tag = item

                clipChildren = false
                clipToPadding = false

                addView(
                    iconView,
                    FrameLayout.LayoutParams(
                        dp(24f),
                        dp(24f),
                        Gravity.CENTER
                    )
                )

                setOnClickListener {
                    handleMenuClick(item)
                }
            }

            val itemParams = LinearLayout.LayoutParams(
                dp(46f),
                dp(56f)
            )

            menu.addView(
                iconContainer,
                itemParams
            )
        }

        arrangeChildrenForSide(
            root,
            logoFrame,
            menu
        )

        installDragListener(logoFrame)

        val savedY = positionPreferences.getInt(
            "vertical_position",
            (
                    resources.displayMetrics.heightPixels * 0.25f
                    ).toInt()
        )

        val params = WindowManager.LayoutParams(
            dp(62f),
            dp(62f),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {

            gravity = Gravity.TOP or
                    if (dockedLeft) Gravity.LEFT else Gravity.RIGHT

            x = dp(6f)

            y = getSafeY(savedY)
        }

        try {

            windowManager.addView(root, params)

            floatingView = root
            logoView = logo
            logoContainer = logoFrame
            closeButton = close
            menuView = menu
            layoutParams = params

            root.animate()
                .scaleX(1.08f)
                .scaleY(1.08f)
                .alpha(1f)
                .setDuration(300)
                .setInterpolator(OvershootInterpolator(1.4f))
                .withEndAction {

                    root.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(160)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                .start()

        } catch (e: Exception) {

            Toast.makeText(
                this,
                "Unable to show Recall floating icon.",
                Toast.LENGTH_SHORT
            ).show()

            stopSelf()
        }
    }

    private fun createPanelBackground(): GradientDrawable {

        return GradientDrawable().apply {

            shape = GradientDrawable.RECTANGLE

            cornerRadius = dp(24f).toFloat()

            setColor(
                Color.argb(22, 25, 15, 42)
            )

            setStroke(
                dp(1f),
                Color.argb(125, 192, 132, 252)
            )
        }
    }

    private fun createExpandedPanelBackground(): GradientDrawable {

        return GradientDrawable().apply {

            shape = GradientDrawable.RECTANGLE

            cornerRadius = dp(24f).toFloat()

            setColor(
                Color.argb(38, 25, 15, 42)
            )

            setStroke(
                dp(1f),
                Color.argb(150, 192, 132, 252)
            )
        }
    }

    private fun animatePanelWidth(
        root: LinearLayout,
        params: WindowManager.LayoutParams,
        startWidth: Int,
        endWidth: Int,
        duration: Long,
        onFinished: (() -> Unit)? = null
    ) {

        panelWidthAnimator?.cancel()

        val animator = ValueAnimator.ofInt(
            startWidth,
            endWidth
        )

        panelWidthAnimator = animator

        animator.duration = duration
        animator.interpolator = DecelerateInterpolator()

        animator.addUpdateListener { valueAnimator ->

            params.width = valueAnimator.animatedValue as Int

            try {
                windowManager.updateViewLayout(
                    root,
                    params
                )
            } catch (_: Exception) {
            }
        }

        animator.addListener(
            object : AnimatorListenerAdapter() {

                override fun onAnimationEnd(animation: Animator) {

                    if (panelWidthAnimator === animator) {
                        panelWidthAnimator = null
                        onFinished?.invoke()
                    }
                }
            }
        )

        animator.start()
    }

    private fun toggleMenu() {

        val root = floatingView ?: return
        val menu = menuView ?: return
        val logo = logoView ?: return
        val params = layoutParams ?: return

        if (isDismissing) return

        panelWidthAnimator?.cancel()
        panelWidthAnimator = null

        menu.animate().cancel()

        if (!expanded) {

            expanded = true

            val screenWidth =
                resources.displayMetrics.widthPixels

            val requiredWidth = dp(
                62f + (3 * 46f)
            )

            val maxExpandedWidth =
                (screenWidth - dp(24f)).coerceAtLeast(dp(62f))

            val expandedWidth = min(
                requiredWidth,
                maxExpandedWidth
            ).coerceAtLeast(dp(62f))

            val availableMenuWidth =
                expandedWidth - dp(62f)

            val itemWidth =
                (availableMenuWidth / 3).coerceAtLeast(1)

            val iconSize = min(
                dp(24f),
                itemWidth
            )

            for (i in 0 until menu.childCount) {

                val child = menu.getChildAt(i)

                child.layoutParams =
                    LinearLayout.LayoutParams(
                        itemWidth,
                        dp(56f)
                    )

                val icon =
                    (child as FrameLayout).getChildAt(0)

                icon.layoutParams =
                    FrameLayout.LayoutParams(
                        iconSize,
                        iconSize,
                        Gravity.CENTER
                    )
            }

            menu.pivotX = if (dockedLeft) {
                availableMenuWidth.toFloat()
            } else {
                0f
            }

            menu.pivotY = dp(28f).toFloat()

            menu.scaleX = 0.01f
            menu.alpha = 0f
            menu.visibility = View.VISIBLE

            params.width = dp(62f)
            params.height = dp(62f)

            root.background =
                createExpandedPanelBackground()

            try {

                windowManager.updateViewLayout(
                    root,
                    params
                )

            } catch (_: Exception) {

                expanded = false
                menu.visibility = View.GONE

                return
            }

            menu.animate()
                .alpha(1f)
                .scaleX(1f)
                .setDuration(320)
                .setInterpolator(DecelerateInterpolator())
                .start()

            animatePanelWidth(
                root = root,
                params = params,
                startWidth = dp(62f),
                endWidth = expandedWidth,
                duration = 320
            )

            logo.background = GradientDrawable().apply {

                shape = GradientDrawable.RECTANGLE

                cornerRadius = dp(27f).toFloat()

                setColor(
                    Color.argb(110, 192, 132, 252)
                )
            }

        } else {

            expanded = false

            val currentWidth = params.width

            menu.pivotX = if (dockedLeft) {
                menu.width.toFloat()
            } else {
                0f
            }

            menu.pivotY = dp(28f).toFloat()

            menu.animate()
                .alpha(0f)
                .scaleX(0.01f)
                .setDuration(240)
                .setInterpolator(DecelerateInterpolator())
                .start()

            animatePanelWidth(
                root = root,
                params = params,
                startWidth = currentWidth,
                endWidth = dp(62f),
                duration = 240
            ) {

                menu.visibility = View.GONE

                menu.alpha = 0f
                menu.scaleX = 1f

                for (i in 0 until menu.childCount) {

                    val child = menu.getChildAt(i)

                    child.layoutParams =
                        LinearLayout.LayoutParams(
                            dp(46f),
                            dp(56f)
                        )

                    val icon =
                        (child as FrameLayout).getChildAt(0)

                    icon.layoutParams =
                        FrameLayout.LayoutParams(
                            dp(24f),
                            dp(24f),
                            Gravity.CENTER
                        )
                }

                params.width = dp(62f)
                params.height = dp(62f)

                try {

                    windowManager.updateViewLayout(
                        root,
                        params
                    )

                } catch (_: Exception) {
                }

                root.background =
                    createPanelBackground()
            }

            logo.background = GradientDrawable().apply {

                shape = GradientDrawable.RECTANGLE

                cornerRadius = dp(27f).toFloat()

                setColor(
                    Color.argb(85, 192, 132, 252)
                )
            }
        }
    }

    private fun collapseMenuForDragging() {

        val root = floatingView ?: return
        val menu = menuView ?: return
        val logo = logoView ?: return
        val params = layoutParams ?: return

        expanded = false

        panelWidthAnimator?.cancel()
        panelWidthAnimator = null

        menu.animate().cancel()

        menu.alpha = 0f
        menu.translationX = 0f
        menu.scaleX = 1f
        menu.visibility = View.GONE

        for (i in 0 until menu.childCount) {

            val child = menu.getChildAt(i)

            child.layoutParams =
                LinearLayout.LayoutParams(
                    dp(46f),
                    dp(56f)
                )

            val icon =
                (child as FrameLayout).getChildAt(0)

            icon.layoutParams =
                FrameLayout.LayoutParams(
                    dp(24f),
                    dp(24f),
                    Gravity.CENTER
                )
        }

        params.width = dp(62f)
        params.height = dp(62f)

        root.background =
            createPanelBackground()

        logo.background = GradientDrawable().apply {

            shape = GradientDrawable.RECTANGLE

            cornerRadius = dp(27f).toFloat()

            setColor(
                Color.argb(85, 192, 132, 252)
            )
        }

        try {

            windowManager.updateViewLayout(
                root,
                params
            )

        } catch (_: Exception) {
        }
    }

    private fun handleMenuClick(item: MenuIcon) {

        when (item) {

            MenuIcon.PRINT_SCREEN -> {

                dismissFloatingButton {

                    val captureIntent = Intent(
                        this@FloatingRecallService,
                        MainActivity::class.java
                    ).apply {

                        action =
                            MainActivity.ACTION_REQUEST_SCREEN_CAPTURE

                        flags =
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }

                    startActivity(captureIntent)
                }
            }

            MenuIcon.FOLDER -> {

                dismissFloatingButton {

                    val libraryIntent = Intent(
                        this@FloatingRecallService,
                        MainActivity::class.java
                    ).apply {

                        action =
                            MainActivity.ACTION_OPEN_LIBRARY

                        flags =
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }

                    startActivity(libraryIntent)
                }
            }

            MenuIcon.SEARCH -> {

                dismissFloatingButton {

                    val searchIntent = Intent(
                        this@FloatingRecallService,
                        MainActivity::class.java
                    ).apply {

                        action =
                            MainActivity.ACTION_OPEN_SEARCH

                        flags =
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }

                    startActivity(searchIntent)
                }
            }
        }
    }

    private fun dismissFloatingButton(
        onFinished: (() -> Unit)? = null
    ) {

        val root = floatingView

        if (root == null) {

            onFinished?.invoke()

            stopSelf()

            return
        }

        if (isDismissing) return

        isDismissing = true

        longPressHandler.removeCallbacksAndMessages(null)

        panelWidthAnimator?.cancel()
        panelWidthAnimator = null

        root.animate().cancel()

        root.animate()
            .scaleX(0.72f)
            .scaleY(0.72f)
            .alpha(0.35f)
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {

                onFinished?.invoke()

                stopSelf()
            }
            .start()
    }

    override fun onDestroy() {

        saveDockPosition()

        longPressHandler.removeCallbacksAndMessages(null)

        panelWidthAnimator?.cancel()
        panelWidthAnimator = null

        try {
            unregisterReceiver(screenOffReceiver)
        } catch (e: Exception) {
        }

        floatingView?.let { view ->

            try {

                view.animate().cancel()

                windowManager.removeView(view)

            } catch (e: Exception) {
            }
        }

        floatingView = null
        logoView = null
        logoContainer = null
        closeButton = null
        menuView = null
        layoutParams = null

        expanded = false
        editMode = false
        isDismissing = false

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {

        const val ACTION_TOGGLE =
            "com.recall.app.FLOATING_TOGGLE"
    }
}

private enum class MenuIcon {
    PRINT_SCREEN,
    SEARCH,
    FOLDER
}

private class OutlineIconView(
    context: Context,
    private val icon: MenuIcon
) : View(context) {

    private val density =
        resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {

        color = Color.WHITE

        style = Paint.Style.STROKE

        strokeWidth = 1.7f * density

        strokeCap = Paint.Cap.ROUND

        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {

        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f

        canvas.save()

        canvas.translate(
            centerX,
            centerY
        )

        when (icon) {

            MenuIcon.PRINT_SCREEN ->
                drawCropIcon(canvas)

            MenuIcon.SEARCH ->
                drawSearchIcon(canvas)

            MenuIcon.FOLDER ->
                drawFolderIcon(canvas)
        }

        canvas.restore()
    }

    private fun drawCropIcon(canvas: Canvas) {

        val path = Path().apply {

            moveTo(
                -6f * density,
                -10f * density
            )

            lineTo(
                -6f * density,
                6f * density
            )

            lineTo(
                10f * density,
                6f * density
            )

            moveTo(
                -10f * density,
                -6f * density
            )

            lineTo(
                6f * density,
                -6f * density
            )

            lineTo(
                6f * density,
                10f * density
            )
        }

        canvas.drawPath(path, paint)
    }

    private fun drawSearchIcon(canvas: Canvas) {

        canvas.drawCircle(
            -2f * density,
            -2f * density,
            7f * density,
            paint
        )

        canvas.drawLine(
            3f * density,
            3f * density,
            9f * density,
            9f * density,
            paint
        )
    }

    // Corrected folder icon: centered and raised.
    private fun drawFolderIcon(canvas: Canvas) {

        val path = Path().apply {

            moveTo(
                -9f * density,
                -6f * density
            )

            lineTo(
                -3f * density,
                -6f * density
            )

            lineTo(
                0f * density,
                -3f * density
            )

            lineTo(
                9f * density,
                -3f * density
            )

            quadTo(
                10f * density,
                -3f * density,
                10f * density,
                -2f * density
            )

            lineTo(
                9f * density,
                7f * density
            )

            quadTo(
                9f * density,
                8f * density,
                8f * density,
                8f * density
            )

            lineTo(
                -8f * density,
                8f * density
            )

            quadTo(
                -9f * density,
                8f * density,
                -9f * density,
                7f * density
            )

            close()
        }

        canvas.drawPath(path, paint)
    }
}
