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
  MainActivity.kt                      # UI mínima (sin Compose, Views a mano)
  AppSettings.kt                       # modelo + idioma de layout + lado del punto + idioma de dictado persistidos
  ModelCatalog.kt / ModelStore.kt      # catálogo y descarga/validación SHA-256 del modelo
  KeyboardLayout.kt                    # modelo declarativo de teclado (layouts, capas, Key data)
  KeyboardView.kt                      # grid de teclas (FrameLayout+TextView/ImageView), shift/capas/long-press/repeat de borrado
  VoiceWaveView.kt                     # visualizador de barras para RECORDING/TRANSCRIBING
  AudioRecorder.kt                     # captura WAV 16 kHz mono PCM16, callback onLevel (RMS)
  LocalTranscriber.kt                  # puente Kotlin -> JNI
  WhsprInputMethodService.kt           # IME: máquina de estados KEYBOARD/RECORDING/TRANSCRIBING
  WhsprRecognitionService.kt           # proveedor de voz Android (RecognitionService)
  Theme.kt                             # paleta monocroma (WhsprColors, WhsprPalette)
app/src/main/cpp/native_whisper.cpp    # JNI + caché nativa del contexto whisper
app/src/main/res/xml/                  # input_method, recognition_service, data_extraction_rules
scripts/                               # build/install/verify (Python, Linux)
third_party/whisper.cpp/               # vendor (MIT) — ver THIRD_PARTY_NOTICES.md
```

## Build / verificación

Entorno canónico: **Fedora/Linux + Python 3 estándar**.

| Acción | Comando |
| --- | --- |
| Comprobar entorno Android | `scripts/check-android-env.py` |
| Crear `local.properties` (SDK) | `scripts/write-local-properties.py` |
| Checks estáticos (lint de facto) | `scripts/check-static.py` |
| Tests de scripts | `python3 -m unittest discover -s scripts/tests -v` |
| Build release (corre static + verify-apk) | `scripts/build-release.py` |
| Build debug | `scripts/build-debug.py` |
| Verificar APK ya generada | `scripts/verify-apk.py` |
| Instalar release + verificar dispositivo | `scripts/install-release.py` |
| Verificar dispositivo/registro Android | `scripts/verify-device.py` |
| Validar catálogo remoto (usa red) | `scripts/verify-model-catalog.py` |
| Gradle directo | `./gradlew :app:assembleRelease` |

Los scripts usan `unittest` de la biblioteca estándar. La verificación automatizada es
**`check-static.py`** (checks regex baratos y sin dependencias) + **`verify-apk.py`**. La verificación funcional real es manual:
ver `TEST_PLAN.md` (requiere dispositivo arm64). Ejecuta `scripts/check-static.py` después de cualquier cambio.

Si añades un invariante crítico, protégelo con un check regex barato en `check-static.py`; no construyas
un analizador frágil ni dependencias nuevas.

## Invariantes que NO romper

- **Contrato de audio**: 16 kHz mono PCM16 WAV entre `AudioRecorder` y `native_whisper.cpp`.
- **arm64-v8a + CPU-only**: nada de otras ABIs ni backends GPU.
- **Release minificada y firmada localmente** con la debug key (para instalar por `adb`); sin modelos embebidos; APK < 4 MB.
- **Sin red directa**: solo `DownloadManager` para el modelo; cero clientes HTTP/telemetría.
- **Validación SHA-256** del modelo antes de transcribir; rechazar modelos parciales/corruptos.
- **Guards de sesión**: no pegar texto si cambia el foco; no permitir cambio de teclado mientras graba/transcribe; no dictar en campos de contraseña.
- **Atribución de micrófono** correcta (contexto) y liberar el micro al devolver resultado.
- No dejar marcadores TODO/stale ni referencias viejas del vendor: `scripts/check-static.py` lo verifica.

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
