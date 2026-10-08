# AGENTS.md

Guía para agentes que trabajen en este repositorio. Léela antes de cambios significativos.

## Qué es

Whspr: entrada de voz Android para **dictado 100% local**. Sin nube. Descarga un modelo Whisper al
almacenamiento propio de la app y transcribe en el dispositivo con `whisper.cpp` vía JNI.

## Principio rector

**Simplicidad antes que features.** No sobre-ingeniería, no abstracciones innecesarias.

- Sin Jetpack Compose.
- Sin dependencias AndroidX.
- Sin librerías de red/HTTP (la descarga usa `DownloadManager` del sistema).
- Sin telemetría ni analítica.
- Sin pantallas ni ajustes nuevos salvo que sean imprescindibles.

No añadas dependencias sin aprobación explícita.

## Stack

- Kotlin (plugin Kotlin **integrado en AGP 9** — NO añadir `org.jetbrains.kotlin.android`).
- Android Gradle Plugin 9, `compileSdk`/`targetSdk` 36, `minSdk` 28.
- NDK `28.2.13676358` + CMake.
- `whisper.cpp` vendorizado en `third_party/whisper.cpp`, recortado a **`arm64-v8a` CPU-only**.
- Package: `dev.jorgex.whspr`.

## Estructura

```
app/src/main/java/dev/jorgex/whspr/   # Kotlin
  MainActivity.kt                      # puesta en marcha por pasos, modelo, idioma, campo de prueba
  SettingsActivity.kt                  # ajustes del teclado y "Acerca de" (licencias)
  AppSettings.kt                       # ajustes persistidos (modelo, idiomas, punto, fila numérica)
  ModelCatalog.kt / ModelStore.kt      # catálogo; descarga, instalación en interno y validación SHA-256
  Languages.kt                         # idiomas de dictado de Whisper
  KeyboardLayout.kt                    # modelo declarativo de teclado (layouts, capas, Key data)
  KeyboardView.kt                      # grid de teclas y manejo táctil completo (rollover, long-press, repeat)
  DictationView.kt                     # panel de dictado: estado + Cancelar + onda
  VoiceWaveView.kt                     # visualizador de barras para RECORDING/TRANSCRIBING
  AudioRecorder.kt                     # captura 16 kHz mono PCM16 en memoria + SilenceDetector
  LocalTranscriber.kt                  # flujo único: valida el modelo, transcribe (JNI), filtra; cancelación
  WhsprInputMethodService.kt           # IME: máquina de estados KEYBOARD/RECORDING/TRANSCRIBING
  WhsprRecognitionService.kt           # proveedor de voz Android (RecognitionService)
  Theme.kt / Ui.kt                     # paleta monocroma y helpers de vistas
app/src/main/cpp/native_whisper.cpp    # JNI: caché del contexto whisper, cancelación por token
app/src/main/res/xml/                  # input_method, recognition_service, data_extraction_rules
app/src/main/res/raw/                  # licencias de terceros que se muestran en la app
scripts/                               # build/install/verify (Python, Linux)
third_party/whisper.cpp/               # vendor (MIT) — ver THIRD_PARTY_NOTICES.md
```

## Build / verificación

Entorno canónico: **Fedora/Linux + Python 3 estándar**. Hace falta un JDK 17+ (no basta un JRE).

| Acción | Comando |
| --- | --- |
| Comprobar entorno Android | `scripts/check-android-env.py` |
| Crear `local.properties` (SDK) | `scripts/write-local-properties.py` |
| Checks estáticos (lint de facto) | `scripts/check-static.py` |
| Tests de scripts | `python3 -m unittest discover -s scripts/tests -v` |
| Build release (corre static + lint + verify-apk) | `scripts/build-release.py` |
| Build debug | `scripts/build-debug.py` |
| Verificar APK ya generada | `scripts/verify-apk.py` |
| Instalar release + verificar dispositivo | `scripts/install-release.py` |
| Verificar dispositivo/registro Android | `scripts/verify-device.py` |
| Validar catálogo remoto (usa red) | `scripts/verify-model-catalog.py` |
| Gradle directo | `./gradlew :app:lintRelease :app:assembleRelease` |

