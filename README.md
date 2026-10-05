# Grabadora Pro

Grabadora de audio para Android con postproducción: **reducción de ruido** y **mejora de voz** ajustables de 0 a 100.

**Descarga:** https://melvinfranco99.github.io/grabadora-pro/

## Características

- 🎙️ Grabación **WAV sin pérdidas** (48 kHz o 44,1 kHz, 16 bits, mono) con pausa/reanudación.
- 📱 Sigue grabando en segundo plano o con la pantalla apagada (servicio en primer plano con notificación y botones *Pausa* / *Detener*).
- ⏱️ **Límite de 2 horas por grabación**, configurable en *Ajustes* (1 min – 23 h 59 min). Al alcanzarlo, se detiene y guarda sola.
- 📂 Lista de **las grabaciones hechas con la app** (carpeta privada de la app): reproducir, renombrar, compartir, guardar en *Música/GrabadoraPro* y borrar.
- 🔇 **Reducción de ruido 0–100**: supresión espectral STFT (FFT de 2048 puntos, solape 75 %) con perfil de ruido estimado automáticamente a partir de los tramos más silenciosos, ganancia de Wiener con estimador *decision-directed* (Ephraim–Malah), seguimiento adaptativo del ruido y suavizado tiempo/frecuencia contra el "ruido musical".
- 🗣️ **Mejora de voz 0–100**: paso-alto anti-rumble, recorte de graves embarrados (250 Hz), realce de presencia (3 kHz), brillo (8 kHz), compresor, limitador suave y normalización a −1 dBFS.
- 👂 **Previsualización de 20 s** y comparación A/B *Original / Procesado* antes de aplicar. El original nunca se modifica: se crea una copia «(mejorada)».
- 🛟 Las cabeceras WAV se actualizan cada 10 s y se reparan al abrir la app, así que una grabación interrumpida no se pierde.

Todo el procesado es local (sin Internet). No usa un modelo de IA: son algoritmos clásicos de procesamiento de señal, los mismos en los que se basan herramientas como Audacity o `noisereduce`.

## Compilación

Requiere Android SDK (`android-35`) y JDK 17.

```bash
./gradlew :app:testDebugUnitTest   # tests del motor de audio
./gradlew :app:assembleRelease     # APK en app/build/outputs/apk/release/
```

La firma usa `keystore.properties` + `.jks` (no incluidos en el repo); sin ellos se firma con la clave de depuración.
