package com.baidu.carlife.sdk.util

import java.io.*
import java.util.zip.GZIPOutputStream

fun InputStream.blockRead(buffer: ByteArray, offset: Int, length: Int) {
    var remaining = length
    while (remaining > 0) {
        val read = read(buffer, offset + length - remaining, remaining)
        if (read >= 0) {
            remaining -= read
        }
        // 不能通过read是否为-1来判断socket断开，因为发现正常情况也会返回-1，
        // 所以需要外部调用thread.interrupt来中断blockRead
        if (Thread.interrupted()) {
            throw IOException("read has been interrupted")
        }
    }
}

// 压缩文件，如果成功删除原始文件
fun File.gzip() {
    val dir = parentFile
    val gzFile = File(dir, "$name.gz")
    try {
        BufferedInputStream(FileInputStream(this)).use { input ->
            GZIPOutputStream(FileOutputStream(gzFile)).use { output ->
                input.copyTo(output)
            }
        }
        // 压缩成功，删除自己
        delete()
    }
    catch (e: Exception) {
        // 如果压缩失败，删除压缩文件
        gzFile.delete()
    }
}