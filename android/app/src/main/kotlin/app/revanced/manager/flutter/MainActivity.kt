package app.revanced.manager.flutter

import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.NonNull
import app.revanced.manager.flutter.utils.Aapt
import app.revanced.manager.flutter.utils.aligning.ZipAligner
import app.revanced.manager.flutter.utils.signing.Signer
import app.revanced.manager.flutter.utils.zip.ZipFile
import app.revanced.manager.flutter.utils.zip.structures.ZipEntry
import app.revanced.patcher.Patcher
import app.revanced.patcher.PatcherOptions
import app.revanced.patcher.annotation.Package
import app.revanced.patcher.data.Context
import app.revanced.patcher.extensions.PatchExtensions.compatiblePackages
import app.revanced.patcher.extensions.PatchExtensions.dependencies
import app.revanced.patcher.extensions.PatchExtensions.description
import app.revanced.patcher.extensions.PatchExtensions.include
import app.revanced.patcher.extensions.PatchExtensions.patchName
import app.revanced.patcher.extensions.PatchExtensions.version
import app.revanced.patcher.logging.Logger
import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.ResourcePatch
import app.revanced.patcher.util.patch.PatchBundle
import dalvik.system.DexClassLoader
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

private const val PATCHER_CHANNEL = "app.revanced.manager.flutter/patcher"
private const val INSTALLER_CHANNEL = "app.revanced.manager.flutter/installer"

