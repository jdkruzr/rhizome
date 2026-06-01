package io.rhizome.core

import java.util.Base64

/**
 * Resolves the sync endpoint + auth header from user-entered credentials. Single-account HTTP
 * Basic over TLS (spec/protocol.md §I.6 — no tenancy). Used to construct the production transport.
 */
data class SyncConfig(val endpoint: String, val authHeader: String) {
    companion object {
        /** null if [serverUrl] is blank. Endpoint is `<serverUrl>/sync/v1`. */
        fun from(serverUrl: String, username: String, password: String): SyncConfig? {
            val base = serverUrl.trim().trimEnd('/')
            if (base.isEmpty()) return null
            val auth = "Basic " + Base64.getEncoder()
                .encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
            return SyncConfig(endpoint = "$base/sync/v1", authHeader = auth)
        }
    }
}
