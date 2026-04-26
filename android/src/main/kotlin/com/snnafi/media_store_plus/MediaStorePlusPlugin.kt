package com.snnafi.media_store_plus

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.content.IntentSender.SendIntentException
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.NonNull
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import com.mpatric.mp3agic.ID3v24Tag
import com.mpatric.mp3agic.Mp3File
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.*
import java.util.concurrent.Executors


fun String.capitalized(): String {
    return this.replaceFirstChar {
        if (it.isLowerCase())
            it.titlecase(Locale.getDefault())
        else it.toString()
    }
}

/** MediaStorePlusPlugin */
class MediaStorePlusPlugin : FlutterPlugin, MethodCallHandler, ActivityAware,
    PluginRegistry.ActivityResultListener {
        private var activity: Activity? = null
        private var activityBinding: ActivityPluginBinding? = null
        private lateinit var channel: MethodChannel
        private val pendingRequests = mutableMapOf<Int, PendingRequest>()
        private val mainHandler = Handler(Looper.getMainLooper())
        private val ioExecutor = Executors.newSingleThreadExecutor()
        private lateinit var uriString: String
        private lateinit var fileName: String
        private lateinit var tempFilePath: String
        private var dirType: Int = 0
        private lateinit var dirName: String
        private lateinit var appFolder: String
        private var externalVolumeName: String? = null
        private var id3v2Tags: Map<String, String>? = null
        private var shouldAddCover: Boolean = false
        private val TAG = "MediaStorage"
        private val maxMp3BytesForId3Rewrite = 32L * 1024L * 1024L
        private val maxExistingId3TagBytes = 2L * 1024L * 1024L
        private val maxArtworkBytesForId3 = 1024L * 1024L
        private val id3RewriteHeapOverheadBytes = 16L * 1024L * 1024L
        private val maxDocumentTreeChildren = 500

        private data class PendingRequest(
            val reply: OneShotResult,
            val uriString: String? = null,
            val fileName: String? = null,
            val tempFilePath: String? = null,
            val dirType: Int = 0,
            val dirName: String? = null,
            val appFolder: String? = null,
            val externalVolumeName: String? = null,
            val id3v2Tags: Map<String, String>? = null,
            val shouldAddCover: Boolean = false,
        )

        private inner class OneShotResult(private val delegate: Result) : Result {
            private var completed = false

            @Synchronized
            override fun success(result: Any?) {
                if (completed) return
                completed = true
                try {
                    delegate.success(result)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Ignoring duplicate MediaStorePlus result", e)
                }
            }

            @Synchronized
            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                if (completed) return
                completed = true
                try {
                    delegate.error(errorCode, errorMessage, errorDetails)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Ignoring duplicate MediaStorePlus error result", e)
                }
            }

            @Synchronized
            override fun notImplemented() {
                if (completed) return
                completed = true
                try {
                    delegate.notImplemented()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Ignoring duplicate MediaStorePlus notImplemented result", e)
                }
            }
        }

        private fun runOnIo(reply: OneShotResult, failureValue: Any? = false, block: () -> Unit) {
            ioExecutor.execute {
                try {
                    block()
                } catch (e: Exception) {
                    Log.e(TAG, "I/O operation failed", e)
                    reply.success(failureValue)
                }
            }
        }

        private fun savePendingRequest(requestCode: Int, pendingRequest: PendingRequest) {
            pendingRequests.remove(requestCode)?.reply?.success(false)
            pendingRequests[requestCode] = pendingRequest
        }

        private fun takePendingRequest(requestCode: Int): PendingRequest? {
            return pendingRequests.remove(requestCode)
        }

        private fun finishPendingResults(value: Any?) {
            pendingRequests.values.forEach { it.reply.success(value) }
            pendingRequests.clear()
        }

        private fun pendingRequestFor(reply: OneShotResult): PendingRequest {
            return PendingRequest(
                reply = reply,
                uriString = if (::uriString.isInitialized) uriString else null,
                fileName = if (::fileName.isInitialized) fileName else null,
                tempFilePath = if (::tempFilePath.isInitialized) tempFilePath else null,
                dirType = dirType,
                dirName = if (::dirName.isInitialized) dirName else null,
                appFolder = if (::appFolder.isInitialized) appFolder else null,
                externalVolumeName = externalVolumeName,
                id3v2Tags = id3v2Tags,
                shouldAddCover = shouldAddCover,
            )
        }

        private fun launchRecoverableRequest(
            exception: Exception,
            requestCode: Int,
            pendingResult: OneShotResult,
        ): Boolean {
            val recoverableSecurityException = exception as? RecoverableSecurityException ?: return false
            val intentSender = recoverableSecurityException.userAction.actionIntent.intentSender
            val pendingRequest = pendingRequestFor(pendingResult)
            mainHandler.post {
                val currentActivity = activity
                if (currentActivity == null) {
                    pendingResult.success(false)
                    return@post
                }

                savePendingRequest(requestCode, pendingRequest)
                try {
                    currentActivity.startIntentSenderForResult(
                        intentSender, requestCode, null, 0, 0, 0, null
                    )
                } catch (e: SendIntentException) {
                    pendingRequests.remove(requestCode)
                    Log.e(TAG, "Could not launch recoverable request $requestCode", e)
                    pendingResult.success(false)
                }
            }
            return true
        }


    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "media_store_plus")
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        val reply = OneShotResult(result)
        Log.d(TAG, "call.method: ${call.method}")
        if (call.method == "getPlatformSDKInt") {
            reply.success(Build.VERSION.SDK_INT)
        } else if (call.method == "saveFile") {
            runOnIo(reply) {
                saveFile(
                    Uri.parse(call.argument("tempFilePath")!!).path!!,
                    call.argument("fileName")!!,
                    call.argument("appFolder")!!,
                    call.argument("dirType")!!,
                    call.argument("dirName")!!,
                    call.argument("externalVolumeName"),
                    call.argument("id3v2Tags"),
                    call.argument("shouldAddCover")!!,
                    reply,
                )
            }
        } else if (call.method == "deleteFile") {
            runOnIo(reply) {
                deleteFile(
                    call.argument("fileName")!!,
                    call.argument("appFolder")!!,
                    call.argument("dirType")!!,
                    call.argument("dirName")!!,
                    reply,
                )
            }
        } else if (call.method == "getFileUri") {
            runOnIo(reply, failureValue = null) {
                val uri: Uri? = getUriFromDisplayName(
                    call.argument("fileName")!!,
                    call.argument("appFolder")!!,
                    call.argument("dirType")!!,
                    call.argument("dirName")!!,
                    call.argument("externalVolumeName"),
                )
                reply.success(uri?.toString()?.trim())
            }
        } else if (call.method == "getUriFromFilePath") {
            uriFromFilePath(Uri.parse(call.argument("filePath")!!).path!!, reply)
        } else if (call.method == "requestForAccess") {
            requestForAccess(Uri.parse(call.argument("initialRelativePath")!!).path!!, reply)
        } else if (call.method == "editFile") {
            runOnIo(reply) {
                editFile(
                    call.argument("contentUri")!!,
                    Uri.parse(call.argument("tempFilePath")!!).path!!,
                    reply,
                )
            }
        } else if (call.method == "deleteFileUsingUri") {
            runOnIo(reply) {
                deleteFileUsingUri(
                    call.argument("contentUri")!!,
                    reply,
                )
            }
        } else if (call.method == "isFileDeletable") {
            runOnIo(reply) {
                reply.success(
                    isDeletable(
                        call.argument("contentUri")!!,
                    )
                )
            }
        } else if (call.method == "isFileWritable") {
            runOnIo(reply) {
                reply.success(
                    isWritable(
                        call.argument("contentUri")!!,
                    )
                )
            }
        } else if (call.method == "readFile") {
            runOnIo(reply) {
                readFile(
                    Uri.parse(call.argument("tempFilePath")!!).path!!,
                    call.argument("fileName")!!,
                    call.argument("appFolder")!!,
                    call.argument("dirType")!!,
                    call.argument("dirName")!!,
                    call.argument("externalVolumeName"),
                    reply,
                )
            }
        } else if (call.method == "readFileUsingUri") {
            runOnIo(reply) {
                readFileUsingUri(
                    call.argument("contentUri")!!,
                    Uri.parse(call.argument("tempFilePath")!!).path!!,
                    reply,
                )
            }
        } else if (call.method == "isFileUriExist") {
            runOnIo(reply) {
                reply.success(
                    isFileUriExist(
                        call.argument("contentUri")!!,
                    )
                )
            }
        } else if (call.method == "getDocumentTree") {
            runOnIo(reply, failureValue = "") {
                getFolderChildren(
                    call.argument("contentUri")!!,
                    reply,
                )
            }
        } else {
            reply.notImplemented()
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding?.removeActivityResultListener(this)
        activityBinding = binding
        this.activity = binding.activity
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        finishPendingResults(false)
        channel.setMethodCallHandler(null)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activityBinding?.removeActivityResultListener(this)
        activityBinding = null
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        activityBinding?.removeActivityResultListener(this)
        activityBinding = null
        activity = null
        finishPendingResults(false)
    }

    private fun saveFile(
        path: String,
        name: String,
        appFolder: String,
        dirType: Int,
        dirName: String,
        externalVolumeName: String?,
        id3v2Tags: Map<String, String>?,
        shouldAddCover: Boolean,
        reply: OneShotResult,
    ) {
        this.fileName = name
        this.tempFilePath = path
        this.appFolder = appFolder
        this.dirType = dirType
        this.dirName = dirName
        this.externalVolumeName = externalVolumeName
        this.id3v2Tags = id3v2Tags
        this.shouldAddCover = shouldAddCover
        try {
            createOrUpdateFile(
                path,
                name,
                appFolder,
                dirType,
                dirName,
                externalVolumeName,
                id3v2Tags,
                shouldAddCover,
            )
            File(path).delete()
            reply.success(true)

        } catch (e: Exception) {
            if (!launchRecoverableRequest(e, 990, reply)) {
                reply.success(false)
            }
            Log.e("Exception", e.message, e)
        }
    }

    private fun deleteFile(
        name: String,
        appFolder: String,
        dirType: Int,
        dirName: String,
        reply: OneShotResult,
    ) {
        try {
            this.fileName = name
            this.tempFilePath = ""
            this.appFolder = appFolder
            this.dirType = dirType
            this.dirName = dirName
            val status: Boolean = deleteFileUsingDisplayName(
                name,
                appFolder,
                dirType,
                dirName,
                null
            )
            reply.success(status)
        } catch (e: Exception) {
            if (!launchRecoverableRequest(e, 991, reply)) {
                reply.success(false)
            }
            Log.e("Exception", e.message, e)
        }
    }

    private fun defineVolume(externalVolumeName: String?): String {
        return if (externalVolumeName != null) {
            MediaStore.getExternalVolumeNames(activity!!.applicationContext)
                .find { it.lowercase() == externalVolumeName.lowercase() }
                ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
        } else {
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun saveId3(
        file: String,
        id3v2Tags: Map<String, String>?,
        shouldAddCover: Boolean = false,
    ) {
        if (id3v2Tags == null) {
            return
        }

        val mp3Source = File(file)
        val skipReason = reasonToSkipId3Rewrite(mp3Source, id3v2Tags, shouldAddCover)
        if (skipReason != null) {
            Log.w(TAG, "Skipping ID3v2 tags for ${mp3Source.name}: $skipReason")
            return
        }

        var temporaryTaggedFile: File? = null
        try {
            val mp3File = Mp3File(file)

            val id3v24Tag = ID3v24Tag()
            id3v24Tag.title = id3v2Tags["title"]
            id3v24Tag.comment = id3v2Tags["comment"]
            id3v24Tag.album = id3v2Tags["album"]
            id3v24Tag.artist = id3v2Tags["artist"]
            id3v24Tag.url = java.lang.String.format(
                "https://www.suamusica.com.br/perfil/%s?playlistId=%s&albumId=%s&musicId=%s",
                id3v2Tags["artistId"],
                id3v2Tags["playlistId"],
                id3v2Tags["albumId"],
                id3v2Tags["musicId"]
            )

            val artworkFile = id3v2Tags["artwork"]?.let { File(it) }
            if (shouldAddCover && artworkFile?.exists() == true) {
                id3v24Tag.setAlbumImage(artworkFile.readBytes(), "image/jpeg")
            }

            mp3File.id3v2Tag = id3v24Tag
            val newFilename = "$file.tmp"
            temporaryTaggedFile = File(newFilename)
            mp3File.save(newFilename)

            temporaryTaggedFile.renameTo(File(file))

            Log.i(TAG, "Successfully set ID3v2 tags")
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Skipping ID3v2 tags after OutOfMemoryError", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set ID3v2 tags", e)
        } finally {
            temporaryTaggedFile?.takeIf { it.exists() }?.delete()
        }
    }

    private fun reasonToSkipId3Rewrite(
        mp3Source: File,
        id3v2Tags: Map<String, String>,
        shouldAddCover: Boolean,
    ): String? {
        if (!mp3Source.exists()) {
            return "source file does not exist"
        }

        val fileSize = mp3Source.length()
        if (fileSize > maxMp3BytesForId3Rewrite) {
            return "source file is too large (${fileSize} bytes)"
        }

        val existingTagSize = readId3v2TagSize(mp3Source)
        if (existingTagSize != null && existingTagSize > maxExistingId3TagBytes) {
            return "existing ID3v2 tag is too large (${existingTagSize} bytes)"
        }

        val artworkSize = if (shouldAddCover) {
            val artwork = id3v2Tags["artwork"]?.let { File(it) }
            if (artwork?.exists() == true) artwork.length() else 0L
        } else {
            0L
        }
        if (artworkSize > maxArtworkBytesForId3) {
            return "artwork is too large (${artworkSize} bytes)"
        }

        val requiredHeap = (fileSize * 2L) + artworkSize + id3RewriteHeapOverheadBytes
        val availableHeap = availableHeapBytes()
        if (availableHeap < requiredHeap) {
            return "not enough heap for ID3 rewrite (available=${availableHeap}, required=${requiredHeap})"
        }

        return null
    }

    private fun availableHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        val usedHeap = runtime.totalMemory() - runtime.freeMemory()
        return runtime.maxMemory() - usedHeap
    }

    private fun readId3v2TagSize(file: File): Long? {
        file.inputStream().use { input ->
            val header = ByteArray(10)
            if (input.read(header) != header.size) {
                return null
            }
            if (
                header[0] != 'I'.code.toByte() ||
                header[1] != 'D'.code.toByte() ||
                header[2] != '3'.code.toByte()
            ) {
                return null
            }
            if (
                (header[6].toInt() and 0x80) != 0 ||
                (header[7].toInt() and 0x80) != 0 ||
                (header[8].toInt() and 0x80) != 0 ||
                (header[9].toInt() and 0x80) != 0
            ) {
                return null
            }

            val tagSize =
                ((header[6].toLong() and 0x7FL) shl 21) or
                    ((header[7].toLong() and 0x7FL) shl 14) or
                    ((header[8].toLong() and 0x7FL) shl 7) or
                    (header[9].toLong() and 0x7FL)

            return tagSize + header.size
        }
    }


    @RequiresApi(Build.VERSION_CODES.Q)
    private fun getUriFromDirType(dirType: Int, externalVolumeName: String?): Uri {
        return when (dirType) {
            0 -> MediaStore.Images.Media.getContentUri(defineVolume(externalVolumeName))
            1 -> MediaStore.Audio.Media.getContentUri(defineVolume(externalVolumeName))
            2 -> MediaStore.Video.Media.getContentUri(defineVolume(externalVolumeName))
            else -> MediaStore.Downloads.getContentUri(defineVolume(externalVolumeName))
        }

    }

    private fun createOrUpdateFile(
        path: String,
        name: String,
        appFolder: String,
        dirType: Int,
        dirName: String,
        externalVolumeName: String?,
        id3v2Tags: Map<String, String>?,
        shouldAddCover: Boolean = false
    ) {
        saveId3(
            path,
            id3v2Tags,
            shouldAddCover,
        )
        // { photo, music, video, download }
        Log.d(TAG, "DirName $dirName")

        val relativePath: String = if (appFolder.trim().isEmpty()) {
            dirName
        } else {
            dirName + File.separator + appFolder
        }

        deleteFileUsingDisplayName(name, appFolder, dirType, dirName, externalVolumeName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }


            val resolver = activity!!.applicationContext.contentResolver
            val uri = resolver.insert(getUriFromDirType(dirType, externalVolumeName), values)!!

            resolver.openOutputStream(uri).use { os ->
                File(path).inputStream().use { it.copyTo(os!!) }
            }

            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            Log.d(TAG, "saveFile $name")

        }
    }


    @kotlin.jvm.Throws
    private fun deleteFileUsingDisplayName(
        displayName: String,
        appFolder: String,
        dirType: Int,
        dirName: String,
        externalVolumeName: String?,
    ): Boolean {
        val relativePath: String = if (appFolder.trim().isEmpty()) {
            dirName + File.separator
        } else {
            dirName + File.separator + appFolder + File.separator
        }
        val uri: Uri? =
            getUriFromDisplayName(displayName, appFolder, dirType, dirName, externalVolumeName)
        Log.d(TAG, "deleteFileUsingDisplayName DisplayName: $displayName URI:$uri")
        if (uri != null) {
            val resolver: ContentResolver = activity!!.applicationContext.contentResolver
            val selectionArgs =
                arrayOf(displayName, relativePath)
            resolver.delete(
                uri,
                MediaStore.Audio.Media.DISPLAY_NAME + " =?  AND " + MediaStore.Audio.Media.RELATIVE_PATH + " =? ",
                selectionArgs
            )
            Log.d("deleteFile", displayName)
            return true
        }
        return false
    }


    @kotlin.jvm.Throws
    private fun getUriFromDisplayName(
        displayName: String,
        appFolder: String,
        dirType: Int,
        dirName: String,
        externalVolumeName: String?,
    ): Uri? {

        val uri = getUriFromDirType(dirType, externalVolumeName)

        val relativePath: String = if (appFolder.trim().isEmpty()) {
            dirName + File.separator
        } else {
            dirName + File.separator + appFolder + File.separator
        }

        val projection: Array<String> = arrayOf(MediaStore.MediaColumns._ID)
        val selectionArgs =
            arrayOf(displayName, relativePath)
        val cursor: Cursor? = activity!!.applicationContext.contentResolver.query(
            uri,
            projection,
            MediaStore.Audio.Media.DISPLAY_NAME + " =?  AND " + MediaStore.Audio.Media.RELATIVE_PATH + " =? ",
            selectionArgs,
            null
        )
        Log.d(TAG, "getUriFromDisplayName: $uri")
        return cursor?.use {
            if (it.moveToFirst()) {
                val columnIndex: Int = it.getColumnIndex(projection[0])
                val fileId: Long = it.getLong(columnIndex)
                Log.d(TAG, "getUriFromDisplayName2: $uri/$fileId")
                Uri.parse("$uri/$fileId")
            } else {
                null
            }
        }

    }

    private fun uriFromFilePath(path: String, reply: OneShotResult): String? {
        try {
            MediaScannerConnection.scanFile(
                activity!!.applicationContext,
                arrayOf(File(path).absolutePath),
                null
            ) { _, uri ->
                Log.d("uriFromFilePath", uri?.toString().toString())
                reply.success(uri?.toString()?.trim())
            }

        } catch (_: Exception) {
            reply.success(null)
        }
        return null
    }

    // Music/AppFolder
    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestForAccess(initialFolderRelativePath: String?, reply: OneShotResult) {

        val startDir: String? = initialFolderRelativePath?.split("/")?.joinToString("%2F")
        startDir?.let {
            Log.d("Start Dir", it)
        }


        // Choose a directory using the system's file picker.
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            startDir?.let {
                // Optionally, specify a URI for the directory that should be opened in
                // the system file picker when it loads.
                var uriroot =
                    getParcelableExtra<Uri>("android.provider.extra.INITIAL_URI")    // get system root uri
                var scheme = uriroot.toString()
                Log.d("Debug", "INITIAL_URI scheme: $scheme")
                scheme = scheme.replace("/root/", "/document/")
                scheme += "%3A$startDir"
                uriroot = Uri.parse(scheme)
                // give changed uri to Intent
                Log.d("requestForAccess", "uri: $uriroot")
                putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    uriroot
                )
            }
        }

        val currentActivity = activity
        if (currentActivity == null) {
            reply.success("")
            return
        }

        savePendingRequest(992, PendingRequest(reply = reply))
        try {
            currentActivity.startActivityForResult(intent, 992)
        } catch (e: Exception) {
            pendingRequests.remove(992)
            Log.e(TAG, "Could not launch directory picker", e)
            reply.success("")
        }
    }

    private fun editFile(uriString: String, path: String, reply: OneShotResult) {
        this.uriString = uriString
        tempFilePath = path
        val fileUri = Uri.parse(uriString)
        try {
            val contentResolver: ContentResolver =
                activity!!.applicationContext.contentResolver
            contentResolver.openFileDescriptor(fileUri, "w")?.use {
                FileOutputStream(it.fileDescriptor).use { os ->
                    File(path).inputStream().use { it.copyTo(os) }
                }
            }
            File(path).delete()
            reply.success(true)
        } catch (e: Exception) {
            if (!launchRecoverableRequest(e, 993, reply)) {
                reply.success(false)
            }
        }
    }

    private fun deleteFileUsingUri(uriString: String, reply: OneShotResult) {
        this.uriString = uriString
        val fileUri = Uri.parse(uriString)
        val contentResolver: ContentResolver = activity!!.applicationContext.contentResolver
        try {
            DocumentsContract.deleteDocument(contentResolver, fileUri)
            reply.success(true)
        } catch (e: Exception) {
            if (!launchRecoverableRequest(e, 994, reply)) {
                reply.success(false)
            }
        }
    }

    private fun isDeletable(uriString: String): Boolean {
        val uri = Uri.parse(uriString)
        if (!DocumentsContract.isDocumentUri(activity!!.applicationContext, uri)) {
            return false
        }

        val contentResolver: ContentResolver = activity!!.applicationContext.contentResolver
        val cursor: Cursor? = contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
            null,
            null,
            null
        )

        val flags: Int = cursor?.use {
            if (cursor.moveToFirst()) {
                cursor.getInt(0)
            } else {
                0
            }
        } ?: 0

        return flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0
    }

    private fun isWritable(uriString: String): Boolean {
        val uri = Uri.parse(uriString)
        if (!DocumentsContract.isDocumentUri(activity!!.applicationContext, uri)) {
            return false
        }

        val contentResolver: ContentResolver = activity!!.applicationContext.contentResolver
        val cursor: Cursor? = contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
            null,
            null,
            null
        )

        val flags: Int = cursor?.use {
            if (cursor.moveToFirst()) {
                cursor.getInt(0)
            } else {
                0
            }
        } ?: 0

        return flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0
    }

    private fun documentId(uriString: String): Long? {
        val uri = Uri.parse(uriString)
        if (!DocumentsContract.isDocumentUri(activity!!.applicationContext, uri)) {
            return null
        }

        val contentResolver: ContentResolver = activity!!.applicationContext.contentResolver
        val cursor: Cursor? = contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null
        )

        return cursor?.use {
            if (it.moveToFirst()) {
                val columnIndex: Int = it.getColumnIndex(it.columnNames[0])
                it.getLong(columnIndex)
            } else {
                null
            }
        }
    }

    private fun readFileUsingUri(uriString: String, path: String, reply: OneShotResult) {
        this.uriString = uriString
        tempFilePath = path
        val fileUri = Uri.parse(uriString)
        try {
            val contentResolver: ContentResolver =
                activity!!.applicationContext.contentResolver
            contentResolver.openInputStream(fileUri)?.use { inputStream ->
                File(path).outputStream().use {
                    inputStream.copyTo(it)
                }
            }
            reply.success(true)
        } catch (e: Exception) {
            if (!launchRecoverableRequest(e, 995, reply)) {
                reply.success(false)
            }
        }
    }

    private fun readFile(
        path: String,
        name: String,
        appFolder: String,
        dirType: Int,
        dirName: String,
        externalVolumeName: String?,
        reply: OneShotResult,
    ) {
        this.fileName = name
        this.tempFilePath = path
        this.appFolder = appFolder
        this.dirType = dirType
        this.dirName = dirName
        this.externalVolumeName = externalVolumeName

        Log.d("DirName", dirName)
        try {
            val uri: Uri? =
                getUriFromDisplayName(name, appFolder, dirType, dirName, externalVolumeName)
            if (uri != null) {
                val contentResolver: ContentResolver =
                    activity!!.applicationContext.contentResolver
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    File(path).outputStream().use {
                        inputStream.copyTo(it)
                    }
                }
                reply.success(true)
            } else {
                reply.success(false)
            }
        } catch (e: Exception) {
            if (!launchRecoverableRequest(e, 996, reply)) {
                reply.success(false)
            }
        }
    }

    private fun isFileUriExist(uriString: String): Boolean {
        val fileUri = Uri.parse(uriString)
        return DocumentsContract.isDocumentUri(activity!!.applicationContext, fileUri)
    }

    private fun getFolderChildren(uriString: String, reply: OneShotResult) {
        try {
            val directoryUri = Uri.parse(uriString)
            val documentTreeInfo = buildDocumentTreeInfo(directoryUri, includePermissions = true)
            reply.success(documentTreeInfo.json)
        } catch (e: Exception) {
            reply.success("")
        }
    }

    private fun buildDocumentTreeInfo(
        directoryUri: Uri,
        includePermissions: Boolean,
    ): DocumentTreeInfo {
        val documentsTree = DocumentFile.fromTreeUri(activity!!.applicationContext, directoryUri)
        val children: MutableList<DocumentInfo> = mutableListOf()
        documentsTree?.listFiles()?.take(maxDocumentTreeChildren)?.forEach { childDocument ->
            Log.d("File: ", "${childDocument.name}, ${childDocument.uri}")
            val childUri = childDocument.uri.toString().trim()
            children.add(
                DocumentInfo(
                    childDocument.name,
                    childUri,
                    childDocument.isVirtual,
                    childDocument.isDirectory,
                    childDocument.type,
                    childDocument.lastModified(),
                    childDocument.length(),
                    if (includePermissions) isWritable(childUri) else null,
                    if (includePermissions) isDeletable(childUri) else null,
                )
            )
        }
        return DocumentTreeInfo(directoryUri.toString().trim(), children)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        Log.d(TAG, "onActivityResult: $resultCode, $resultCode")
        if (requestCode !in 990..996) {
            return false
        }
        val pendingRequest = takePendingRequest(requestCode)
        if (pendingRequest == null) {
            Log.w(TAG, "Ignoring stale activity result for requestCode=$requestCode")
            return true
        }
        val reply = pendingRequest.reply

        if (requestCode == 990) {
            if (resultCode == Activity.RESULT_OK) {
                runOnIo(reply) {
                    saveFile(
                        pendingRequest.tempFilePath ?: "",
                        pendingRequest.fileName ?: "",
                        pendingRequest.appFolder ?: "",
                        pendingRequest.dirType,
                        pendingRequest.dirName ?: "",
                        pendingRequest.externalVolumeName,
                        pendingRequest.id3v2Tags,
                        pendingRequest.shouldAddCover,
                        reply,
                    )
                }
            } else {
                reply.success(false)
            }
            return true
        } else if (requestCode == 991) {
            if (resultCode == Activity.RESULT_OK) {
                runOnIo(reply) {
                    deleteFile(
                        pendingRequest.fileName ?: "",
                        pendingRequest.appFolder ?: "",
                        pendingRequest.dirType,
                        pendingRequest.dirName ?: "",
                        reply,
                    )
                }
            } else {
                reply.success(false)
            }
            return true
        } else if (requestCode == 992) {
            // https://developer.android.com/training/data-storage/shared/documents-files#persist-permissions
            if (resultCode == Activity.RESULT_OK) {
                val directoryUri = data?.data
                if (directoryUri != null) {
                    val contentResolver = activity!!.applicationContext.contentResolver
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(directoryUri, takeFlags)
                    runOnIo(reply, failureValue = "") {
                        val documentTreeInfo = buildDocumentTreeInfo(directoryUri, includePermissions = false)
                        val string = documentTreeInfo.json
                        Log.d("requestForAccess: G", string)
                        reply.success(string)
                    }
                } else {
                    reply.success("")
                }
            } else {
                reply.success("")
            }
            return true
        } else if (requestCode == 993) {
            if (resultCode == Activity.RESULT_OK) {
                runOnIo(reply) {
                    editFile(
                        pendingRequest.uriString ?: "",
                        pendingRequest.tempFilePath ?: "",
                        reply,
                    )
                }
            } else {
                reply.success(false)
            }
            return true
        } else if (requestCode == 994) {
            if (resultCode == Activity.RESULT_OK) {
                runOnIo(reply) { deleteFileUsingUri(pendingRequest.uriString ?: "", reply) }
            } else {
                reply.success(false)
            }
            return true
        } else if (requestCode == 995) {
            if (resultCode == Activity.RESULT_OK) {
                runOnIo(reply) {
                    readFileUsingUri(
                        pendingRequest.uriString ?: "",
                        pendingRequest.tempFilePath ?: "",
                        reply,
                    )
                }
            } else {
                reply.success(false)
            }
            return true
        } else if (requestCode == 996) {
            if (resultCode == Activity.RESULT_OK) {
                runOnIo(reply) {
                    readFile(
                        pendingRequest.tempFilePath ?: "",
                        pendingRequest.fileName ?: "",
                        pendingRequest.appFolder ?: "",
                        pendingRequest.dirType,
                        pendingRequest.dirName ?: "",
                        pendingRequest.externalVolumeName,
                        reply,
                    )
                }
            } else {
                reply.success(false)
            }
            return true
        }
        return true
    }
}
