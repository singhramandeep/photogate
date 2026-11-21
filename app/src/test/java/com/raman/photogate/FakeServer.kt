package com.raman.photogate

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** A tiny local HTTP/1.1 server standing in for Google in tests (handles Content-Length and chunked bodies). */
class FakeServer : AutoCloseable {
    class Req(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    val requests = mutableListOf<Req>()
    private val routes = mutableMapOf<String, (Req) -> Pair<Int, String>>()
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: Exception) { break }
                s.use {
                    val input = BufferedInputStream(s.getInputStream())
                    val first = line(input)
                    val headers = HashMap<String, String>()
                    while (true) {
                        val h = line(input)
                        if (h.isEmpty()) break
                        headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
                    }
                    val body = when {
                        headers["transfer-encoding"]?.contains("chunked") == true -> chunked(input)
                        headers["content-length"] != null -> input.readNBytes(headers["content-length"]!!.toInt())
                        else -> ByteArray(0)
                    }
                    val parts = first.split(' ')
                    val req = Req(parts[0], parts[1].substringBefore('?'), headers, body)
                    synchronized(requests) { requests.add(req) }
                    val (code, text) = routes[req.path]?.invoke(req) ?: (404 to "no route")
                    val bytes = text.toByteArray()
                    val out = s.getOutputStream()
                    out.write("HTTP/1.1 $code X\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(bytes)
                    out.flush()
                }
            }
        }
    }

    private fun line(i: InputStream): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = i.read()
            if (c == -1 || c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
        }
        return b.toString(Charsets.ISO_8859_1)
    }

    private fun chunked(i: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = line(i).substringBefore(';').trim().toInt(16)
            if (size == 0) {
                line(i)
                break
            }
            out.write(i.readNBytes(size))
            line(i)
        }
        return out.toByteArray()
    }

    val url get() = "http://127.0.0.1:${server.localPort}"

    fun on(path: String, handler: (Req) -> Pair<Int, String>) {
        routes[path] = handler
    }

    override fun close() = server.close()
}
