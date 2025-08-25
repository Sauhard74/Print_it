package com.example.printer.utils

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.server.response.*

/**
 * This is a simple test class to verify Ktor imports are working
 */
class KtorTest {
    fun startServer() {
        val server = embeddedServer(Netty, port = 8080) {
            routing {
                get("/") {
                    call.respondText("Hello, world!", ContentType.Text.Plain)
                }
            }
        }
        // Don't actually start, just test imports
    }
} 