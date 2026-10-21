package vip.ringling.recon

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import vip.ringling.recon.domain.WidgetRisk

/**
 * Home-screen risk gauge. It shows ONE number (the highest latest risk across scanned targets) and a band word, nothing else: no names, values or
 * findings ever reach the launcher. The number is the only thing stored outside the encrypted database ([publish]).
 */
class RiskWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = ids.forEach { manager.updateAppWidget(it, views(context)) }

    companion object {
        private const val PREFS = "recon_widget"; private const val KEY = "risk"

        fun publish(context: Context, risk: Int?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply { if (risk == null) remove(KEY) else putInt(KEY, risk) }.apply()
            val m = AppWidgetManager.getInstance(context)
            m.getAppWidgetIds(ComponentName(context, RiskWidget::class.java)).forEach { m.updateAppWidget(it, views(context)) }
        }

        private fun views(context: Context): RemoteViews {
            val risk = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).takeIf { it.contains(KEY) }?.getInt(KEY, 0)
            val color = when { risk == null -> 0xFF8E978B.toInt(); risk >= 50 -> 0xFFFF5B3A.toInt(); risk >= 14 -> 0xFF5BC0FF.toInt(); else -> 0xFFD2FF3F.toInt() }
            val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return RemoteViews(context.packageName, R.layout.widget_risk).apply {
                setTextViewText(R.id.risk_value, risk?.toString() ?: "–")
                setTextColor(R.id.risk_value, color)
                setTextViewText(R.id.risk_label, WidgetRisk.label(risk))
                setTextColor(R.id.risk_label, color)
                setOnClickPendingIntent(R.id.risk_root, open)
            }
        }
    }
}
