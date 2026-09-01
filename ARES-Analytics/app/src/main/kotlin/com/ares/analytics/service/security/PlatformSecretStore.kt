package com.ares.analytics.service.security

import com.ares.analytics.service.AppDataPaths
import com.ares.analytics.service.writeSecrets
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.PointerType
import com.sun.jna.platform.mac.CoreFoundation
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.util.Base64
import java.util.Locale

/** A platform-owned, current-user secret store. Implementations must never persist plaintext. */
internal interface PlatformSecretStore {
    fun read(key: String): ByteArray?
    fun write(key: String, bytes: ByteArray)
    fun delete(key: String): Boolean
    val protectionDescription: String
}

internal fun createPlatformSecretStore(): PlatformSecretStore {
    val osName = System.getProperty("os.name").lowercase(Locale.ROOT)
    return when {
        osName.contains("win") -> WindowsDpapiSecretStore(AppDataPaths.file("credentials"))
        osName.contains("mac") -> MacOsKeychainSecretStore()
        osName.contains("linux") -> LinuxSecretServiceStore()
        else -> error("ARES Robotics Studio does not support secure credential storage on ${System.getProperty("os.name")}")
    }
}

internal class WindowsDpapiSecretStore(
    private val directory: File,
    private val writer: (File, ByteArray) -> Unit = ::writeSecrets,
) : PlatformSecretStore {
    override fun read(key: String): ByteArray? = fileFor(key)
        .takeIf(File::isFile)
        ?.readBytes()
        ?.let(Crypt32Util::cryptUnprotectData)

    override fun write(key: String, bytes: ByteArray) {
        writer(fileFor(key), Crypt32Util.cryptProtectData(bytes))
    }

    override fun delete(key: String): Boolean = fileFor(key).let { !it.exists() || it.delete() }

    override val protectionDescription: String = "Windows DPAPI (current user)"

    private fun fileFor(key: String): File {
        requireSecretKey(key)
        return directory.resolve("$key.dpapi")
    }
}

/** Uses SecItem with a generic-password item in the current user's macOS Keychain. */
internal class MacOsKeychainSecretStore(
    private val serviceName: String = SERVICE_NAME,
    private val security: MacSecurity = MacSecurity.instance(),
) : PlatformSecretStore {
    override fun read(key: String): ByteArray? = withQuery(key) { query, _ ->
        query.setValue(security.constant("kSecReturnData"), security.booleanTrue())
        val result = PointerByReference()
        when (val status = security.api.SecItemCopyMatching(query, result)) {
            ERR_SEC_SUCCESS -> CoreFoundation.CFDataRef(result.value).let { data ->
                try {
                    data.bytePtr.getByteArray(0, data.length)
                } finally {
                    CoreFoundation.INSTANCE.CFRelease(data)
                }
            }
            ERR_SEC_ITEM_NOT_FOUND -> null
            else -> throw keychainFailure("read", status)
        }
    }

    override fun write(key: String, bytes: ByteArray) = withQuery(key) { query, retained ->
        val dataMemory = Memory(bytes.size.toLong().coerceAtLeast(1L))
        if (bytes.isNotEmpty()) dataMemory.write(0, bytes, 0, bytes.size)
        val data = CoreFoundation.INSTANCE.CFDataCreate(
            null,
            dataMemory,
            CoreFoundation.CFIndex(bytes.size.toLong()),
        )
        retained += data
        val attributes = mutableDictionary()
        retained += attributes
        attributes.setValue(security.constant("kSecValueData"), data)
        when (val updateStatus = security.api.SecItemUpdate(query, attributes)) {
            ERR_SEC_SUCCESS -> Unit
            ERR_SEC_ITEM_NOT_FOUND -> {
                query.setValue(security.constant("kSecValueData"), data)
                val addStatus = security.api.SecItemAdd(query, null)
                if (addStatus != ERR_SEC_SUCCESS) throw keychainFailure("write", addStatus)
            }
            else -> throw keychainFailure("write", updateStatus)
        }
    }

    override fun delete(key: String): Boolean = withQuery(key) { query, _ ->
        when (val status = security.api.SecItemDelete(query)) {
            ERR_SEC_SUCCESS, ERR_SEC_ITEM_NOT_FOUND -> true
            else -> throw keychainFailure("delete", status)
        }
    }

    override val protectionDescription: String = "macOS Keychain (current user)"

    private fun <T> withQuery(key: String, block: (CoreFoundation.CFMutableDictionaryRef, MutableList<CoreFoundation.CFTypeRef>) -> T): T {
        requireSecretKey(key)
        val retained = mutableListOf<CoreFoundation.CFTypeRef>()
        return try {
            val query = mutableDictionary()
            retained += query
            val service = CoreFoundation.CFStringRef.createCFString(serviceName)
            val account = CoreFoundation.CFStringRef.createCFString(key)
            retained += service
            retained += account
            query.setValue(security.constant("kSecClass"), security.constant("kSecClassGenericPassword"))
            query.setValue(security.constant("kSecAttrService"), service)
            query.setValue(security.constant("kSecAttrAccount"), account)
            block(query, retained)
        } finally {
            retained.asReversed().forEach(CoreFoundation.INSTANCE::CFRelease)
        }
    }

    private fun mutableDictionary(): CoreFoundation.CFMutableDictionaryRef =
        CoreFoundation.INSTANCE.CFDictionaryCreateMutable(null, CoreFoundation.CFIndex(0), null, null)

    private fun keychainFailure(operation: String, status: Int) =
        IllegalStateException("macOS Keychain could not $operation an ARES credential (OSStatus $status)")
}

