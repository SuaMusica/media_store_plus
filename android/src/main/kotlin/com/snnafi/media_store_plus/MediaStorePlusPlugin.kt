package com.snnafi.media_store_plus

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
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
        private lateinit var result: OneShotResult
        private val pendingResults = mutableMapOf<Int, OneShotResult>()
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

        private fun savePendingResult(requestCode: Int) {
            pendingResults.remove(requestCode)?.success(false)
            pendingResults[requestCode] = result
        }

        private fun restorePendingResult(requestCode: Int): Boolean {
            val pendingResult = pendingResults.remove(requestCode) ?: return false
            result = pendingResult
            return true
        }

        private fun finishPendingResults(value: Any?) {
            pendingResults.values.forEach { it.success(value) }
            pendingResults.clear()
        }


    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "media_store_plus")
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        this.result = OneShotResult(result)
        Log.d(TAG, "call.method: ${call.method}")
        if (call.method == "getPlatformSDKInt") {
            result.success(Build.VERSION.SDK_INT)
        } else if (call.method == "saveFile") {
            saveFile(
                Uri.parse(call.argument("tempFilePath")!!).path!!,
                call.argument("fileName")!!,
                call.argument("appFolder")!!,
                call.argument("dirType")!!,
                call.argument("dirName")!!,
                call.argument("externalVolumeName"),
                call.argument("id3v2Tags"),
                call.argument("shouldAddCover")!!,
            )
                } else if (call.method == "deleteFile") {
            deleteFile(
                call.argument("fileName")!!,
                call.argument("appFolder")!!,
                call.argument("dirType")!!,
                call.argument("dirName")!!
            )
        } else if (call.method == "getFileUri") {
            val uri: Uri? = getUriFromDisplayName(
                call.argument("fileName")!!,
                call.argument("appFolder")!!,
                call.argument("dirType")!!,
                call.argument("dirName")!!,
                call.argument("externalVolumeName"),
            )
            if (uri != null) {
                result.success(uri.toString().trim())
            } else {
                result.success(null)
            }
        } else if (call.method == "getUriFromFilePath") {
            uriFromFilePath(Uri.parse(call.argument("filePath")!!).path!!)
        } else if (call.method == "requestForAccess") {
            requestForAccess(Uri.parse(call.argument("initialRelativePath")!!).path!!)
        } else if (call.method == "editFile") {
            editFile(
                call.argument("contentUri")!!,
                Uri.parse(call.argument("tempFilePath")!!).path!!,
            )
        } else if (call.method == "deleteFileUsingUri") {
            deleteFileUsingUri(
                call.argument("contentUri")!!,
            )
        } else if (call.method == "isFileDeletable") {
            result.success(
                isDeletable(
                    call.argument("contentUri")!!,
                )
            )
        } else if (call.method == "isFileWritable") {
            result.success(
                isWritable(
                    call.argument("contentUri")!!,
                )
            )
        } else if (call.method == "readFile") {
            readFile(
                Uri.parse(call.argument("tempFilePath")!!).path!!,
                call.argument("fileName")!!,
                call.argument("appFolder")!!,
                call.argument("dirType")!!,
                call.argument("dirName")!!,
                call.argument("externalVolumeName")
            )
        } else if (call.method == "readFileUsingUri") {
            readFileUsingUri(
                call.argument("contentUri")!!,
                Uri.parse(call.argument("tempFilePath")!!).path!!,
            )
        } else if (call.method == "isFileUriExist") {
            result.success(
                isFileUriExist(
                    call.argument("contentUri")!!,
                )
            )
        } else if (call.method == "getDocumentTree") {
            getFolderChildren(
                call.argument("contentUri")!!,
            )
        } else {
            result.notImplemented()
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
        shouldAddCover: Boolean = false,
    ) {
        this.fileName = name
        this.tempFilePath = path
        this.appFolder = appFolder
        this.dirType = dirType
        this.dirName = dirName
        this.externalVolumeName = externalVolumeName
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
            result.success(true)

        } catch (e: Exception) {
            if (e is RecoverableSecurityException) {
                val recoverableSecurityException = e as? RecoverableSecurityException
                recoverableSecurityException?.let {
                    val intentSender =
                        recoverableSecurityException.userAction.actionIntent.intentSender
                    intentSender.let {
                        savePendingResult(990)
                        activity!!.startIntentSenderForResult(
                            intentSender, 990, null, 0, 0, 0, null
                        )
                    }
                }
            }
            Log.e("Exception", e.message, e)
        }
    }

    private fun deleteFile(
        name: String,
        appFolder: String,
        dirType: Int,
        dirName: String
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
            result.success(status)
        } catch (e: Exception) {
            if (e is RecoverableSecurityException) {
                val recoverableSecurityException = e as? RecoverableSecurityException
                recoverableSecurityException?.let {
                    val intentSender =
                        recoverableSecurityException.userAction.actionIntent.intentSender
                    intentSender.let {
                        savePendingResult(991)
                        activity!!.startIntentSenderForResult(
                            intentSender, 991, null, 0, 0, 0, null
                        )
                    }
                }
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
        }
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

    private fun uriFromFilePath(path: String): String? {
        val reply = result
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
    private fun requestForAccess(initialFolderRelativePath: String?) {

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

        savePendingResult(992)
        activity!!.startActivityForResult(intent, 992)
    }

    private fun editFile(uriString: String, path: String) {
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
            result.success(true)
        } catch (e: Exception) {
            if (e is RecoverableSecurityException) {
                val recoverableSecurityException = e as? RecoverableSecurityException
                recoverableSecurityException?.let {
                    val intentSender =
                        recoverableSecurityException.userAction.actionIntent.intentSender
                    intentSender.let {
                        savePendingResult(993)
                        activity!!.startIntentSenderForResult(
                            intentSender, 993, null, 0, 0, 0, null
                        )
                    }
                }
            }
        }
    }

    private fun deleteFileUsingUri(uriString: String) {
        val fileUri = Uri.parse(uriString)
        val contentResolver: ContentResolver = activity!!.applicationContext.contentResolver
        try {
            DocumentsContract.deleteDocument(contentResolver, fileUri)
            result.success(true)
        } catch (e: Exception) {
            if (e is RecoverableSecurityException) {
                val recoverableSecurityException = e as? RecoverableSecurityException
                recoverableSecurityException?.let {
                    val intentSender =
                        recoverableSecurityException.userAction.actionIntent.intentSender
                    intentSender.let {
                        savePendingResult(994)
                        activity!!.startIntentSenderForResult(
                            intentSender, 994, null, 0, 0, 0, null
                        )
                    }
                }
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
        }

        return cursor?.use {
            if (it.moveToFirst()) {
                val columnIndex: Int = it.getColumnIndex(it.columnNames[0])
                it.getLong(columnIndex)
            } else {
                null
            }
        }
    }

    private fun readFileUsingUri(uriString: String, path: String) {
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
            result.success(true)
        } catch (e: Exception) {
            if (e is RecoverableSecurityException) {
                val recoverableSecurityException = e as? RecoverableSecurityException
                recoverableSecurityException?.let {
                    val intentSender =
                        recoverableSecurityException.userAction.actionIntent.intentSender
                    intentSender.let {
                        savePendingResult(995)
                        activity!!.startIntentSenderForResult(
                            intentSender, 995, null, 0, 0, 0, null
                        )
                    }
                }
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
    ) {
        this.fileName = name
        this.tempFilePath = path
        this.appFolder = appFolder
        this.dirType = dirType
        this.dirName = dirName

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
                result.success(true)
            } else {
                result.success(false)
            }
        } catch (e: Exception) {
            if (e is RecoverableSecurityException) {
                val recoverableSecurityException = e as? RecoverableSecurityException
                recoverableSecurityException?.let {
                    val intentSender =
                        recoverableSecurityException.userAction.actionIntent.intentSender
                    intentSender.let {
                        savePendingResult(996)
                        activity!!.startIntentSenderForResult(
                            intentSender, 996, null, 0, 0, 0, null
                        )
                    }
                }
            }
        }
    }

    private fun isFileUriExist(uriString: String): Boolean {
        val fileUri = Uri.parse(uriString)
        return DocumentsContract.isDocumentUri(activity!!.applicationContext, fileUri)
    }

    private fun getFolderChildren(uriString: String) {
        try {
            val directoryUri = Uri.parse(uriString)
            val documentTreeInfo = buildDocumentTreeInfo(directoryUri, includePermissions = true)
            result.success(documentTreeInfo.json)
        } catch (e: Exception) {
            result.success("")
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
        if (!restorePendingResult(requestCode)) {
            Log.w(TAG, "Ignoring stale activity result for requestCode=$requestCode")
            return true
        }

        if (requestCode == 990) {
            if (resultCode == Activity.RESULT_OK) {
                saveFile(
                    "",
                    fileName,
                    appFolder,
                    dirType,
                    dirName,
                    externalVolumeName,
                    id3v2Tags,
                    shouldAddCover,
                )
            } else {
                result.success(false)
            }
            return true
        } else if (requestCode == 991) {
            if (resultCode == Activity.RESULT_OK) {
                deleteFile(
                    fileName,
                    appFolder,
                    dirType,
                    dirName
                )
            } else {
                result.success(false)
            }
            return true
        } else if (requestCode == 992) {
            // https://developer.android.com/training/data-storage/shared/documents-files#persist-permissions
            if (resultCode == Activity.RESULT_OK) {
                var documentTreeInfo: DocumentTreeInfo? = null
                val uriList: MutableList<String> = mutableListOf()
                data?.data?.also { directoryUri ->
                    Log.d(TAG, "requestForAccess: G: $directoryUri")

                    uriList.add(directoryUri.toString().trim())


                    val contentResolver = activity!!.applicationContext.contentResolver
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(directoryUri, takeFlags)

                    documentTreeInfo = buildDocumentTreeInfo(directoryUri, includePermissions = false)

                }
                val string = documentTreeInfo?.json ?: ""
                Log.d("requestForAccess: G", string)
                result.success(string)
            } else {
                result.success("")
            }
            return true
        } else if (requestCode == 993) {
            if (resultCode == Activity.RESULT_OK) {
                editFile(uriString, tempFilePath)
            } else {
                result.success(false)
            }
            return true
        } else if (requestCode == 994) {
            if (resultCode == Activity.RESULT_OK) {
                deleteFileUsingUri(uriString)
            } else {
                result.success(false)
            }
            return true
        } else if (requestCode == 995) {
            if (resultCode == Activity.RESULT_OK) {
                readFileUsingUri(uriString, tempFilePath)
            } else {
                result.success(false)
            }
            return true
        } else if (requestCode == 996) {
            if (resultCode == Activity.RESULT_OK) {
                readFile(
                    tempFilePath,
                    fileName,
                    appFolder,
                    dirType,
                    dirName,
                    externalVolumeName,
                )
            } else {
                result.success(false)
            }
            return true
        }
        return true
    }
}
