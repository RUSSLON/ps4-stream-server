package com.ps4stream.mobile

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object VlcLauncher {
    /** Use Android public VIEW contract. VLC is preferred, other apps are fallback. */
    fun open(activity:Activity, url:String) {
        val uri=Uri.parse(url)
        if (uri.scheme != "https") {
            Toast.makeText(activity,"Видео должно иметь HTTPS-ссылку",Toast.LENGTH_LONG).show()
            return
        }
        val type=if (uri.path?.endsWith(".m3u8",true)==true) "application/vnd.apple.mpegurl" else "video/*"
        val intent=Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri,type)
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(Intent(intent).setPackage("org.videolan.vlc"))
        } catch (_: ActivityNotFoundException) {
            try {activity.startActivity(Intent.createChooser(intent,"Открыть видео в плеере"))}
            catch (_: ActivityNotFoundException) {
                Toast.makeText(activity,"Установи VLC или другой видеоплеер",Toast.LENGTH_LONG).show()
            }
        } catch (_: SecurityException) {
            Toast.makeText(activity,"Не удалось запустить видеоплеер",Toast.LENGTH_LONG).show()
        }
    }
}
