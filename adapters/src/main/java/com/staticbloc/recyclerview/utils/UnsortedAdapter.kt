package com.staticbloc.recyclerview.utils

import android.os.Handler
import android.os.HandlerThread
import android.support.v7.util.DiffUtil
import android.support.v7.widget.RecyclerView
import java.util.*

private const val KILL_DELAY = 2500L

private const val OP_ADD = "add"
private const val OP_ADD_ALL = "add_all"
private const val OP_CLEAR = "clear"
private const val OP_REMOVE = "remove"
private const val OP_REPLACE = "replace"

abstract class UnsortedAdapter<T : Any, VH : RecyclerView.ViewHolder>(
    initialItems: Collection<T>? = null
) : RecyclerView.Adapter<VH>() {
  private val diffCallbackProvider: (List<T>, List<T>) -> DiffUtil.Callback = { oldList, newList ->
    object : DiffUtil.Callback() {
      override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
          areItemsTheSame(oldList[oldItemPosition], newList[newItemPosition])
      override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
          areContentsTheSame(oldList[oldItemPosition], newList[newItemPosition])
      override fun getOldListSize() = oldList.size
      override fun getNewListSize() = newList.size
    }
  }

  private val killRunnable = Runnable {
    handlerThread?.quit()
    handlerThread = null
    handler = null
  }

  private data class QueuedOp<out Data : Any>(
      val op: String,
      val index: Int = 0,
      val data: Data? = null
  )

  private val list: MutableList<T> = ArrayList(initialItems ?: Collections.emptyList())
  private val queuedOps: Queue<QueuedOp<*>> = LinkedList()

  private var shouldQueue = false

  private val mainHandler = Handler()
  private var handlerThread: HandlerThread? = null
  private var handler: Handler? = null

  protected open fun areItemsTheSame(item1: T, item2: T) = false
  protected open fun areContentsTheSame(item1: T, item2: T) = false

  private fun executeQueuedOps() {
    // if shouldQueue then we just execute a replace op
    // we'll let it run and then continue executing the rest of the queued ops
    while(!queuedOps.isEmpty() && !shouldQueue) {
      val op = queuedOps.remove()
      when(op.op) {
        OP_ADD -> add(op.data as T)
        OP_ADD_ALL -> addAll(op.data as Collection<T>)
        OP_CLEAR -> clear()
        OP_REMOVE -> remove(op.data as T)
        OP_REPLACE -> replace(op.data as List<T>)
      }
    }
  }

  fun add(element: T) {
    if(shouldQueue) {
      queuedOps.add(QueuedOp(OP_ADD, data = element))
      return
    }

    list.add(element)
    notifyItemInserted(list.size - 1)
  }

  fun addAll(elements: Collection<T>) {
    if(shouldQueue) {
      queuedOps.add(QueuedOp(OP_ADD_ALL, data = elements))
      return
    }

    val insertionIndex = list.size
    list.addAll(elements)
    notifyItemRangeInserted(insertionIndex, elements.size)
  }

  fun clear() {
    if(shouldQueue) {
      queuedOps.add(QueuedOp<Nothing>(OP_CLEAR))
      return
    }

    val size = list.size
    list.clear()
    notifyItemRangeRemoved(0, size)
  }

  operator fun get(index: Int) = list[index]

  fun <CastT : Any> get(position: Int, clazz: Class<CastT>): CastT? {
    val item = list[position]
    return if (item.javaClass === clazz) item as CastT else null
  }

  fun getAll(): List<T> = ArrayList(list)

  fun remove(element: T) {
    if(shouldQueue) {
      queuedOps.add(QueuedOp(OP_REMOVE, data = element))
      return
    }

    val removalIndex = list.indexOf(element)
    if(removalIndex != -1) {
      list.removeAt(removalIndex)
      notifyItemRemoved(removalIndex)
    }
  }

  fun replace(newList: List<T>) {
    if(handlerThread == null) {
      handlerThread = HandlerThread("UnsortedAdapter")
      handlerThread?.start()
      handler = Handler(handlerThread?.looper)
    }

    mainHandler.removeCallbacks(killRunnable)

    shouldQueue = true
    handler?.post {
      val result = DiffUtil.calculateDiff(diffCallbackProvider.invoke(list, newList))
      mainHandler.post {
        list.clear()
        list.addAll(newList)
        result.dispatchUpdatesTo(this)
        shouldQueue = false
        executeQueuedOps()

        mainHandler.postDelayed(killRunnable, KILL_DELAY)
      }
    }
  }

  final override fun getItemCount() = list.size
}
