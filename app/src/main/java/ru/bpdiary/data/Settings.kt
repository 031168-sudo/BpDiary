package ru.bpdiary.data

import android.content.Context
import ru.bpdiary.analysis.BpNorms

/** Настройки приложения (SharedPreferences). */
object AppSettings {
    private const val PREFS = "settings"
    private const val KEY_SYS = "target_sys"
    private const val KEY_DIA = "target_dia"

    data class Target(val sys: Int, val dia: Int)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun target(ctx: Context) = Target(
        prefs(ctx).getInt(KEY_SYS, BpNorms.DEFAULT_TARGET_SYS),
        prefs(ctx).getInt(KEY_DIA, BpNorms.DEFAULT_TARGET_DIA),
    )

    fun setTarget(ctx: Context, t: Target) {
        prefs(ctx).edit().putInt(KEY_SYS, t.sys).putInt(KEY_DIA, t.dia).apply()
        apply(t)
    }

    /** Применить цель к расчётам анализа. */
    fun apply(t: Target) {
        BpNorms.targetSys = t.sys
        BpNorms.targetDia = t.dia
    }
}
