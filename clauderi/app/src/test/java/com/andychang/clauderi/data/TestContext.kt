package com.andychang.clauderi.data

import android.content.Context
import org.mockito.Mockito
import java.io.File
import java.nio.file.Files

/** A Context whose filesDir is a fresh temp folder. Nothing else on it is used by the stores. */
fun testContext(): Context {
    val dir: File = Files.createTempDirectory("lordclaude-test").toFile().apply { deleteOnExit() }
    val ctx = Mockito.mock(Context::class.java)
    Mockito.`when`(ctx.filesDir).thenReturn(dir)
    return ctx
}
