package com.staticbloc.recyclerview.utils

import android.os.Build
import android.support.v4.view.GestureDetectorCompat
import android.support.v4.view.MotionEventCompat
import android.support.v7.widget.RecyclerView
import android.view.*

interface OnItemClickListener {
  /**
   * If `true` is returned [View.playSoundEffect] will be called
   * @return `true` if handled, otherwise `false`
   */
  fun onItemClicked(holder: RecyclerView.ViewHolder, v: View) = false

  /**
   * If `true` is returned and haptic feedback is enabled on the device
   * [View.performHapticFeedback] will be called
   * @return `true` if handled, otherwise `false`
   */
  fun onItemLongClicked(holder: RecyclerView.ViewHolder, v: View) = false
}

fun RecyclerView.onItemClickListener(init: __RecyclerView_OnItemClickListener.() -> Unit) {
  val listener = __RecyclerView_OnItemClickListener()
  listener.init()
  addOnItemClickListener(listener)
}

class __RecyclerView_OnItemClickListener : OnItemClickListener {
  private var _onItemClicked: ((RecyclerView.ViewHolder, View) -> Boolean)? = null
  private var _onItemLongClicked: ((RecyclerView.ViewHolder, View) -> Boolean)? = null

  fun onItemClicked(listener: ((RecyclerView.ViewHolder, View) -> Boolean)) {
    _onItemClicked = listener
  }

  override fun onItemClicked(holder: RecyclerView.ViewHolder, v: View): Boolean {
    return _onItemClicked?.invoke(holder, v) ?: false
  }

  fun onItemLongClicked(listener: ((RecyclerView.ViewHolder, View) -> Boolean)) {
    _onItemLongClicked = listener
  }

  override fun onItemLongClicked(holder: RecyclerView.ViewHolder, v: View): Boolean {
    return _onItemLongClicked?.invoke(holder, v) ?: false
  }
}

fun RecyclerView.addOnItemClickListener(listener: OnItemClickListener): RecyclerView.OnItemTouchListener {
  val onItemTouchListener = ClickItemTouchListener(this, listener)
  addOnItemTouchListener(onItemTouchListener)
  return onItemTouchListener
}

private class ClickItemTouchListener(
    recyclerView: RecyclerView,
    listener: OnItemClickListener
) : RecyclerView.SimpleOnItemTouchListener() {
  private val gestureListener = ItemClickGestureListener(recyclerView, listener)
  private val gestureDetector = GestureDetectorCompat(recyclerView.context, gestureListener)

  private fun isAttachedToWindow(hostView: RecyclerView): Boolean {
    if (Build.VERSION.SDK_INT >= 19) {
      return hostView.isAttachedToWindow
    } else {
      return hostView.handler != null
    }
  }

  override fun onInterceptTouchEvent(recyclerView: RecyclerView, event: MotionEvent): Boolean {
    if (!isAttachedToWindow(recyclerView) || recyclerView.adapter == null) {
      return false
    }

    val handled = gestureDetector.onTouchEvent(event)

    if(event.action and MotionEventCompat.ACTION_MASK == MotionEvent.ACTION_UP) {
      gestureListener.onUp()
    }

    return handled
  }
}

private class ItemClickGestureListener(
    private val recyclerView: RecyclerView,
    private val listener: OnItemClickListener
) : GestureDetector.SimpleOnGestureListener() {
  private var target: View? = null

  // we're assuming that all events after onDown will be delivered before the next onDown
  override fun onDown(event: MotionEvent): Boolean {
    target = recyclerView.findChildViewUnder(event.x, event.y)
    target?.isPressed = true
    return target != null
  }

  fun onUp() {
    target?.let { target ->
      target.isPressed = false
      this.target = null
    }
  }

  override fun onSingleTapUp(event: MotionEvent): Boolean {
    var handled = false

    target?.let { target: View ->
      target.isPressed = false
      handled = listener.onItemClicked(recyclerView.getChildViewHolder(target), target)
      if(handled) {
        target.playSoundEffect(SoundEffectConstants.CLICK)
      }
      this.target = null
    }

    return handled
  }

  override fun onScroll(event: MotionEvent, event2: MotionEvent, v: Float, v2: Float): Boolean {
    if (target != null) {
      target!!.isPressed = false
      target = null
    }

    return false
  }

  override fun onLongPress(event: MotionEvent) {
    target?.let { target: View ->
      val handled = listener.onItemLongClicked(recyclerView.getChildViewHolder(target), target)

      if (handled) {
        target.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        target.isPressed = false
        this.target = null
      }
    }
  }
}
