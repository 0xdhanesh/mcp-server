package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.collaborator.CollaboratorClient
import burp.api.montoya.collaborator.SecretKey

/**
 * One Collaborator client for this extension, restored from the project file
 * when a secret key was saved earlier. Payloads from this client are the ones
 * [get_collaborator_interactions] can poll.
 */
internal class CollaboratorClients(private val api: MontoyaApi) {
    private var cached: CollaboratorClient? = null

    fun use(client: CollaboratorClient) {
        cached = client
    }

    fun client(): CollaboratorClient {
        cached?.let { return it }
        val storage = runCatching { api.persistence().extensionData() }.getOrNull()
        val saved = runCatching { storage?.getString(SECRET_KEY) }.getOrNull()
        val restored = if (!saved.isNullOrBlank()) {
            runCatching { api.collaborator().restoreClient(SecretKey.secretKey(saved)) }.getOrNull()
        } else {
            null
        }
        val client = restored ?: api.collaborator().createClient()
        if (restored == null && storage != null) {
            runCatching {
                val secret = client.getSecretKey().toString()
                if (secret.isNotBlank()) storage.setString(SECRET_KEY, secret)
            }
        }
        cached = client
        return client
    }

    companion object {
        const val SECRET_KEY = "mcp_collaborator_secret"
    }
}
