package id.yomic.mobile.engine.js

import id.yomic.mobile.data.network.YomicNetworkClient
import id.yomic.mobile.engine.webview.WebViewPageExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Loads, caches, and manages installed `.js` extensions stored inside `filesDir/plugins/`.
 */
class JsExtensionManager(
    private val pluginsDir: File,
    private val networkClient: YomicNetworkClient,
    private val webViewExtractor: WebViewPageExtractor? = null
) {
    private val lock = Mutex()
    private val _sources = MutableStateFlow<Map<String, JsExtensionSource>>(emptyMap())
    val sources: StateFlow<Map<String, JsExtensionSource>> = _sources.asStateFlow()

    @Volatile
    private var selectedMangaDexLang: String = "en"

    suspend fun loadAllInstalled(): Map<String, JsExtensionSource> = lock.withLock {
        withContext(Dispatchers.IO) {
            pluginsDir.mkdirs()
            val files = pluginsDir.listFiles { f -> f.isFile && f.extension.equals("js", ignoreCase = true) }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()

            val loaded = LinkedHashMap<String, JsExtensionSource>()
            for (file in files) {
                val pkg = file.nameWithoutExtension.lowercase()
                runCatching {
                    val code = file.readText(Charsets.UTF_8)
                    val src = JsExtensionSource.loadFromCode(
                        pkg = pkg,
                        scriptFileName = file.name,
                        scriptCode = code,
                        networkClient = networkClient,
                        webViewExtractor = webViewExtractor
                    )
                    src.selectedLanguage = selectedMangaDexLang
                    loaded[pkg] = src
                }
            }
            val previous = _sources.value.values.toList()
            _sources.value = loaded
            previous.forEach { runCatching { it.closeSafely() } }
            loaded
        }
    }

    suspend fun installScript(pkg: String, scriptFileName: String, scriptCode: String): JsExtensionSource =
        lock.withLock {
            withContext(Dispatchers.IO) {
                val normalizedPkg = pkg.trim().lowercase()
                val safeFileName = File(scriptFileName).name.ifBlank { "$normalizedPkg.js" }
                // Validate script before writing to disk
                val source = JsExtensionSource.loadFromCode(
                    pkg = normalizedPkg,
                    scriptFileName = safeFileName,
                    scriptCode = scriptCode,
                    networkClient = networkClient,
                    webViewExtractor = webViewExtractor
                )
                source.selectedLanguage = selectedMangaDexLang

                pluginsDir.mkdirs()
                val targetFile = File(pluginsDir, safeFileName)
                targetFile.writeText(scriptCode, Charsets.UTF_8)

                val next = _sources.value.toMutableMap()
                val replaced = next.put(normalizedPkg, source)
                _sources.value = next
                runCatching { replaced?.closeSafely() }
                source
            }
        }

    suspend fun uninstallScript(pkg: String, scriptFileName: String? = null) = lock.withLock {
        withContext(Dispatchers.IO) {
            val normalizedPkg = pkg.trim().lowercase()
            val existing = _sources.value[normalizedPkg]
            val targetName = scriptFileName?.takeIf { it.isNotBlank() }
                ?: existing?.scriptFileName
                ?: "$normalizedPkg.js"

            val file = File(pluginsDir, File(targetName).name)
            if (file.exists()) {
                file.delete()
            }
            val altFile = File(pluginsDir, "$normalizedPkg.js")
            if (altFile.exists()) {
                altFile.delete()
            }

            val next = _sources.value.toMutableMap()
            val removed = next.remove(normalizedPkg)
            _sources.value = next
            runCatching { removed?.closeSafely() }
        }
    }

    fun getSource(pkgOrId: String): JsExtensionSource? {
        val key = pkgOrId.trim().lowercase()
        _sources.value[key]?.let { return it }
        return _sources.value.values.firstOrNull {
            it.pkg.equals(key, ignoreCase = true) ||
                it.name.equals(pkgOrId, ignoreCase = true) ||
                it.id.toString() == pkgOrId
        }
    }

    fun getSourceByNumericId(numericId: Long): JsExtensionSource? =
        _sources.value.values.firstOrNull { it.id == numericId }

    fun getAllSources(): List<JsExtensionSource> = _sources.value.values.toList()

    fun updateMangaDexLanguage(langCode: String) {
        selectedMangaDexLang = if (langCode.equals("id", ignoreCase = true)) "id" else "en"
        _sources.value.values.forEach { it.selectedLanguage = selectedMangaDexLang }
    }

    suspend fun clearAllPlugins() = lock.withLock {
        withContext(Dispatchers.IO) {
            val current = _sources.value.values.toList()
            _sources.value = emptyMap()
            current.forEach { runCatching { it.closeSafely() } }
            pluginsDir.listFiles()?.forEach { runCatching { it.delete() } }
        }
    }
}
