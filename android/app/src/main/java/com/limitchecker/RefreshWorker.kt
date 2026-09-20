package com.limitchecker

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * hub から残量を取り、ウィジェットを描き直す。
 *
 * ネットワークをメインスレッドで触れないため、取得も描画もここで行う。
 *
 * 省電力方針（docs/decisions.md D10, D11）:
 *   - 定期実行は 15 分（WorkManager の下限）
 *   - 圏外では走らせない（NetworkType.CONNECTED 制約）
 *   - 画面 OFF 中は取得をやめる。見えていないものを更新する意味がない
 *   - 失敗時は指数バックオフ
 */
class RefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val context = applicationContext

        // タップなど明示的な要求でなければ、画面 OFF 中は何もしない。
        if (!inputData.getBoolean(KEY_FORCE, false) && !isScreenOn(context)) {
            return Result.success()
        }

        val result = HubClient.fetch(context)
        WidgetRenderer.updateAll(context, result)
        StatusNotification.update(context, result)

        return when (result) {
            is HubClient.Result.Failed -> Result.retry()
            else -> Result.success()
        }
    }

    private fun isScreenOn(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true

    companion object {
        private const val PERIODIC_NAME = "limitchecker-periodic"
        private const val KEY_FORCE = "force"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * ウィジェットか常設通知のどちらかが有効なら定期実行し、
         * 両方なくなったら止める。表示するものがないのに動かさない。
         */
        fun syncSchedule(context: Context) {
            if (hasWidgets(context) || Prefs.notificationEnabled(context)) {
                schedulePeriodic(context)
            } else {
                cancelPeriodic(context)
            }
        }

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
        }

        /** 即時更新。force を立てると画面 OFF でも取りに行く（タップ直後など）。 */
        fun refreshNow(context: Context, force: Boolean) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(constraints)
                .setInputData(androidx.work.Data.Builder().putBoolean(KEY_FORCE, force).build())
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }

        fun hasWidgets(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context) ?: return false
            val component = ComponentName(context, LimitWidgetProvider::class.java)
            return manager.getAppWidgetIds(component).isNotEmpty()
        }
    }
}
