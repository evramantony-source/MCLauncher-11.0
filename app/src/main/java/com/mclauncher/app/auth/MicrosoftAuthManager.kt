package com.mclauncher.app.auth

import com.mclauncher.minecraft.HttpDownloader
import com.mclauncher.model.AccountType
import com.mclauncher.model.AuthSession
import com.mclauncher.model.LauncherAccount
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class MicrosoftDeviceCode(
    val device_code: String,
    val user_code: String,
    val verification_uri: String,
    val expires_in: Int,
    val interval: Int = 5,
    val message: String = ""
)

@Serializable
private data class MicrosoftTokenResponse(
    val token_type: String = "Bearer",
    val scope: String = "",
    val expires_in: Int,
    val access_token: String,
    val refresh_token: String? = null
)

@Serializable
private data class OAuthError(val error: String = "", val error_description: String = "")

@Serializable
private data class XboxDisplayClaims(val xui: List<XboxUserClaim> = emptyList())

@Serializable
private data class XboxUserClaim(val uhs: String = "", val xid: String? = null)

@Serializable
private data class XboxTokenResponse(
    @SerialName("Token") val token: String,
    @SerialName("DisplayClaims") val displayClaims: XboxDisplayClaims
)

@Serializable
private data class MinecraftTokenResponse(
    val username: String = "",
    val roles: List<String> = emptyList(),
    val access_token: String,
    val token_type: String = "Bearer",
    val expires_in: Int
)


@Serializable
private data class MinecraftEntitlements(
    val items: List<MinecraftEntitlement> = emptyList(),
    val signature: String? = null,
    val keyId: String? = null
)

@Serializable
private data class MinecraftEntitlement(val name: String = "", val signature: String? = null)

@Serializable
private data class MinecraftProfile(
    val id: String,
    val name: String,
    val skins: List<MinecraftSkin> = emptyList(),
    val capes: List<MinecraftCape> = emptyList()
)

@Serializable
private data class MinecraftSkin(val id: String? = null, val state: String? = null, val url: String? = null, val variant: String? = null)

@Serializable
private data class MinecraftCape(val id: String? = null, val state: String? = null, val url: String? = null, val alias: String? = null)

