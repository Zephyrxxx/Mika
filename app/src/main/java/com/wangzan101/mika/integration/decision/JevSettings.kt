package com.wangzan101.mika.integration.decision

import android.content.Context
import android.util.AtomicFile
import ai.typesafe.integration.JevConfig
import com.wangzan101.mika.settings.KeystoreKeyCipher
import kotlinx.serialization.json.*
import java.io.File
import java.util.Base64
import com.wangzan101.mika.core.decision.FastMode

data class JevSettings(val enabled: Boolean = false, val config: JevConfig = JevConfig(), val key: String = "",
                       val fastMode: FastMode = FastMode.Off) {
    override fun toString() = "JevSettings(enabled=$enabled, config=$config, key=***, fastMode=$fastMode)"
}

/** Keystore-protected secrets, outside backups and Mika's normal settings export. */
object JevSettingsStore {
    private fun file(c: Context) = AtomicFile(File(c.noBackupFilesDir, "jev/settings.json"))
    private val cipher get() = KeystoreKeyCipher("mika-jev-key-v1")
    @Synchronized fun read(c: Context): JevSettings {
        val f = file(c)
        if (!f.baseFile.exists()) {
            // Keep existing opt-in, endpoint and encrypted credentials. A newly saved
            // record is authoritative; the legacy record is never overwritten here.
            val legacy = com.wangzan101.mika.settings.DecisionSettings.get(c)
            val config = legacy.state.value
            return JevSettings(config.enabled,
                JevConfig(baseUrl = config.endpoint, model = config.model), legacy.apiKey())
        }
        val o = Json.parseToJsonElement(f.openRead().use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject
        require(o.getValue("schemaVersion").jsonPrimitive.int == 1)
        return JevSettings(o.getValue("enabled").jsonPrimitive.boolean,
            JevConfig(baseUrl = o.getValue("baseUrl").jsonPrimitive.content,
                model = o.getValue("model").jsonPrimitive.content,
                budgetMs = o.getValue("budgetMs").jsonPrimitive.long),
            cipher.decrypt(Base64.getDecoder().decode(o.getValue("key").jsonPrimitive.content)).toString(Charsets.UTF_8),
            o["fastMode"]?.jsonPrimitive?.content?.let { FastMode.valueOf(it) } ?: FastMode.Off)
    }
    @Synchronized fun save(c: Context, settings: JevSettings) {
        require(!settings.enabled || settings.key.isNotBlank())
        val o = buildJsonObject {
            put("schemaVersion", 1); put("enabled", settings.enabled)
            put("baseUrl", settings.config.baseUrl); put("model", settings.config.model)
            put("budgetMs", settings.config.budgetMs)
            put("fastMode", settings.fastMode.name)
            put("key", Base64.getEncoder().encodeToString(cipher.encrypt(settings.key.toByteArray(Charsets.UTF_8))))
        }
        val f = file(c)
        f.baseFile.parentFile!!.mkdirs()
        val stream = f.startWrite()
        try { stream.write(o.toString().toByteArray(Charsets.UTF_8)); f.finishWrite(stream) }
        catch (e: Exception) { f.failWrite(stream); throw e }
    }
}
