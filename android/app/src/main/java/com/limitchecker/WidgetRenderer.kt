package com.limitchecker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.util.TypedValue
import android.widget.RemoteViews

/**
 * 取得結果をウィジェットに反映する。
 *
 * ウィジェット全体を1枚の Bitmap として描き、ImageView に流す（docs/design.md）。
 */
object WidgetRenderer {

    fun updateAll(context: Context, result: HubClient.Result) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val component = ComponentName(context, LimitWidgetProvider::class.java)
        val ids = manager.getAppWidgetIds(component)
        for (id in ids) {
            update(context, manager, id, result)
        }
    }

    fun update(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        result: HubClient.Result,
    ) {
        val (widthPx, heightPx) = sizeOf(context, manager, widgetId)
        val widthDp = (widthPx / context.resources.displayMetrics.density).toInt()
        val night = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val bitmap = DonutRenderer.render(
            widthPx = widthPx,
            heightPx = heightPx,
            result = result,
            nowEpoch = System.currentTimeMillis() / 1000,
            night = night,
            widthDp = widthDp,
            background = Prefs.widgetBackground(context),
        )

        val views = RemoteViews(context.packageName, R.layout.widget_limit)
        views.setImageViewBitmap(R.id.widget_image, bitmap)
        views.setOnClickPendingIntent(R.id.widget_image, tapIntent(context, result))
        manager.updateAppWidget(widgetId, views)
    }

    /**
     * ドーナツ部分をタップしたら即時更新（docs/design.md）。
     * ただし未設定のうちは設定画面を開く。
     */
    private fun tapIntent(context: Context, result: HubClient.Result): PendingIntent {
        return if (result is HubClient.Result.NotConfigured) {
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } else {
            PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, LimitWidgetProvider::class.java)
                    .setAction(LimitWidgetProvider.ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }

    /** ウィジェットの実寸。オプションは dp で渡ってくるので px に直す。 */
    private fun sizeOf(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
    ): Pair<Int, Int> {
        val options = manager.getAppWidgetOptions(widgetId)
        val portrait = (context.resources.configuration.orientation ==
            Configuration.ORIENTATION_PORTRAIT)

        val widthDp = if (portrait) {
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        } else {
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
        }
        val heightDp = if (portrait) {
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        } else {
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        }

        val metrics = context.resources.displayMetrics
        fun toPx(dp: Int, fallbackDp: Int): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            (if (dp > 0) dp else fallbackDp).toFloat(),
            metrics,
        ).toInt()

        return Pair(toPx(widthDp, 250), toPx(heightDp, 110))
    }
}
