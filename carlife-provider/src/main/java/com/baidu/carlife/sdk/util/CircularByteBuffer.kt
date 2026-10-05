package com.baidu.carlife.sdk.util

import java.util.concurrent.atomic.AtomicBoolean

class CircularByteBuffer(
    val capacity: Int = 24 * 1024,
    // 当存储空间不够时，是否覆盖
    private val overwrite: Boolean = false) {

    // initial 24K Buffer
    private val storage = ByteArray(capacity)
    private val lock = Object()

    @Volatile
    private var start: Int = 0
    @Volatile
    private var end: Int = 0
    @Volatile
    var size: Int = 0
        private set

    private var meetEnd = AtomicBoolean(false)

    /**
     * 返回闲置空间大小
     */
    fun avaliable(): Int {
        return capacity - size
    }

    fun empty(): Boolean {
        return size == 0
    }

    fun full(): Boolean {
        return size == capacity
    }

    fun bytes(): ByteArray {
        return storage
    }

    /**
     * 设置到达了数据的结尾了，不影响继续读缓存的数据
     */
    fun end() {
        meetEnd.set(true)
        synchronized(lock) {
            lock.notifyAll()
        }
    }

    fun blockUntil(length: Int, timeout: Long = 0): Int {
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeout
            while (size < length && (timeout == 0L || System.currentTimeMillis() < deadline)) {
                if (timeout == 0L) {
                    // 如果timeout为0，死等
                    lock.wait()
                } else {
                    val waitTime = deadline - System.currentTimeMillis()
                    if (waitTime > 0) {
                        lock.wait(waitTime)
                    }
                }
            }
            return size
        }
    }

    fun write(buffer: ByteArray, offset: Int, length: Int, timeout: Long = 0) = synchronized(lock) {
        if (meetEnd.get()) {
            // 如果调用了End，则不在写入任何数据
            lock.notifyAll()
            return@synchronized 0
        }

        val deadline = System.currentTimeMillis() + timeout
        if (!overwrite) {
            var remaining = length
            while (remaining > 0 && (timeout == 0L || System.currentTimeMillis() < deadline)) {
                if (avaliable() == 0) {
                    if (timeout == 0L) {
                        // 如果timeout为0，死等
                        lock.wait()
                    } else {
                        val waitTime = deadline - System.currentTimeMillis()
                        if (waitTime > 0) {
                            lock.wait(waitTime)
                        }
                    }
                }
                remaining -= doWrite(buffer, offset + (length - remaining), remaining)
            }
        }
        else {
            if (length >= capacity) {
                val skip = length - capacity
                System.arraycopy(buffer, offset + skip, storage, 0, capacity)
                start = 0
                end = 0
                size = capacity
            }
            else {
                val skip = length - avaliable()
                if (skip > 0) {
                    start = (start + skip) % capacity
                    size -= skip
                }
                doWrite(buffer, offset, length)
            }
        }

        lock.notifyAll()
        return@synchronized length
    }

    private fun doWrite(buffer: ByteArray, offset: Int, length: Int): Int {
        // 已经满了，直接返回
        if (avaliable() == 0) {
            return 0
        }
        // 只需拷贝一段, end到start之间的为可用空间
        if (end < start) {
            val copySize = length.coerceAtMost(avaliable())
            System.arraycopy(buffer, offset, storage, end, copySize)
            end += copySize
            size += copySize
            return copySize
        }
        // 可能需要分段拷贝
        var copySize = length.coerceAtMost(capacity - end)
        System.arraycopy(buffer, offset, storage, end, copySize)
        if (copySize == length) {
            end += copySize
            size += copySize
            return copySize
        }
        // 拷贝第二段
        val remaining = (length - copySize).coerceAtMost(start)
        System.arraycopy(buffer, offset + copySize, storage, 0, remaining)
        copySize += remaining
        end = remaining
        size += copySize
        return copySize
    }

    /**
     * 读取固定长度数据
     * @param buffer 缓存区
     * @param offset 缓存区偏移量
     * @param length 读取长度
     * @param timeout 超时时间
     * @return 读到的数据量，如果isClose为true，并且当前没有数据，则返回-1
     */
    fun read(buffer: ByteArray, offset: Int, length: Int, timeout: Long = 0): Int = synchronized(lock) {
        if (empty() && meetEnd.get()) {
            return@synchronized -1
        }

        val deadline = System.currentTimeMillis() + timeout
        var remaining = length
        while (remaining > 0 && (timeout == 0L || System.currentTimeMillis() < deadline)) {
            remaining -= doRead(buffer, offset + length - remaining, remaining)
            if (remaining > 0) {
                if (meetEnd.get()) {
                    // 当前已为close状态，直接跳出循环
                    break
                }

                if (timeout == 0L) {
                    // 如果timeout为0，死等
                    lock.wait()
                } else {
                    val waitTime = deadline - System.currentTimeMillis()
                    if (waitTime > 0) {
                        lock.wait(waitTime)
                    }
                }
                remaining -= doRead(buffer, offset + length - remaining, remaining)
            }
        }
        lock.notifyAll()
        return length - remaining
    }

    private fun doRead(buffer: ByteArray, offset: Int, length: Int): Int {
        if (size <= 0) {
            return 0
        }

        if (start < end) {
            val copySize = length.coerceAtMost(size)
            System.arraycopy(storage, start, buffer, offset, copySize)
            size -= copySize
            start = (start + copySize) % capacity
            return copySize
        }

        // 分两段拷贝
        var copySize = length.coerceAtMost(capacity - start)
        System.arraycopy(storage, start, buffer, offset, copySize)
        start = (start + copySize) % capacity
        size -= copySize
        // 第一段未读完，继续读第二段
        if (size > 0 && copySize < length) {
            val remaining = (length - copySize).coerceAtMost(size)
            System.arraycopy(storage, start, buffer, offset + copySize, remaining)

            copySize += remaining
            start = (start + remaining) % capacity
            size -= remaining
        }
        return copySize
    }
}