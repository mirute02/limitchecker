package com.limitchecker

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * ホーム画面のウィジェット。
 *
 * 取得はすべて RefreshWorker に任せる。BroadcastReceiver のコールバックは
 * メインスレッドで短時間しか走れないため、ここではネットワークに触れない。
 */
class LimitWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        RefreshWorker.syncSchedule(context)
    }

    override fun onDisabled(context: Context) {
        // 常設通知だけ残っている場合もあるので、一律に止めない
        RefreshWorker.syncSchedule(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // 取得を待たずに、まず今ある状態で描いておく（空白の時間を作らない）
        RefreshWorker.refreshNow(context, force = false)
    }

    /** リサイズされたら描き直す。取得はしない。 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        RefreshWorker.refreshNow(context, force = false)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            // タップされたときは画面が点いているので必ず取りに行く
            RefreshWorker.refreshNow(context, force = true)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.limitchecker.action.REFRESH"
    }
}
