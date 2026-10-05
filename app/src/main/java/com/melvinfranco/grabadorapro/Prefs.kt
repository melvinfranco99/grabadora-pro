package com.melvinfranco.grabadorapro

import android.content.Context

/** Ajustes persistentes de la app. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    /** Duración máxima de una grabación, en minutos (por defecto 2 horas). */
    var maxMinutes: Int
        get() = sp.getInt("max_minutes", 120)
        set(v) = sp.edit().putInt("max_minutes", v.coerceIn(1, 24 * 60)).apply()

    var sampleRate: Int
        get() = sp.getInt("sample_rate", 48000)
        set(v) = sp.edit().putInt("sample_rate", v).apply()

    /** Usar el supresor de ruido del propio teléfono durante la grabación. */
    var liveNoiseSuppressor: Boolean
        get() = sp.getBoolean("live_ns", false)
        set(v) = sp.edit().putBoolean("live_ns", v).apply()

    var lastNoise: Int
        get() = sp.getInt("last_noise", 50)
        set(v) = sp.edit().putInt("last_noise", v).apply()

    var lastVoice: Int
        get() = sp.getInt("last_voice", 50)
        set(v) = sp.edit().putInt("last_voice", v).apply()
}