class MainActivity : FlutterActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var patches = mutableListOf<Class<out Patch<Context>>>()
    private lateinit var installerChannel: MethodChannel

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val mainChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, PATCHER_CHANNEL)
        installerChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, INSTALLER_CHANNEL)
        mainChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "loadPatches" -> {
                    val jarPatchBundlePath = call.argument<String>("jarPatchBundlePath")
                    val cacheDirPath = call.argument<String>("cacheDirPath")
                    if (jarPatchBundlePath != null && cacheDirPath != null) {
                        loadPatches(result, jarPatchBundlePath, cacheDirPath)
                    } else {
                        result.notImplemented()
                    }
                }

                "getCompatiblePackages" -> getCompatiblePackages(result)
                "getFilteredPatches" -> {
                    val targetPackage = call.argument<String>("targetPackage")
                    val targetVersion = call.argument<String>("targetVersion")
                    val ignoreVersion = call.argument<Boolean>("ignoreVersion")
                    if (targetPackage != null && targetVersion != null && ignoreVersion != null) {
                        getFilteredPatches(result, targetPackage, targetVersion, ignoreVersion)
                    } else {
                        result.notImplemented()
                    }
                }

                "needsResourcePatching" -> {
                    val selectedPatches = call.argument<List<String>>("selectedPatches")
                    val packageName = call.argument<String>("packageName")
                    if (selectedPatches != null && packageName != null) {
                        needsResourcePatching(result, selectedPatches, packageName)
                    } else {
                        result.notImplemented()
                    }
                }

                "runPatcher" -> {
                    val originalFilePath = call.argument<String>("originalFilePath")
                    val inputFilePath = call.argument<String>("inputFilePath")
                    val patchedFilePath = call.argument<String>("patchedFilePath")
                    val outFilePath = call.argument<String>("outFilePath")
                    val integrationsPath = call.argument<String>("integrationsPath")
                    val selectedPatches = call.argument<List<String>>("selectedPatches")
                    val cacheDirPath = call.argument<String>("cacheDirPath")
                    val keyStoreFilePath = call.argument<String>("keyStoreFilePath")
                    val keystorePassword = call.argument<String>("keystorePassword")

                    if (originalFilePath != null &&
                        inputFilePath != null &&
                        patchedFilePath != null &&
                        outFilePath != null &&
                        integrationsPath != null &&
                        selectedPatches != null &&
                        cacheDirPath != null &&
                        keyStoreFilePath != null &&
                        keystorePassword != null
                    ) {
                        runPatcher(
                            result,
                            originalFilePath,
                            inputFilePath,
                            patchedFilePath,
                            outFilePath,
                            integrationsPath,
                            selectedPatches,
                            cacheDirPath,
                            keyStoreFilePath,
                            keystorePassword
                        )
                    } else {
                        result.notImplemented()
                    }
                }

                else -> result.notImplemented()
            }
        }
    }

    fun loadPatches(
        result: MethodChannel.Result,
        jarPatchBundlePath: String,
        cacheDirPath: String
    ) {
        Thread(
            Runnable {
                patches.addAll(
                    PatchBundle.Dex(
                        jarPatchBundlePath,
                        DexClassLoader(
                            jarPatchBundlePath,
                            cacheDirPath,
                            null,
                            javaClass.classLoader
                        )
                    ).loadPatches()
                )
                handler.post { result.success(null) }
            }
        )
            .start()
    }

    fun getCompatiblePackages(result: MethodChannel.Result) {
        Thread(
            Runnable {
                val filteredPackages = mutableListOf<String>()
                patches.forEach patch@{ patch ->
                    patch.compatiblePackages?.forEach { pkg ->
                        filteredPackages.add(pkg.name)
                    }
                }
                handler.post { result.success(filteredPackages.distinct()) }
            }
        )
            .start()
    }

    fun needsResourcePatching(
        result: MethodChannel.Result,
        selectedPatches: List<String>,
        packageName: String
    ) {
        Thread(
            Runnable {
                fun Class<out Patch<Context>>.anyRecursively(predicate: (Class<out Patch<Context>>) -> Boolean): Boolean =
                    predicate(this) || dependencies?.any { it.java.anyRecursively(predicate) } == true

                var hasResourcePatch = false
                val filteredPatches = recoverPatchesList(selectedPatches, packageName)
                for (patch in filteredPatches) {
                    if (patch.anyRecursively { ResourcePatch::class.java.isAssignableFrom(it) }) {
                        hasResourcePatch = true
                        break
                    }
                }

                handler.post { result.success(hasResourcePatch) }
            }
        )
            .start()
    }

    fun getFilteredPatches(
        result: MethodChannel.Result,
        targetPackage: String,
        targetVersion: String,
        ignoreVersion: Boolean
    ) {
        Thread(
            Runnable {
                val filteredPatches = mutableListOf<Map<String, Any?>>()
                patches.forEach patch@{ patch ->
                    patch.compatiblePackages?.forEach { pkg ->
                        if (pkg.name == targetPackage &&
                            (ignoreVersion ||
                                    pkg.versions.isNotEmpty() ||
                                    pkg.versions.contains(targetVersion))
                        ) {
                            var p = mutableMapOf<String, Any?>()
                            p.put("name", patch.patchName)
                            p.put("version", patch.version)
                            p.put("description", patch.description)
                            p.put("include", patch.include)
                            filteredPatches.add(p)
                        }
                    }
                }
                handler.post { result.success(filteredPatches) }
            }
        )
            .start()
    }

    private fun recoverPatchesList(selectedPatches: List<String>, packageName: String) = patches.filter { patch ->
        (patch.compatiblePackages?.any { it.name == packageName } == true || patch.compatiblePackages.isNullOrEmpty()) &&
                selectedPatches.any { it == patch.patchName }
    }

    private fun runPatcher(
        result: MethodChannel.Result,
        originalFilePath: String,
        inputFilePath: String,
        patchedFilePath: String,
        outFilePath: String,
        integrationsPath: String,
        selectedPatches: List<String>,
        cacheDirPath: String,
        keyStoreFilePath: String,
        keystorePassword: String
    ) {
        val originalFile = File(originalFilePath)
        val inputFile = File(inputFilePath)
        val patchedFile = File(patchedFilePath)
        val outFile = File(outFilePath)
        val integrations = File(integrationsPath)
        val keyStoreFile = File(keyStoreFilePath)

        Thread {
            try {
                handler.post {
                    installerChannel.invokeMethod(
                        "update",
                        mapOf(
                            "progress" to 0.1,
                            "header" to "",
                            "log" to "Copying original apk"
                        )
                    )
                }
                originalFile.copyTo(inputFile, true)

                handler.post {
                    installerChannel.invokeMethod(
                        "update",
                        mapOf(
                            "progress" to 0.2,
                            "header" to "Unpacking apk...",
                            "log" to "Unpacking input apk"
                        )
                    )
                }
                val patcher =
                    Patcher(
                        PatcherOptions(
                            inputFile,
                            cacheDirPath,
                            Aapt.binary(applicationContext).absolutePath,
                            cacheDirPath,
                            logger = ManagerLogger()
                        )
                    )

                handler.post {
                    installerChannel.invokeMethod(
                        "update",
                        mapOf("progress" to 0.3, "header" to "", "log" to "")
                    )
                }
                handler.post {
                    installerChannel.invokeMethod(
                        "update",
                        mapOf(
                            "progress" to 0.4,
                            "header" to "Merging integrations...",
                            "log" to "Merging integrations"
                        )
                    )
                }
                patcher.addIntegrations(listOf(integrations)) {}

                handler.post {
                    installerChannel.invokeMethod(
                        "update",
                        mapOf(
                            "progress" to 0.5,
                            "header" to "Applying patches...",
                            "log" to ""
                        )
                    )
                }

                patcher.addPatches(recoverPatchesList(selectedPatches, patcher.context.packageMetadata.packageName))
                patcher.executePatches().forEach { (patch, res) ->
                    if (res.isSuccess) {
                        val msg = "Applied $patch"
                        handler.post {
                            installerChannel.invokeMethod(
                                "update",
                                mapOf(
                                    "progress" to 0.5,
                                    "header" to "",
                                    "log" to msg
                                )
                            )
                        }
                        return@forEach
                    }
                    val msg =
                        "Failed to apply $patch: " + "${res.exceptionOrNull()!!.message ?: res.exceptionOrNull()!!.cause!!::class.simpleName}"
                    handler.post {
                        installerChannel.invokeMethod(
                            "update",
                            mapOf("progress" to 0.5, "header" to "", "log" to msg)
                        )
                    }
                }

                handler.post {
                    installerChannel.invokeMethod(
                        "update",
                        mapOf(
                            "progress" to 0.7,
                            "header" to "Repacking apk...",
                            "log" to "Repacking patched apk"
                        )
                    )
                }
                val res = patcher.save()
                ZipFile(patchedFile).use { file ->
                    res.dexFiles.forEach {
                        file.addEntryCompressData(
                            ZipEntry.createWithName(it.name),
                            it.stream.readBytes()
                        )
                    }
                    res.resourceFile?.let {
                        file.copyEntriesFromFileAligned(
                            ZipFile(it),
                            ZipAligner::getEntryAlignment
                        )
                    }
                    file.copyEntriesFromFileAligned(
                        ZipFile(inputFile),
                        ZipAligner::getEntryAlignment
                    )
                }
                handler.post {
                    installerChannel.invokeMethod(
                        "update",
                        mapOf(
                            "progress" to 0.9,
                            "header" to "Signing apk...",
                            "log" to ""
                        )
                    )
                }

                // Signer("ReVanced", "s3cur3p@ssw0rd").signApk(patchedFile, outFile, keyStoreFile)

                try {
                    Signer("ReVanced", keystorePassword).signApk(patchedFile, outFile, keyStoreFile)
                } catch (e: Exception) {
                    //log to console
                    print("Error signing apk: ${e.message}")
                    e.printStackTrace()
                }

                handler.post {
                    installerChannel.invokeMethod(
                        "update",
                        mapOf(
                            "progress" to 1.0,
                            "header" to "Finished!",
                            "log" to "Finished!"
                        )
                    )
                }
            } catch (ex: Throwable) {
                val stack = ex.stackTraceToString()
                handler.post {
                    installerChannel.invokeMethod(
                        "update",
                        mapOf(
                            "progress" to -100.0,
                            "header" to "Aborting...",
                            "log" to "An error occurred! Aborting\nError:\n$stack"
                        )
                    )
                }
            }
            handler.post { result.success(null) }
        }.start()
    }

    inner class ManagerLogger : Logger {
        override fun error(msg: String) {
            handler.post {
                installerChannel
                    .invokeMethod(
                        "update",
                        mapOf("progress" to -1.0, "header" to "", "log" to msg)
                    )
            }
        }

        override fun warn(msg: String) {
            handler.post {
                installerChannel.invokeMethod(
                    "update",
                    mapOf("progress" to -1.0, "header" to "", "log" to msg)
                )
            }
        }

        override fun info(msg: String) {
            handler.post {
                installerChannel.invokeMethod(
                    "update",
                    mapOf("progress" to -1.0, "header" to "", "log" to msg)
                )
            }
        }

        override fun trace(_msg: String) { /* unused */
        }
    }
}
