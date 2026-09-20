package app.readflow.diagnostics

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class GitHubConfiguration(val destination: GitHubDestination = GitHubDestination(), val installationId: String = UUID.randomUUID().toString())

class GitHubSettings(context: Context) {
    private val root = File(context.noBackupFilesDir, "github-diagnostics")
    private val configFile = AtomicFile(File(root, "destination.json"))
    private val tokenFile = AtomicFile(File(root, "token.enc"))
    private val lock = Mutex()
    private val alias = "readflow-github-token-v1"
    private val mutable = MutableStateFlow(runCatching { Json.decodeFromString<GitHubConfiguration>(configFile.openRead().use { it.readBytes().toString(Charsets.UTF_8) }) }.getOrDefault(GitHubConfiguration()))
    val configuration = mutable.asStateFlow()
    val hasToken get() = tokenFile.baseFile.exists()

    suspend fun save(destination: GitHubDestination, newToken: String) = withContext(Dispatchers.IO) { lock.withLock {
        destination.validate()
        root.mkdirs()
        val old = mutable.value
        val sameRepository = destination.repository.equals(old.destination.repository, ignoreCase = true)
        val next = old.copy(destination = destination.copy(issueNumber = if (sameRepository) old.destination.issueNumber else 0))
        if (newToken.isNotBlank()) {
            validateGitHubToken(newToken)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            write(tokenFile, byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(newToken.toByteArray()))
        }
        write(configFile, Json.encodeToString(GitHubConfiguration.serializer(), next).toByteArray())
        mutable.value = next
    } }
    suspend fun token(): String = withContext(Dispatchers.IO) { lock.withLock {
        check(hasToken) { "Add a repository-scoped GitHub token in GitHub settings" }
        try {
            val bytes = tokenFile.openRead().use { it.readNBytes(2048) }
            val length = bytes.first().toInt() and 255
            require(length in 12..16 && bytes.size > length + 16)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 1 + length)))
            }
            cipher.doFinal(bytes.copyOfRange(1 + length, bytes.size)).toString(Charsets.UTF_8)
        } catch (_: Exception) { error("Saved GitHub credential is unavailable; enter a new token") }
    } }
    suspend fun saveIssue(repository: String, number: Int) = withContext(Dispatchers.IO) { lock.withLock {
        check(mutable.value.destination.repository == repository) { "GitHub destination changed during upload" }
        val next = mutable.value.copy(destination = mutable.value.destination.copy(issueNumber = number))
        root.mkdirs(); write(configFile, Json.encodeToString(GitHubConfiguration.serializer(), next).toByteArray()); mutable.value = next
    } }
    suspend fun forgetToken() = withContext(Dispatchers.IO) { lock.withLock { tokenFile.delete() } }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
    }
    private fun write(file: AtomicFile, bytes: ByteArray) {
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (error: Exception) { file.failWrite(output); throw error }
    }
}
