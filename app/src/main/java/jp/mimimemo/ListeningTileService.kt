package jp.mimimemo

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * クイック設定の「ミミメモ」タイル（issue #85）。聞き取り中は点灯する。
 * 押すと、聞き取り中なら止め、止まっていれば聞き取り画面を開いて始める。
 * Android 14 以降、マイクの前面サービスは画面（前面）から始める必要があるため、タイルから直接は始めない。
 */
class ListeningTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        ListeningHub.attach(this)
        render()
    }

    override fun onClick() {
        super.onClick()
        if (ListeningHub.isRunning) {
            ListeningService.stop(this)
            render(running = false)
        } else {
            openListening()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openListening() {
        val intent = Intent(this, ListeningActivity::class.java)
            .putExtra(ListeningActivity.EXTRA_START, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(running: Boolean = ListeningHub.isRunning) {
        val tile = qsTile ?: return
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (running) R.string.status_listening else R.string.status_stopped)
        }
        tile.updateTile()
    }

    companion object {
        /** 聞き取りが始まった・止まったときに呼ぶ。タイルの表示を合わせてもらう */
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, ListeningTileService::class.java)) }
        }
    }
}
