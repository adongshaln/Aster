package com.adong.adchat.data.story

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.adong.adchat.data.ChatImageAttachment
import com.adong.adchat.data.ChatMessage
import com.adong.adchat.data.DocumentImport
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

object StoryImages {
    fun encode(images: List<ChatImageAttachment>): String {
        require(images.size<=4)
        return JSONArray(images.map { a -> JSONObject().put("id",a.id).put("uri",a.uri).put("name",a.name)
            .put("mime",a.mimeType).put("size",a.size).put("width",a.width).put("height",a.height) }).toString()
    }
    fun decode(raw:String): List<ChatImageAttachment> = JSONArray(raw).let { array ->
        require(array.length()<=4)
        List(array.length()) { index -> val a=array.getJSONObject(index)
            ChatImageAttachment(a.getString("id"),a.getString("uri"),a.getString("name"),a.getString("mime"),a.getLong("size"),a.getInt("width"),a.getInt("height"))
        }
    }
    fun importImage(context:Context,storyId:String,uri:Uri):ChatImageAttachment {
        val bytes=context.contentResolver.openInputStream(uri)?.use { DocumentImport.boundedBytes(it,10*1024*1024) } ?: error("无法读取图片")
        val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
            if(it.moveToFirst())it.getString(0) else null
        } ?: "图片"
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0) { "无法识别图片格式" }
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>2048)sample*=2
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=sample}) ?: error("图片解码失败")
        val output=ByteArrayOutputStream()
        try { check(bitmap.compress(Bitmap.CompressFormat.JPEG,85,output)) } finally { bitmap.recycle() }
        require(output.size()<=2*1024*1024) { "图片压缩后仍超过 2 MB，请缩小后重试" }
        val directory=directory(context,storyId).apply { mkdirs() }
        val file=File(directory,"${UUID.randomUUID()}.jpg")
        file.writeBytes(output.toByteArray())
        return ChatImageAttachment(uri=Uri.fromFile(file).toString(),name=name,mimeType="image/jpeg",size=file.length(),width=bounds.outWidth/sample,height=bounds.outHeight/sample)
    }
    fun hydrate(context:Context,storyId:String,history:List<ChatMessage>):List<ChatMessage> = history.map { message ->
        message.copy(attachments=message.attachments.map { image ->
            val uri=Uri.parse(image.uri);require(uri.scheme=="file") { "图片来源无效，请重新添加" }
            val file=File(uri.path ?: error("图片路径无效")).canonicalFile
            require(file.parentFile==directory(context,storyId).canonicalFile && file.isFile) { "图片已不可用，请重新添加" }
            image.copy(bytes=file.inputStream().use { DocumentImport.boundedBytes(it,2*1024*1024) })
        })
    }
    fun directory(context:Context,storyId:String):File {
        require(storyId.matches(Regex("[a-zA-Z0-9_-]+")))
        return File(context.filesDir,"story_images/$storyId")
    }
}
