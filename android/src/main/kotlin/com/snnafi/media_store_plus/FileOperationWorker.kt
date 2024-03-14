package com.snnafi.media_store_plus

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mpatric.mp3agic.ID3v24Tag
import com.mpatric.mp3agic.Mp3File
import kotlinx.coroutines.delay
import java.io.File

class FileOperationWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        try {
            // Example of file operation, replace with actual work
            val path = inputData.getString("path") ?: return Result.failure()
            val name = inputData.getString("name") ?: return Result.failure()
            val appFolder = inputData.getString("appFolder") ?: return Result.failure()
            val dirType = inputData.getInt("dirType",0) ?: return Result.failure()
            val dirName = inputData.getString("dirName") ?: return Result.failure()
            val externalVolumeName = inputData.getString("externalVolumeName")
            val id3v2TagsJson = inputData.getString("id3v2Tags")

            val gson = Gson()
            val type = object : TypeToken<Map<String, String>>() {}.type
            val id3v2Tags: Map<String, String> = gson.fromJson(id3v2TagsJson, type)

            Log.d(TAG, "Processing file $name")

            saveId3(path, id3v2Tags)
            val relativePath: String = if (appFolder.trim().isEmpty()) {
                dirName
            } else {
                dirName + File.separator + appFolder
            }

            deleteFileUsingDisplayName(
                applicationContext,
                name,
                appFolder,
                dirType,
                dirName,
                externalVolumeName
            )
            Log.d(TAG, "file $name after delete")


            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }


                val resolver = applicationContext.contentResolver
                val uri = resolver.insert(
                    getUriFromDirType(applicationContext,dirType, externalVolumeName),
                    values
                )!!

                resolver.openOutputStream(uri).use { os ->
                    File(path).inputStream().use { it.copyTo(os!!) }
                }

                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)

                Log.d(TAG, "saveFile $name")

            }

            Log.d(TAG, "File $name processed")
            if (File(path).exists()) {
                File(path).delete()
            }
            // Successfully finished
            return Result.success()
        } catch (e: Exception) {
            // Handle failure
            Log.e(TAG, "Error processing file", e)
            return Result.failure()
        }
    }

    companion object {
        const val TAG = "FileOperationWorker"
    }

    private fun getUriFromDisplayName(
        ctx: Context,

        displayName: String,
        appFolder: String,
        dirType: Int,
        dirName: String,
        externalVolumeName: String?,
    ): Uri? {

        val uri = getUriFromDirType(ctx,dirType, externalVolumeName)

        val relativePath: String = if (appFolder.trim().isEmpty()) {
            dirName + File.separator
        } else {
            dirName + File.separator + appFolder + File.separator
        }

        val projection: Array<String> = arrayOf(MediaStore.MediaColumns._ID)
        val selectionArgs =
            arrayOf(displayName, relativePath)
        val cursor: Cursor = ctx.contentResolver.query(
            uri,
            projection,
            MediaStore.Audio.Media.DISPLAY_NAME + " =?  AND " + MediaStore.Audio.Media.RELATIVE_PATH + " =? ",
            selectionArgs,
            null
        )!!
        cursor.moveToFirst()
        Log.d(TAG, "getUriFromDisplayName: $uri")
        return if (cursor.count > 0) {
            val columnIndex: Int = cursor.getColumnIndex(projection[0])
            val fileId: Long = cursor.getLong(columnIndex)
            cursor.close()
            Log.d(TAG, "getUriFromDisplayName2: $uri/$fileId")
            Uri.parse("$uri/$fileId")
        } else {
            null
        }

    }
    private fun deleteFileUsingDisplayName(
        ctx: Context,
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
            getUriFromDisplayName(ctx,displayName, appFolder, dirType, dirName, externalVolumeName)
        Log.d(TAG, "deleteFileUsingDisplayName DisplayName: $displayName URI:$uri")
        if (uri != null) {
            val selectionArgs =
                arrayOf(displayName, relativePath)
            ctx.contentResolver.delete(
                uri,
                MediaStore.Audio.Media.DISPLAY_NAME + " =?  AND " + MediaStore.Audio.Media.RELATIVE_PATH + " =? ",
                selectionArgs
            )
            Log.d("deleteFile", displayName)
            return true
        }
        return false
    }
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun defineVolume(ctx: Context, externalVolumeName: String?): String {
        return if (externalVolumeName != null) {
            MediaStore.getExternalVolumeNames(ctx)
                .find { it.lowercase() == externalVolumeName.lowercase() }
                ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
        } else {
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        }
    }

    private fun getUriFromDirType(ctx: Context,dirType: Int, externalVolumeName: String?): Uri {
        return when (dirType) {
            0 -> MediaStore.Images.Media.getContentUri(defineVolume(ctx,externalVolumeName))
            1 -> MediaStore.Audio.Media.getContentUri(defineVolume(ctx,externalVolumeName))
            2 -> MediaStore.Video.Media.getContentUri(defineVolume(ctx,externalVolumeName))
            else -> MediaStore.Downloads.getContentUri(defineVolume(ctx,externalVolumeName))
        }

    }
    private fun saveId3(file: String, id3v2Tags: Map<String, String>?) {

        if (id3v2Tags != null) {
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
                mp3File.id3v2Tag = id3v24Tag
                val newFilename = "$file.tmp"
                mp3File.save(newFilename)

                val from = File(newFilename)
                from.renameTo(File(file))

                Log.i(TAG, "Successfully set ID3v2 tags")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set ID3v2 tags", e)
            }
        }
    }
}
