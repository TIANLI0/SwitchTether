package io.github.tianli0.switchtether.ui.workspace

import io.github.tianli0.switchtether.remote.api.CommandResponse
import io.github.tianli0.switchtether.remote.api.RemoteApi
import io.github.tianli0.switchtether.remote.protocol.CreateTerminalBody
import io.github.tianli0.switchtether.remote.protocol.ScrollbackPage
import io.github.tianli0.switchtether.remote.protocol.TerminalList
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * The part of the desktop API the terminal page uses (docs/PROTOCOL.md §7.6): the REST calls and
 * the stream socket. A seam for tests; the app always goes through [RemoteTerminalApi].
 */
interface TerminalApi {
    suspend fun terminals(): TerminalList

    suspend fun createTerminal(body: CreateTerminalBody): CommandResponse

    suspend fun killTerminal(id: String)

    suspend fun terminalScrollback(id: String, offset: Int, limit: Int): ScrollbackPage

    fun openTerminal(id: String, listener: WebSocketListener): WebSocket
}

class RemoteTerminalApi(private val api: RemoteApi) : TerminalApi {
    override suspend fun terminals() = api.terminals()

    override suspend fun createTerminal(body: CreateTerminalBody) = api.createTerminal(body)

    override suspend fun killTerminal(id: String) {
        api.killTerminal(id)
    }

    override suspend fun terminalScrollback(id: String, offset: Int, limit: Int) = api.terminalScrollback(id, offset, limit)

    override fun openTerminal(id: String, listener: WebSocketListener) = api.openTerminal(id, listener)
}
