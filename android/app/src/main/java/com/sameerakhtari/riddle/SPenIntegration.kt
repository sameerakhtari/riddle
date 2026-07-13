package com.sameerakhtari.riddle

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.sameerakhtari.riddle.data.AppSettings
import com.sameerakhtari.riddle.data.PenButtonAction
import com.sameerakhtari.riddle.logging.AppLog
import kotlin.math.abs

/** Adds first-run guidance and in-app S Pen actions without Samsung-private APIs. */
class SPenIntegration(private val application: Application) : Application.ActivityLifecycleCallbacks {
    private var startX = 0f
    private var startY = 0f
    private var startedAt = 0L
    private var wasPressed = false
    private var gestureSent = false

    fun register() {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity) return
        val settings = AppSettings(activity)
        if (!settings.guideSeen) {
            settings.guideSeen = true
            activity.startActivity(Intent(activity, GuideActivity::class.java))
            return
        }
        activity.window.decorView.setOnGenericMotionListener { _, event -> handle(activity, event) }
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity is MainActivity) activity.window.decorView.setOnGenericMotionListener(null)
        reset()
    }

    private fun handle(activity: Activity, event: MotionEvent): Boolean {
        if (event.pointerCount == 0 || event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return false
        val settings = AppSettings(activity)
        val pressed = event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0

        when (event.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS -> begin(event)
            MotionEvent.ACTION_HOVER_MOVE -> {
                if (pressed && !wasPressed) begin(event)
                if (pressed && wasPressed && settings.spenHoverGestures && !gestureSent) {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    val threshold = 72f * activity.resources.displayMetrics.density
                    if (abs(dx) > threshold || abs(dy) > threshold) {
                        gestureSent = true
                        when {
                            abs(dx) >= abs(dy) && dx < 0 -> click(activity, R.id.undoButton, "hover left: undo")
                            abs(dx) >= abs(dy) -> click(activity, R.id.redoButton, "hover right: redo")
                            dy < 0 -> click(activity, R.id.askButton, "hover up: ask")
                            else -> click(activity, R.id.chromeButton, "hover down: controls")
                        }
                    }
                }
                if (!pressed && wasPressed) finish(activity, settings)
            }
            MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.ACTION_HOVER_EXIT -> {
                if (wasPressed) finish(activity, settings)
            }
        }
        return pressed || wasPressed || event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
    }

    private fun begin(event: MotionEvent) {
        startX = event.x
        startY = event.y
        startedAt = event.eventTime
        wasPressed = true
        gestureSent = false
    }

    private fun finish(activity: Activity, settings: AppSettings) {
        val shortPress = !gestureSent && SystemClock.uptimeMillis() - startedAt < 1_000L
        if (shortPress) {
            when (settings.penButtonAction) {
                PenButtonAction.TOGGLE_ERASER -> {
                    val eraser = activity.findViewById<View>(R.id.eraserButton)
                    val eraserSelected = eraser != null && eraser.alpha >= 0.9f
                    val target = if (eraserSelected) R.id.penButton else R.id.eraserButton
                    click(activity, target, "button: toggle tool")
                }
                PenButtonAction.UNDO -> click(activity, R.id.undoButton, "button: undo")
                PenButtonAction.ASK -> click(activity, R.id.askButton, "button: ask")
                PenButtonAction.TOGGLE_CONTROLS -> click(activity, R.id.chromeButton, "button: controls")
            }
        }
        reset()
    }

    private fun click(activity: Activity, id: Int, description: String) {
        activity.findViewById<View>(id)?.performClick()
        AppLog.i("SPen", description)
    }

    private fun reset() {
        wasPressed = false
        gestureSent = false
        startedAt = 0L
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
