package com.yota.launcher

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.URLDecoder

/**
 * Tiny HTTP server for the WiFi book-transfer feature.
 *
 * GET  /                  -> upload page (HTML)
 * POST /upload?name=...   -> raw file body is written to the WIFI_transfer
 *                            directory (no multipart parsing; the page uses
 *                            fetch with the file as the request body).
 */
class WifiTransferServer(
    private val port: Int,
    private val saveDir: File
) {
    companion object {
        private const val TAG = "WifiTransferServer"
    }

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start(): Boolean {
        if (running) return true
        return try {
            if (!saveDir.exists() && !saveDir.mkdirs()) {
                Log.e(TAG, "cannot create save dir: ${saveDir.absolutePath}")
            }
            val socket = ServerSocket(port)
            serverSocket = socket
            running = true
            acceptThread = Thread({ acceptLoop() }, "WifiTransferAccept").apply { isDaemon = true; start() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed on port $port", e)
            running = false
            false
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val client = serverSocket?.accept() ?: break
                Thread({ handle(client) }, "WifiTransferClient").apply { isDaemon = true; start() }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept failed", e)
            }
        }
    }

    private fun handle(client: java.net.Socket) {
        try {
            client.use { socket ->
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())

                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val target = parts[1]

                var contentLength = 0
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val lower = line.lowercase()
                    if (lower.startsWith("content-length:")) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }

                when {
                    method == "GET" && (target == "/" || target.startsWith("/?")) -> {
                        val html = UPLOAD_PAGE.toByteArray(Charsets.UTF_8)
                        writeHead(output, "200 OK", "text/html; charset=utf-8", html.size)
                        output.write(html)
                    }
                    method == "POST" && target.startsWith("/upload") -> {
                        val response = handleUpload(input, target, contentLength)
                        val body = response.toByteArray(Charsets.UTF_8)
                        writeHead(output, "200 OK", "text/plain; charset=utf-8", body.size)
                        output.write(body)
                    }
                    else -> {
                        val body = "Not Found".toByteArray(Charsets.UTF_8)
                        writeHead(output, "404 Not Found", "text/plain; charset=utf-8", body.size)
                        output.write(body)
                    }
                }
                output.flush()
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "handle failed", e)
        }
    }

    private fun handleUpload(input: BufferedInputStream, target: String, contentLength: Int): String {
        if (contentLength <= 0) return "上传失败：未收到文件内容"
        val requestedName = target.substringAfter("name=", "").substringBefore('&')
        val decoded = runCatching {
            URLDecoder.decode(requestedName, "UTF-8")
        }.getOrDefault(requestedName)
        val name = safeName(decoded)

        val finalFile = uniqueFile(saveDir, name)
        val partFile = File(saveDir, finalFile.name + ".part")

        var written = 0L
        try {
            FileOutputStream(partFile).use { fos ->
                val buffer = ByteArray(64 * 1024)
                var remaining = contentLength
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read < 0) break
                    fos.write(buffer, 0, read)
                    written += read
                    remaining -= read
                }
                fos.flush()
            }
            if (written <= 0L && contentLength > 0) {
                partFile.delete()
                return "上传失败：未收到文件内容"
            }
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }
            return "上传成功：${finalFile.name}（${formatSize(written)}）"
        } catch (e: Exception) {
            runCatching { partFile.delete() }
            Log.e(TAG, "save upload failed", e)
            return "上传失败：${e.message}"
        }
    }

    private fun writeHead(output: BufferedOutputStream, status: String, contentType: String, length: Int) {
        val head = "HTTP/1.1 $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: $length\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n" +
            "\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder(64)
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                var s = sb.toString()
                if (s.endsWith("\r")) s = s.dropLast(1)
                return s
            }
            sb.append(b.toChar())
        }
    }

    private fun safeName(raw: String?): String {
        var name = (raw ?: "").trim()
        name = name.substringAfterLast('/').substringAfterLast('\\')
        name = name.replace(Regex("[\u0000-\u001f<>:\"/\\\\|?*]"), "_")
        if (name.isBlank()) name = "upload.bin"
        if (name.length > 120) {
            val dot = name.lastIndexOf('.')
            name = if (dot > 0) name.substring(0, 100) + name.substring(dot)
            else name.substring(0, 120)
        }
        return name
    }

    private fun uniqueFile(dir: File, name: String): File {
        val candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val f = File(dir, "$base ($i)$ext")
            if (!f.exists()) return f
            i++
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private val UPLOAD_PAGE = """
        <!doctype html>
        <html lang="zh">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>WiFi 传书</title>
        <style>
          body{font-family:Georgia,'Songti SC',serif;background:#f7f4ed;color:#111;margin:0;padding:32px 24px;max-width:560px;margin-left:auto;margin-right:auto}
          h1{font-weight:normal;border-bottom:1px solid #999;padding-bottom:8px}
          input[type=file]{margin:24px 0;width:100%}
          button{background:#111;color:#f7f4ed;border:none;padding:12px 28px;font-size:16px}
          button:disabled{background:#999}
          #s{margin-top:16px;color:#333}
        </style>
        </head>
        <body>
        <h1>WiFi 传书</h1>
        <p>选择文件后点击上传，文件会保存到设备的 <b>WIFI_transfer</b> 文件夹。</p>
        <input type="file" id="f">
        <button id="b">上传</button>
        <p id="s"></p>
        <script>
        var b=document.getElementById('b'),f=document.getElementById('f'),s=document.getElementById('s');
        b.onclick=async function(){
          var file=f.files[0];
          if(!file){s.textContent='请先选择文件';return;}
          b.disabled=true;s.textContent='上传中…';
          try{
            var r=await fetch('/upload?name='+encodeURIComponent(file.name),{method:'POST',body:file});
            s.textContent=await r.text();
          }catch(e){s.textContent='上传失败：'+e;}
          b.disabled=false;
        };
        </script>
        </body>
        </html>
    """.trimIndent()
}
