package ai.iqworks.consentiq

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class StubHttpServer : AutoCloseable {
    data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    data class Response(
        val status: Int,
        val body: String,
        val contentType: String = "application/json",
    )

    private val socket = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    private val routes = LinkedHashMap<String, (Request) -> Response>()
    val received = CopyOnWriteArrayList<Request>()
    val baseUrl: String = "http://127.0.0.1:${socket.localPort}"

    private val acceptor =
        thread(name = "stub-http", isDaemon = true) {
            while (!socket.isClosed) {
                val client =
                    try {
                        socket.accept()
                    } catch (e: SocketException) {
                        return@thread
                    }
                client.use(::serve)
            }
        }

    fun route(
        path: String,
        status: Int = 200,
        body: String,
    ) {
        routes[path] = { Response(status, body) }
    }

    private fun serve(client: Socket) {
        val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = input.readLine() ?: return
        val parts = requestLine.split(' ')
        val method = parts[0]
        val path = parts.getOrElse(1) { "/" }.substringBefore('?')
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            val name = line.substringBefore(':').trim().lowercase()
            headers[name] = line.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val bodyChars = CharArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bodyChars, read, length - read)
            if (n < 0) break
            read += n
        }
        val body = String(String(bodyChars, 0, read).toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        val request = Request(method, path, headers, body)
        received += request
        val response = routes[path]?.invoke(request) ?: Response(404, """{"error":"No route for $path"}""")
        val payload = response.body.toByteArray(Charsets.UTF_8)
        val head =
            "HTTP/1.1 ${response.status} ${reason(response.status)}\r\n" +
                "Content-Type: ${response.contentType}\r\n" +
                "Content-Length: ${payload.size}\r\n" +
                "Connection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(payload)
            flush()
        }
    }

    private fun reason(status: Int): String =
        when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            else -> "Status"
        }

    override fun close() {
        socket.close()
        acceptor.join(2_000)
    }
}
