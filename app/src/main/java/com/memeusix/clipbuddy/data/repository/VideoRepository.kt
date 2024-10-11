package com.memeusix.clipbuddy.data.repository

import android.content.Context
import android.provider.MediaStore
import com.memeusix.clipbuddy.data.model.VideoModel

class VideoRepository(private val context: Context) {

    fun getVideosByFolder(): Map<String, List<VideoModel>> {
        val videoMap = mutableMapOf<String, MutableList<VideoModel>>()

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )

        val cursor = context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, null
        )

        cursor?.use {
            val idIndex = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameIndex = it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val folderIndex = it.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val pathIndex = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val durationIndex = it.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeIndex = it.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

            while (it.moveToNext()) {
                val id = it.getLong(idIndex)
                val name = it.getString(nameIndex)
                val folder = it.getString(folderIndex) ?: "Root"
                val path = it.getString(pathIndex)
                val duration = it.getLong(durationIndex)
                val size = it.getLong(sizeIndex)
                val video = VideoModel(id, name, folder, path, duration, size)
                videoMap.getOrPut(folder) { mutableListOf() }.add(video)
            }
        }

        return videoMap
    }
}