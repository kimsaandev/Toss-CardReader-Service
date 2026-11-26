package com.toss.cardreader.reader.hw.sp

import android.os.SystemClock
import android.util.Log
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.io.IOException

/**
 * Process-wide owner of the reader UART. Every command goes through [call], which holds one lock for the whole
 * request/reply exchange, so two callers can never interleave bytes. Only [SpCommand] values can be framed.
 */
object SpLink {
    private const val DEVICE = "/dev/ttyS3"
    private const val BAUD = "921600"
    private const val STTY = "/system/bin/stty"
    private const val PROBE_TIMEOUT_MS = 1000L
    private const val HEADER_TIMEOUT_MS = 300L
    private const val QUIET_MS = 300L
    private const val TAG = "SpLink"

    private val lock = Any()
    private var fd: FileDescriptor? = null
    private val seq = SpSequence()

    fun available(): Boolean = java.io.File(DEVICE).canWrite()

    val isOpen: Boolean get() = synchronized(lock) { fd != null }

    fun open(): Unit = synchronized(lock) {
        if (fd != null) return
        val f = Os.open(DEVICE, OsConstants.O_RDWR or OsConstants.O_NOCTTY or OsConstants.O_NONBLOCK, 0)
        try {
            configure()
            drain(f)
            // Nothing talks on this line unless asked. Bytes arriving before our first request are replies to another
            // process that has the port open (e.g. a card app with its own direct link): leave it alone.
            if (traffic(f)) throw SpPortBusyException()
            Os.write(f, byteArrayOf(SpProtocol.LINK_PROBE), 0, 1)
            val ack = readExact(f, 1, PROBE_TIMEOUT_MS)
            if (ack[0] != SpProtocol.LINK_ACK) throw IOException("unexpected link reply")
            seq.restart()
            fd = f
            Log.i(TAG, "open pid=${android.os.Process.myPid()} link ok")
        } catch (e: Exception) {
            Os.close(f)
            throw e
        }
    }

    fun close(): Unit = synchronized(lock) {
        fd?.let { runCatching { Os.close(it) }; Log.i(TAG, "close") }
        fd = null
    }

    fun call(command: SpCommand, args: List<ByteArray>, timeoutMs: Long): SpReply = synchronized(lock) {
        val f = fd ?: throw IOException("link closed")
        val request = SpProtocol.frame(seq.take(), command, args)
        drain(f)
        var off = 0
        while (off < request.size) off += Os.write(f, request, off, request.size - off)
        val started = SystemClock.uptimeMillis()
        var raw: ByteArray? = null
        try {
            raw = readFrame(f, timeoutMs)
            val reply = SpProtocol.parseReply(request, raw)
            // Card data never reaches logcat: only the status reply (a bare result code) is logged in full.
            val shown = if (command == SpCommand.IC_STATUS) " rx=${raw.hex()}" else ""
            Log.d(TAG, "cmd=%02X seq=%02X result=%d args=%d %dms%s".format(command.id, request[1].toInt() and 0xFF,
                reply.result, reply.data.size, SystemClock.uptimeMillis() - started, shown))
            reply
        } catch (e: Exception) {
            val shown = if (command == SpCommand.IC_STATUS) " rx=${raw?.hex()}" else ""
            Log.w(TAG, "cmd=%02X failed: %s %s%s".format(command.id, e.javaClass.simpleName, e.message, shown))
            close()
            throw e
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    private fun configure() {
        runStty(BAUD, "cs8", "-parenb", "-cstopb", "-crtscts", "clocal", "cread", "min", "0", "time", "0")
        val current = runStty("-g")
        if (!current.matches(Regex("[0-9a-fA-F]+(?::[0-9a-fA-F]+){4,}"))) throw IOException("invalid serial settings")
        val fields = current.split(':', limit = 5)
        // Some Android stty builds set input flags when applying "raw" or individual control options.
        // Write the serialized raw mode last, preserving the configured control flags and characters.
        runStty("0:0:${fields[2]}:0:${fields[4]}")
    }

    private fun runStty(vararg args: String): String {
        val p = ProcessBuilder(STTY, "-F", DEVICE, *args).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().use { it.readText().trim() }
        if (p.waitFor() != 0) throw IOException("stty failed")
        return output
    }

    private fun readFrame(f: FileDescriptor, timeoutMs: Long): ByteArray {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val mark = readExact(f, 1, deadline - SystemClock.uptimeMillis())
            if ((mark[0].toInt() and 0xFF) != SpProtocol.MARK) continue
            val head = readExact(f, 5, HEADER_TIMEOUT_MS)
            val len = (head[3].toInt() and 0xFF) or ((head[4].toInt() and 0xFF) shl 8)
            if (len + SpProtocol.HEADER + 1 > SpProtocol.MAX_FRAME) throw SpProtocolException("frame too long")
            val rest = readExact(f, len + 1, maxOf(HEADER_TIMEOUT_MS, deadline - SystemClock.uptimeMillis()))
            return mark + head + rest
        }
    }

    private fun readExact(f: FileDescriptor, n: Int, timeoutMs: Long): ByteArray {
        val out = ByteArray(n)
        var got = 0
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (got < n) {
            val left = deadline - SystemClock.uptimeMillis()
            if (left <= 0) throw IOException("reader timeout")
            val pfd = StructPollfd().apply { this.fd = f; events = OsConstants.POLLIN.toShort() }
            if (Os.poll(arrayOf(pfd), left.toInt()) == 0) continue
            try {
                val r = Os.read(f, out, got, n - got)
                if (r > 0) got += r
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.EAGAIN) throw e
            }
        }
        return out
    }

    private fun traffic(f: FileDescriptor): Boolean {
        val pfd = StructPollfd().apply { this.fd = f; events = OsConstants.POLLIN.toShort() }
        return Os.poll(arrayOf(pfd), QUIET_MS.toInt()) > 0
    }

    private fun drain(f: FileDescriptor) {
        val buf = ByteArray(256)
        while (true) {
            try {
                if (Os.read(f, buf, 0, buf.size) <= 0) return
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN) return
                throw e
            }
        }
    }
}
