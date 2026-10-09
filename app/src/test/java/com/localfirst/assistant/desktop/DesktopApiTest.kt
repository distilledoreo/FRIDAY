package com.localfirst.assistant.desktop

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DesktopApiTest {
    @Test fun authenticatedRequestsDoNotFollowRedirects() {
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 2000
        val worker = Executors.newSingleThreadExecutor()
        val received = worker.submit<Int> {
            server.accept().use { socket ->
                socket.soTimeout = 2000
                val input = socket.getInputStream().bufferedReader()
                while (!input.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: /other\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            }
            server.soTimeout = 300
            try {
                server.accept().use { socket ->
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
                1
            } catch (_: SocketTimeoutException) { 0 }
        }
        try {
            val response = desktopRequest("http://127.0.0.1:${server.localPort}", "synthetic-token", "GET", "/redirect", timeoutMs = 1000)
            assertEquals(302, response.first)
            assertEquals(0, received.get(3, TimeUnit.SECONDS))
        } finally { server.close(); worker.shutdownNow() }
    }
}