internal class LinuxSecretServiceStore(
    private val command: SecretToolCommand = ProcessSecretToolCommand(),
) : PlatformSecretStore {
    override fun read(key: String): ByteArray? {
        requireSecretKey(key)
        val result = command.run(listOf("lookup", APPLICATION_ATTRIBUTE, SERVICE_NAME, KEY_ATTRIBUTE, key))
        if (result.exitCode == 1 && result.output.isBlank()) return null
        requireSuccess("read", result)
        return runCatching { Base64.getDecoder().decode(result.output.trim()) }
            .getOrElse { throw IllegalStateException("Linux Secret Service returned an invalid ARES credential", it) }
    }

    override fun write(key: String, bytes: ByteArray) {
        requireSecretKey(key)
        val result = command.run(
            arguments = listOf(
                "store",
                "--label=ARES Robotics Studio credential",
                APPLICATION_ATTRIBUTE,
                SERVICE_NAME,
                KEY_ATTRIBUTE,
                key,
            ),
            standardInput = Base64.getEncoder().encodeToString(bytes) + "\n",
        )
        requireSuccess("write", result)
    }

    override fun delete(key: String): Boolean {
        requireSecretKey(key)
        val result = command.run(listOf("clear", APPLICATION_ATTRIBUTE, SERVICE_NAME, KEY_ATTRIBUTE, key))
        if (result.exitCode == 1 && result.output.isBlank()) return true
        requireSuccess("delete", result)
        return true
    }

    override val protectionDescription: String = "Linux Secret Service (current user keyring)"

    private fun requireSuccess(operation: String, result: SecretToolResult) {
        check(result.exitCode == 0) {
            val detail = result.output.trim().take(240).ifBlank { "no diagnostic was returned" }
            "Linux Secret Service could not $operation an ARES credential: $detail"
        }
    }
}

internal data class SecretToolResult(val exitCode: Int, val output: String)

internal fun interface SecretToolCommand {
    fun run(arguments: List<String>, standardInput: String?): SecretToolResult

    fun run(arguments: List<String>): SecretToolResult = run(arguments, null)
}

private class ProcessSecretToolCommand : SecretToolCommand {
    override fun run(arguments: List<String>, standardInput: String?): SecretToolResult {
        val process = runCatching { ProcessBuilder(listOf("secret-tool") + arguments).redirectErrorStream(true).start() }
            .getOrElse {
                throw IllegalStateException(
                    "Linux Secret Service requires the 'secret-tool' command and an unlocked user keyring",
                    it,
                )
            }
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { input ->
            standardInput?.let(input::write)
        }
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return SecretToolResult(process.waitFor(), output)
    }
}

internal class MacSecurity private constructor(
    internal val api: SecurityFramework,
    private val securityLibrary: NativeLibrary,
    private val coreFoundationLibrary: NativeLibrary,
) {
    fun constant(name: String): CoreFoundation.CFTypeRef = CoreFoundation.CFTypeRef(
        securityLibrary.getGlobalVariableAddress(name).getPointer(0),
    )

    fun booleanTrue(): CoreFoundation.CFTypeRef = CoreFoundation.CFTypeRef(
        coreFoundationLibrary.getGlobalVariableAddress("kCFBooleanTrue").getPointer(0),
    )

    companion object {
        fun instance(): MacSecurity = MacSecurity(
            api = Native.load("Security", SecurityFramework::class.java),
            securityLibrary = NativeLibrary.getInstance("Security"),
            coreFoundationLibrary = NativeLibrary.getInstance("CoreFoundation"),
        )
    }
}

internal interface SecurityFramework : Library {
    fun SecItemCopyMatching(query: CoreFoundation.CFDictionaryRef, result: PointerByReference): Int
    fun SecItemAdd(attributes: CoreFoundation.CFDictionaryRef, result: PointerByReference?): Int
    fun SecItemUpdate(query: CoreFoundation.CFDictionaryRef, attributesToUpdate: CoreFoundation.CFDictionaryRef): Int
    fun SecItemDelete(query: CoreFoundation.CFDictionaryRef): Int
}

private fun requireSecretKey(key: String) {
    require(key.matches(Regex("[a-z0-9][a-z0-9._-]{0,127}"))) { "Secret key is invalid" }
}

private const val SERVICE_NAME = "org.aresfirst.robotics-studio"
private const val APPLICATION_ATTRIBUTE = "application"
private const val KEY_ATTRIBUTE = "credential"
private const val ERR_SEC_SUCCESS = 0
private const val ERR_SEC_ITEM_NOT_FOUND = -25300