class MicrosoftAuthManager(
    private val tokenStore: SecureTokenStore,
    private val downloader: HttpDownloader = HttpDownloader(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
) {
    suspend fun beginDeviceCode(clientId: String): MicrosoftDeviceCode {
        require(clientId.isNotBlank()) { "Enter your Microsoft application client ID in Settings" }
        val raw = downloader.postForm(
            DEVICE_CODE_ENDPOINT,
            mapOf(
                "client_id" to clientId.trim(),
                "scope" to "XboxLive.signin offline_access"
            )
        )
        return json.decodeFromString(raw)
    }

    suspend fun completeDeviceCode(
        clientId: String,
        code: MicrosoftDeviceCode,
        onStatus: (String) -> Unit = {}
    ): LauncherAccount {
        val deadline = System.currentTimeMillis() + code.expires_in * 1000L
        var intervalSeconds = code.interval.coerceAtLeast(3)
        while (System.currentTimeMillis() < deadline) {
            delay(intervalSeconds * 1000L)
            val response = runCatching {
                downloader.postForm(
                    TOKEN_ENDPOINT,
                    mapOf(
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                        "client_id" to clientId.trim(),
                        "device_code" to code.device_code
                    )
                )
            }
            if (response.isFailure) {
                val message = response.exceptionOrNull()?.message.orEmpty()
                when {
                    message.contains("authorization_pending", true) -> {
                        onStatus("Waiting for Microsoft sign-in")
                        continue
                    }
                    message.contains("slow_down", true) -> {
                        intervalSeconds += 5
                        continue
                    }
                    message.contains("authorization_declined", true) -> error("Microsoft sign-in was declined")
                    message.contains("expired_token", true) -> error("Microsoft sign-in code expired")
                    else -> throw response.exceptionOrNull() ?: error("Microsoft sign-in failed")
                }
            }
            val microsoft = json.decodeFromString<MicrosoftTokenResponse>(response.getOrThrow())
            return finishMicrosoftLogin(clientId, microsoft)
        }
        error("Microsoft sign-in code expired")
    }

    suspend fun refresh(account: LauncherAccount): AuthSession {
        require(account.type == AccountType.MICROSOFT) { "Only Microsoft accounts can be refreshed" }
        val stored = tokenStore.load(account.id) ?: error("Microsoft session is missing")
        if (!stored.isExpired()) return stored
        val refreshToken = stored.refreshToken ?: error("Microsoft refresh token is missing")
        val clientId = stored.clientId ?: account.clientId ?: error("Microsoft client ID is missing")
        val raw = downloader.postForm(
            TOKEN_ENDPOINT,
            mapOf(
                "client_id" to clientId,
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "scope" to "XboxLive.signin offline_access"
            )
        )
        val microsoft = json.decodeFromString<MicrosoftTokenResponse>(raw)
            .let { response -> if (response.refresh_token == null) response.copy(refresh_token = refreshToken) else response }
        val updated = finishMicrosoftLogin(clientId, microsoft)
        return tokenStore.load(updated.id) ?: error("Refreshed session was not saved")
    }

    fun session(account: LauncherAccount): AuthSession? = tokenStore.load(account.id)

    fun remove(accountId: String) = tokenStore.remove(accountId)

    private suspend fun finishMicrosoftLogin(clientId: String, microsoft: MicrosoftTokenResponse): LauncherAccount {
        val xbox = authenticateXbox(microsoft.access_token)
        val userHash = xbox.displayClaims.xui.firstOrNull()?.uhs ?: error("Xbox account did not return a user hash")
        val xuid = xbox.displayClaims.xui.firstOrNull()?.xid
        val xsts = authorizeXsts(xbox.token)
        val xstsHash = xsts.displayClaims.xui.firstOrNull()?.uhs ?: userHash
        val minecraft = authenticateMinecraft(xstsHash, xsts.token)
        verifyMinecraftEntitlements(minecraft.access_token)
        val profile = minecraftProfile(minecraft.access_token)
        val now = System.currentTimeMillis()
        val account = LauncherAccount(
            id = profile.id,
            profileId = profile.id,
            username = profile.name,
            type = AccountType.MICROSOFT,
            xuid = xuid,
            clientId = clientId,
            skinUrl = profile.skins.firstOrNull { it.state.equals("ACTIVE", true) }?.url ?: profile.skins.firstOrNull()?.url,
            createdAtEpochMs = now,
            lastUsedAtEpochMs = now,
            tokenExpiresAtEpochMs = now + minecraft.expires_in * 1000L
        )
        tokenStore.save(
            account.id,
            AuthSession(
                accessToken = minecraft.access_token,
                refreshToken = microsoft.refresh_token,
                expiresAtEpochMs = now + minecraft.expires_in * 1000L,
                xuid = xuid,
                clientId = clientId
            )
        )
        return account
    }

    private suspend fun authenticateXbox(accessToken: String): XboxTokenResponse {
        val body = """{"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com","RpsTicket":"d=$accessToken"},"RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}"""
        return json.decodeFromString(downloader.postJson("https://user.auth.xboxlive.com/user/authenticate", body, JSON_HEADERS))
    }

    private suspend fun authorizeXsts(xboxToken: String): XboxTokenResponse {
        val body = """{"Properties":{"SandboxId":"RETAIL","UserTokens":["$xboxToken"]},"RelyingParty":"rp://api.minecraftservices.com/","TokenType":"JWT"}"""
        return json.decodeFromString(downloader.postJson("https://xsts.auth.xboxlive.com/xsts/authorize", body, JSON_HEADERS))
    }

    private suspend fun authenticateMinecraft(userHash: String, xstsToken: String): MinecraftTokenResponse {
        val body = json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject { put("identityToken", "XBL3.0 x=$userHash;$xstsToken") }
        )
        return json.decodeFromString(
            downloader.postJson("https://api.minecraftservices.com/authentication/login_with_xbox", body)
        )
    }


    private suspend fun verifyMinecraftEntitlements(accessToken: String) {
        val entitlements = json.decodeFromString<MinecraftEntitlements>(
            downloader.readText(
                "https://api.minecraftservices.com/entitlements/mcstore",
                headers = mapOf("Authorization" to "Bearer $accessToken")
            )
        )
        require(entitlements.items.isNotEmpty()) {
            "This Microsoft account does not own Minecraft: Java Edition or an eligible entitlement"
        }
    }

    private suspend fun minecraftProfile(accessToken: String): MinecraftProfile =
        json.decodeFromString(
            downloader.readText(
                "https://api.minecraftservices.com/minecraft/profile",
                headers = mapOf("Authorization" to "Bearer $accessToken")
            )
        )

    companion object {
        private const val DEVICE_CODE_ENDPOINT = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode"
        private const val TOKEN_ENDPOINT = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
        private val JSON_HEADERS = mapOf("Accept" to "application/json", "x-xbl-contract-version" to "1")
    }
}
