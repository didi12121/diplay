package com.baidu.carlife.sdk.util

import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.util.Log
import com.baidu.carlife.sdk.Constants
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.lang.Exception
import java.lang.StringBuilder
import java.util.*

class Logger(private val dir: File) {
    companion object {
        const val DEFAULT_MAX_FILE_SIZE = 5 * 1024 * 1024L
        const val DEFAULT_MAX_FILE_COUNT = 20

        const val MESSAGE_SEND = 0
        const val MESSAGE_INIT = 1

        var default: Logger? = null

        @JvmStatic
        var logLevel: Int
            set(value) {
                default?.level = value
            }
            get() = default?.level ?: Log.ERROR

        @JvmStatic
        fun v(tag: String, vararg args:Any?) {
            default?.log(Log.VERBOSE, tag, *args)
        }

        @JvmStatic
        fun d(tag: String, vararg args:Any?) {
            default?.log(Log.DEBUG, tag, *args)
        }

        @JvmStatic
        fun w(tag: String, vararg args:Any?) {
            default?.log(Log.WARN, tag, *args)
        }

        @JvmStatic
        fun e(tag: String, vararg args:Any?) {
            default?.log(Log.ERROR, tag, *args)
        }

        @JvmStatic
        fun wtf(tag: String, vararg args:Any?) {
            default?.log(Log.ASSERT, tag, *args)
        }

        fun uncaughtException(e: Throwable) {
            default?.uncaughtException(e)
        }
    }

    private var writer: BufferedWriter? = null
    private var currentFile: File? = null

    private val persistHandler: Handler
    private val persistThread = HandlerThread("CarLife_Logger")
    // Log 等级
    var level: Int = Log.WARN
    // 单个文件最大尺寸
    var maxFileSize: Long = DEFAULT_MAX_FILE_SIZE
    // 日志文件最大数量，超过 maxFileCount 时，清除一次到 0.8 * maxFileCount
    var maxFileCount: Int = DEFAULT_MAX_FILE_COUNT

    init {
        if (!dir.exists()) {
            dir.mkdirs()
        }

        persistThread.start()
        persistHandler = Handler(persistThread.looper) {
            if (it.what == MESSAGE_SEND) {
                write(it.obj as String)
            }
            else if (it.what == MESSAGE_INIT) {
                val latestFile = resumeLatestLogFile()
                if (latestFile != null) {
                    currentFile = latestFile
                    writer = BufferedWriter(FileWriter(currentFile!!, true))
                }
            }
            return@Handler true
        }
        persistHandler.sendMessage(Message.obtain(persistHandler, MESSAGE_INIT))
    }

    /*fun v(tag: String, vararg args:Any) {
        log(Log.VERBOSE, tag, *args)
    }

    fun d(tag: String, vararg args:Any) {
        log(Log.DEBUG, tag, *args)
    }

    fun w(tag: String, vararg args:Any) {
        log(Log.WARN, tag, *args)
    }

    fun e(tag: String, vararg args:Any) {
        log(Log.ERROR, tag, *args)
    }

    fun wtf(tag: String, vararg args:Any) {
        log(Log.ASSERT, tag, *args)
    }*/

    // crash 写到单独的文件里边
    fun uncaughtException(e: Throwable) {
        try {
            val crashFile = File(dir, "crash_" + Date().formatISO8601() + ".log")
            FileWriter(crashFile).use {
                it.write(Log.getStackTraceString(e))
            }
        }
        catch (e: Throwable) {
            Log.e(Constants.TAG, "uncaughtException exception: " + Log.getStackTraceString(e))
        }
    }

    @Synchronized
    fun quit() {
        persistThread.quitSafely()
        writer?.close()
    }

    fun log(logLevel: Int, tag: String, vararg args:Any?) {
        if (level <= logLevel) {
            val message = if (args.size == 1) {
                transform(args[0])
            }
            else {
                args.joinToString("") { transform(it) }
            }

            when (logLevel) {
                Log.VERBOSE -> {
                    Log.v(tag, message)
                    persist(tag, "V", message)
                }
                Log.DEBUG -> {
                    Log.d(tag, message)
                    persist(tag, "D", message)
                }
                Log.WARN -> {
                    Log.w(tag, message)
                    persist(tag, "W", message)
                }
                Log.ERROR -> {
                    Log.e(tag, message)
                    persist(tag, "E", message)
                }
                Log.ASSERT -> {
                    Log.wtf(tag, message)
                    persist(tag, "E", message)
                }
            }
        }
    }

    private fun transform(obj: Any?): String {
        return if (obj is Exception) {
            Log.getStackTraceString(obj)
        }
        else {
            obj.toString()
        }
    }

    private fun persist(tag: String, logLevel: String, message: String, syncWrite: Boolean = false) {
        val timeString = Date().formatISO8601()
        val pid = android.os.Process.myPid()
        val tid = Thread.currentThread().id
        val formatMessage = "$timeString $pid $tid $logLevel/$tag: $message"
        if (syncWrite) {
            write(formatMessage)
        }
        else {
            persistHandler.sendMessage(Message.obtain(persistHandler, 0, formatMessage))
        }
    }

    private fun write(text: String) {
        if (writer == null) {
            currentFile = File(dir, Date().formatISO8601() + ".log")
            if(!currentFile!!.exists()){
                currentFile!!.createNewFile()
            }
            writer = BufferedWriter(FileWriter(currentFile!!, true))
        }

        writer?.let {
            it.write(text)
            // 如果结尾不是换行，自动添加
            if (text.last() != '\n') {
                it.newLine()
            }
            it.flush()
        }

        if (currentFile!!.length() > maxFileSize) {
            // 超过单文件最大size，清空重新创建文件
            writer?.close()
            // 压缩当前文件，移除超过限制的文件如果需要的话
            doCompressAndDeleteFilesIfNeeded(currentFile)
            // clean objects
            writer = null
            currentFile = null
        }
    }

    private fun doCompressAndDeleteFilesIfNeeded(file: File?) {
        file?.let {
            it.gzip()
            Log.d(Constants.TAG, "Logger compress new file $it")
        }

        dir.listFiles()?.let { logFiles ->
            // delete 20% of maxFileSize
            // crash 文件不删除
            val sortedFiles = logFiles.filter {
                !it.name.startsWith("crash")
            }.sortedWith(LogFileComparator())
            if (sortedFiles.size >= maxFileCount) {
                val deleteSize = (sortedFiles.size * 0.2).toInt()
                for (i in 0 until deleteSize) {
                    sortedFiles[i].delete()
                    Log.d(Constants.TAG, "Logger delete file ${sortedFiles[i]}")
                }
            }
        }
    }

    private fun resumeLatestLogFile(): File? {
        dir.listFiles()?.let { logFiles ->
            val sortedFiles = logFiles.filter {
                val fileName = it.name
                !fileName.startsWith("crash") && fileName.endsWith(".log")
            }
            if (sortedFiles.isNotEmpty()) {
                return sortedFiles.last()
            }
        }
        return null
    }

    class LogFileComparator: Comparator<File> {
        private val lastModifiedCache = mutableMapOf<File, Long>()

        override fun compare(f1: File, f2: File): Int {
            val lastModified1: Long = lastModifiedCache[f1].let {
                return@let it ?: f1.lastModified().apply { lastModifiedCache[f1] = this }
            }
            val lastModified2: Long = lastModifiedCache[f2].let {
                return@let it ?: f2.lastModified().apply { lastModifiedCache[f2] = this }
            }
            return (lastModified1 - lastModified2).toInt()
        }
    }
}