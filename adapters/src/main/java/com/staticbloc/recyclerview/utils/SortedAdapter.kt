package com.staticbloc.recyclerview.utils

import android.support.v7.util.DiffUtil
import android.support.v7.util.SortedList
import android.support.v7.widget.RecyclerView
import android.widget.Filter
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock

abstract class SortedAdapter<T : Any, VH : RecyclerView.ViewHolder>(
    clazz: Class<T>,
    val forceThreadSafety: Boolean = false,
    initialItems: Collection<T>? = null
) : RecyclerView.Adapter<VH>() {
  companion object {
    const private val READ_LOCK = 0
    const private val WRITE_LOCK = 1
  }

  private fun SortedList<T>.batchUpdate(op: SortedList<T>.() -> Unit) {
    beginBatchedUpdates()
    try {
      this.op()
    }
    finally {
      endBatchedUpdates()
    }
  }

  private fun SortedList<T>.toList(): List<T> {
    val size = size()
    val list = ArrayList<T>(size)
    (0..size - 1).mapTo(list) { get(it) }
    return list
  }

  private fun SortedList<T>.linearReferentialEqualitySearch(item: T): Int {
    return (0..size() - 1).firstOrNull { areItemsTheSame(item, get(it)) }
        ?: SortedList.INVALID_POSITION
  }

  private fun SortedList<T>.upsert(item: T): Int {
    var index = indexOf(item)
    if (index == SortedList.INVALID_POSITION) {
      index = linearReferentialEqualitySearch(item)
      if (index == SortedList.INVALID_POSITION) {
        return add(item)
      }
      else {
        updateItemAt(index, item)
        return index
      }
    }
    else {
      updateItemAt(index, item)
      return index
    }
  }

  private fun SortedList<T>.upsert(oldItem: T, newItem: T): Int {
    val index = indexOf(oldItem)
    if (index == SortedList.INVALID_POSITION) {
      return add(newItem)
    }
    else {
      updateItemAt(index, newItem)
      return index
    }
  }

  private inner class Callback : SortedList.Callback<T>() {
    var disableNotifications: Boolean = false

    override fun compare(o1: T, o2: T) = this@SortedAdapter.compare(o1, o2)

    override fun areContentsTheSame(oldItem: T, newItem: T) = this@SortedAdapter.areContentsTheSame(oldItem, newItem)

    override fun areItemsTheSame(item1: T, item2: T): Boolean = this@SortedAdapter.areItemsTheSame(item1, item2)

    override fun onInserted(position: Int, count: Int) {
      if(!disableNotifications) notifyItemRangeInserted(position, count)
    }

    override fun onRemoved(position: Int, count: Int) {
      if(!disableNotifications)  notifyItemRangeRemoved(position, count)
    }

    override fun onMoved(fromPosition: Int, toPosition: Int) {
      if(!disableNotifications) notifyItemMoved(fromPosition, toPosition)
    }

    override fun onChanged(position: Int, count: Int) {
      if(!disableNotifications) notifyItemRangeChanged(position, count)
    }

  }

  private val originalCallback = Callback()
  private val filterCallback = Callback()

  private val originalList: SortedList<T> = SortedList(clazz, originalCallback)
  private val filteredList: SortedList<T> = SortedList(clazz, filterCallback)
  private var list: SortedList<T> = originalList

  private val lock: ReentrantReadWriteLock = ReentrantReadWriteLock(true)

  private val filter = FilterImpl()
  private var filtering = false
  private var filterRequestPending = false
  private var filterConstraint = ""

  private var lastModifiedTimestamp = 0L

  init {
    if(initialItems != null) {
      list.addAll(initialItems)
    }
  }

  final override fun getItemCount(): Int {
    return lockIfNeeded(READ_LOCK) {
      list.size()
    }
  }

  fun filter(constraint: CharSequence) {
    lockIfNeeded(WRITE_LOCK) {
      filterRequestPending = true
      filtering = true
      filter.filter(constraint)
    }
  }

  operator fun get(position: Int): T {
    return lockIfNeeded(READ_LOCK) {
      list.get(position)
    }
  }

  fun <CastT : Any> get(position: Int, clazz: Class<CastT>): CastT? {
    return lockIfNeeded(READ_LOCK) {
      val item = list.get(position)
      if (item.javaClass === clazz) item as CastT else null
    }
  }

  fun getAll(): List<T> {
    return lockIfNeeded(READ_LOCK) {
      list.toList()
    }
  }

  fun <ClazzT> getAll(clazz: Class<ClazzT>): List<ClazzT> {
    return lockIfNeeded(READ_LOCK) {
      val list = this.list
      val size = list.size()
      val all = ArrayList<ClazzT>(size)
      (0..size - 1).asSequence()
          .map { list.get(it) }
          .filter { it.javaClass == clazz }
          .mapTo(all) { it as ClazzT }
    }
  }

  fun add(item: T): Int {
    return lockIfNeeded(WRITE_LOCK) {
      if(filtering) {
        originalList.add(item)
      }

      if (!filtering || isItemInFilter(filterConstraint, item)) {
        list.add(item)
      }
      else {
        SortedList.INVALID_POSITION
      }
    }
  }

  fun addAll(items: Collection<T>) {
    lockIfNeeded(WRITE_LOCK) {
      if(filtering) {
        originalList.addAll(items)

        val filteredItems = items.filter {
          isItemInFilter(filterConstraint, it)
        }

        list.addAll(filteredItems)
      }

      list.addAll(items)
    }
  }

  fun update(item: T): Int {
    return lockIfNeeded(WRITE_LOCK) {
      if(filtering) {
        originalList.upsert(item)
      }

      if (!filtering || isItemInFilter(filterConstraint, item)) {
        list.upsert(item)
      }
      else {
        SortedList.INVALID_POSITION
      }
    }
  }

  fun update(oldItem: T, newItem: T): Int {
    return lockIfNeeded(WRITE_LOCK) {
      if (filtering) {
        originalList.upsert(oldItem, newItem)

        if (isItemInFilter(filterConstraint, newItem)) {
          if (oldItem === newItem || isItemInFilter(filterConstraint, oldItem)) {
            list.upsert(oldItem, newItem)
          }
          else {
            list.add(newItem)
          }
        }
        else {
          SortedList.INVALID_POSITION
        }
      }
      else {
        list.upsert(oldItem, newItem)
      }
    }
  }

  fun remove(item: T): Boolean {
    return lockIfNeeded(WRITE_LOCK) {
      if (filtering) originalList.remove(item)
      list.remove(item)
    }
  }

  fun remove(position: Int): Boolean {
    return lockIfNeeded(WRITE_LOCK) {
      val item = get(position)
      if (item != null) {
        if (filtering) originalList.remove(item)
        list.remove(item)
      }
      else {
        false
      }
    }
  }

  fun clear() {
    lockIfNeeded(WRITE_LOCK) {
      if (filtering) originalList.clear()
      list.clear()
    }
  }

  fun replace(items: List<T>) {
    lockIfNeeded(WRITE_LOCK) {
      if (filtering) {
        originalList.clear()
        items.forEach { originalList.add(it) }
      }

      list.batchUpdate {
        clear()

        this@SortedAdapter.addAll(items)
      }
    }
  }

  fun replaceAllItemsOfViewType(items: List<T>, viewType: Int) {
    lockIfNeeded(WRITE_LOCK) {
      val indicesToRemove = ArrayList<Int>()

      if (filtering) {
        (0..originalList.size() - 1).asSequence()
            .filter { getItemViewType(it) == viewType }
            .forEach { indicesToRemove.add(it - indicesToRemove.size) }

        for (index in indicesToRemove) {
          originalList.removeItemAt(index)
        }

        items.forEach { originalList.add(it) }
      }

      indicesToRemove.clear()

      list.batchUpdate {
        (0..size() - 1).asSequence()
            .filter { getItemViewType(it) == viewType }
            .forEach { indicesToRemove.add(it - indicesToRemove.size) }

        for (index in indicesToRemove) {
          removeItemAt(index)
        }

        items.asSequence()
            .filter { isItemInFilter(filterConstraint, it) }
            .forEach { add(it) }
      }
    }
  }

  protected abstract fun compare(lhs: T, rhs: T): Int
  protected abstract fun areContentsTheSame(oldItem: T, newItem: T): Boolean
  protected abstract fun areItemsTheSame(item1: T, item2: T): Boolean

  protected fun isItemInFilter(constraint: String, item: T): Boolean {
    return true
  }

  private fun <T> lockIfNeeded(whichLock: Int, op: () -> T): T {
    var locked = false

    if (forceThreadSafety || filtering) {
      if (whichLock == READ_LOCK) {
        lock.readLock().lock()
      } else {
        lock.writeLock().lock()
        lastModifiedTimestamp = System.currentTimeMillis()
      }
      locked = true
    }

    try {
      return op.invoke()
    }
    finally {
      if (locked) {
        if (whichLock == READ_LOCK) {
          lock.readLock().unlock()
        } else {
          lock.writeLock().unlock()
        }
      }
    }
  }

  private inner class FilterResults(val list: List<T>, val isFiltering: Boolean, val diffResult: DiffUtil.DiffResult, val modified: Long)

  private inner class FilterImpl : Filter() {
    override fun performFiltering(constraint: CharSequence?): Filter.FilterResults {
      return lockIfNeeded(WRITE_LOCK) {
        filterConstraint = constraint?.toString()?.toLowerCase()?.trim() ?: ""
        val noFilter = filterConstraint == ""

        val results = Filter.FilterResults()

        val oldFilteredList = list.toList()
        val newFilteredList: List<T> = if(noFilter) {
          results.count = originalList.size()
          originalList.toList()
        }
        else {
          val thisList = ArrayList<T>(originalList.size())
          for(i in 0..originalList.size() - 1) {
            val item = originalList.get(i)
            if (isItemInFilter(filterConstraint, item)) {
              thisList.add(item)
              results.count++
            }
          }
          thisList
        }

        val diffResults = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
          override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
              areItemsTheSame(oldFilteredList[oldItemPosition], newFilteredList[newItemPosition])
          override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
              areContentsTheSame(oldFilteredList[oldItemPosition], newFilteredList[newItemPosition])
          override fun getOldListSize() = oldFilteredList.size
          override fun getNewListSize() = newFilteredList.size

        }, true)

        results.apply { values = FilterResults(newFilteredList, !noFilter, diffResults, lastModifiedTimestamp) }
      }
    }

    override fun publishResults(constraint: CharSequence?, results: Filter.FilterResults) {
      lockIfNeeded(WRITE_LOCK) {
        val filterResults = results.values as SortedAdapter<T, VH>.FilterResults

        if(filterResults.isFiltering) {
          originalCallback.disableNotifications = true
          list = filteredList
          if(filterResults.modified == lastModifiedTimestamp) {
            filterCallback.disableNotifications = true
            list.clear()
            list.addAll(filterResults.list)
            filterResults.diffResult.dispatchUpdatesTo(this@SortedAdapter)
            filterCallback.disableNotifications = false
          }
          else {
            list.batchUpdate {
              clear()
              addAll(filterResults.list)
            }
          }
        }
        else {
          originalCallback.disableNotifications = false
          list = originalList
          if(filterResults.modified == lastModifiedTimestamp) {
            filterResults.diffResult.dispatchUpdatesTo(this@SortedAdapter)
          }
          else {
            notifyDataSetChanged()
          }
        }

        filtering = filterRequestPending
        filterRequestPending = false
      }
    }
  }
}