Los scripts usan `unittest` de la biblioteca estándar. La verificación automatizada es
**`check-static.py`** (checks regex baratos y sin dependencias), **Android lint** (los avisos son errores) y
**`verify-apk.py`**; CI ejecuta las tres en cada PR. La verificación funcional es manual: ver `TEST_PLAN.md`
(dispositivo arm64, o emulador x86_64 con traducción arm64 como se explica allí). Ejecuta
`scripts/check-static.py` después de cualquier cambio y `scripts/build-release.py` antes de proponerlo.

Si añades un invariante crítico, protégelo con un check regex barato en `check-static.py`; no construyas
un analizador frágil ni dependencias nuevas.

## Invariantes que NO romper

- **Contrato de audio**: muestras 16 kHz mono PCM16 en memoria (`ShortArray`) entre `AudioRecorder` y
  `native_whisper.cpp`. El audio nunca se escribe en disco.
- **arm64-v8a + CPU-only**: nada de otras ABIs ni backends GPU.
- **Release minificada**, firmada con `keystore.properties` si existe o con la debug key local si no; sin
  modelos embebidos; APK < 4 MB.
- **Sin red directa**: solo `DownloadManager` para el modelo; cero clientes HTTP/telemetría.
- **Validación SHA-256** del modelo antes de transcribir; rechazar modelos parciales/corruptos. Whisper
  solo carga el modelo desde almacenamiento interno (`filesDir/models`); la descarga externa es una zona
  de paso que otras apps pueden modificar en Android 9 y 10.
- **Sin descargas a petición de terceros**: el `RecognitionService` nunca llama a `ModelStore.download`.
- **Idioma `auto`**: se pasa `language = "auto"`; `detect_language` hace que whisper termine sin texto.
- **El silencio no llega a Whisper**: sin voz detectada, `AudioRecorder.stop()` devuelve null. Whisper
  inventa frases con silencio.
- **No recortar `audio_ctx`**: medido con el modelo tiny, el WER pasa del 12 % al 86 %.
- **Guards de sesión**: no pegar texto si cambia el foco; un dictado abandonado (cambio de campo, cancelar,
  teclado oculto) suelta el micro y aborta la transcripción; no dictar en campos de contraseña.
- **Atribución de micrófono** correcta (contexto) y liberar el micro al devolver resultado.
- **`<queries>` del manifiesto**: sin visibilidad del paquete cliente, Android deniega su permiso de micro
  y el `RecognitionService` responde siempre `ERROR_INSUFFICIENT_PERMISSIONS`.
- **Resultados del `RecognitionService`** en `SpeechRecognizer.RESULTS_RECOGNITION`.
- **El toque del teclado se resuelve en `KeyboardView`** (`onInterceptTouchEvent`), no con listeners por tecla.
- No dejar marcadores TODO/stale ni referencias viejas del vendor: `scripts/check-static.py` lo verifica
  (ojo: el marcador no distingue mayúsculas, evita la palabra "todo" suelta en comentarios).

## Diseño

UI estética terminal/ASCII, **monocroma** (blancos y grises, claro/oscuro automático).
Tokens de color en `Theme.kt` (`WhsprColors`) — no hardcodear hex en las vistas. Detalles en `DESIGN.md`.

## Idioma

- Documentación y UI en **español (España)**. Idioma de dictado por defecto `es-ES`, con modo `auto`.
- Identificadores y código en su forma original.

## Git

- Commits pequeños y coherentes por tarea.
- No firmas de IA ni `Co-Authored-By`.
- Cambios de comportamiento van por rama + PR; triviales (docs/typos) pueden ir directos.
