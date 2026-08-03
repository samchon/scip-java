package org.scip_code.scip_java.buildtools

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ProcessResult(val exitCode: Int, val stdout: ByteArray? = null)

/**
 * Tiny `ProcessBuilder` wrapper that streams stdout/stderr line-by-line to caller-provided sinks.
 * Each stream is drained on its own thread so the spawned process cannot deadlock on a full pipe.
 */
object ProcessRunner {
    fun run(
        command: List<String>,
        cwd: Path,
        env: Map<String, String> = emptyMap(),
        onStdout: (String) -> Unit = {},
        onStderr: (String) -> Unit = {},
        captureStdout: Boolean = false,
    ): ProcessResult {
        val builder = ProcessBuilder(command).directory(cwd.toFile())
        if (env.isNotEmpty()) {
            val merged = builder.environment()
            for ((k, v) in env) {
                merged[k] = v
            }
        }
        val process = builder.start()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val outFuture =
                pool.submit<ByteArray?> {
                    if (captureStdout) drainCaptured(process.inputStream, onStdout)
                    else {
                        drain(process.inputStream, onStdout)
                        null
                    }
                }
            val errFuture = pool.submit { drain(process.errorStream, onStderr) }
            val exit = process.waitFor()
            val stdout = outFuture.get(30, TimeUnit.SECONDS)
            errFuture.get(30, TimeUnit.SECONDS)
            return ProcessResult(exit, stdout)
        } finally {
            pool.shutdown()
        }
    }

    private fun drain(input: java.io.InputStream, sink: (String) -> Unit) {
        BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).useLines { lines ->
            for (line in lines) sink(line)
        }
    }

    private fun drainCaptured(input: java.io.InputStream, sink: (String) -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        val line = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        input.use {
            while (true) {
                val size = it.read(buffer)
                if (size < 0) break
                output.write(buffer, 0, size)
                for (index in 0 until size) {
                    val byte = buffer[index]
                    if (byte == '\n'.code.toByte()) {
                        sink(line.toString(StandardCharsets.UTF_8).removeSuffix("\r"))
                        line.reset()
                    } else {
                        line.write(byte.toInt())
                    }
                }
            }
        }
        if (line.size() > 0) sink(line.toString(StandardCharsets.UTF_8).removeSuffix("\r"))
        return output.toByteArray()
    }
}
