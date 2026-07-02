# Whspr

Entrada de voz Android para **dictado 100% local**. Sin nube: descarga un modelo Whisper al almacenamiento propio de la app y transcribe en el dispositivo con `whisper.cpp`.

[![Static checks](https://github.com/jorgehn98/whspr-phone/actions/workflows/static-checks.yml/badge.svg)](https://github.com/jorgehn98/whspr-phone/actions/workflows/static-checks.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android%2028%2B-3DDC84)
![ABI](https://img.shields.io/badge/ABI-arm64--v8a-blue)

## Estado

MVP inicial:

- App Android nativa en Kotlin.
- `InputMethodService` registrado como entrada de voz del sistema.
- `RecognitionService` registrado como proveedor de reconocimiento de voz Android.
- Support-check moderno del proveedor de voz cuando el modelo ya está descargado.
- Respuesta mínima a petición moderna de descarga de modelo: éxito si ya está descargado, descarga agendada si falta.
- UI mínima para activar/cambiar teclado.
- Permiso de micrófono.
- Descarga de modelo local.
- Validación SHA-256 del modelo antes de transcribir.
- Ajustes mínimos: modelo e idioma.
- Botón de micrófono en el teclado.
- Captura de audio a WAV 16 kHz desde el IME.
- Motor local con `whisper.cpp` vía JNI/CMake.
- Caché nativa del modelo para no recargarlo en cada dictado.

La app no usa nube. Descarga el modelo al almacenamiento propio de la app y transcribe localmente.

## Uso

1. Abre Whspr.
2. Pulsa **Activar Whspr** y habilita `Whspr`.
3. Pulsa **Permitir micrófono**.
4. Elige modelo. Por defecto: `Tiny multilingüe`, el más ligero.
5. Pulsa **Descargar modelo**.
6. Pulsa **Cambiar a Whspr** y elige Whspr para usar su teclado QWERTY completo.
7. En cualquier campo de texto, pulsa el micrófono para dictar o escribe normalmente.
8. Usa **Globo** para cambiar entre ES (ñ, tildes) e EN (ñ en long-press, tildes en long-press).
9. Pulsa **!#1** para acceder a símbolos; **[1/2]** y **[2/2]** para navegar entre páginas.

## Privacidad

- El audio nunca sale del dispositivo. No hay servidores ni telemetría.
- La única conexión de red es la descarga del modelo, vía `DownloadManager` del sistema.
- El modelo se valida por SHA-256 antes de transcribir y se guarda en el almacenamiento privado de la app.
- Permisos declarados: solo `RECORD_AUDIO` e `INTERNET` (este último, exclusivamente para descargar el modelo).

## Principio del proyecto

Simplicidad antes que features:

- Sin Compose.
- Sin dependencias AndroidX.
- Sin nube.
- Sin historial al principio.
- Sin postprocesado hasta que el IME funcione bien.

## Siguiente paso

Compilar y probar en dispositivo/emulador:

- Android Studio con SDK/NDK/CMake instalado.
- Build release local minificada.
- Instalar APK.
- Activar `Whspr`.
- Descargar modelo.
- Dictar desde cualquier campo de texto.

## Build

El proyecto usa:

- Android Gradle Plugin.
- Kotlin Android.
- Android SDK 36.
- NDK + CMake.
- `whisper.cpp` vendorizado en `third_party/whisper.cpp`, recortado a Android `arm64-v8a` CPU-only.

Avisos de terceros: `THIRD_PARTY_NOTICES.md`.

Desde Android Studio:

- Abrir el proyecto.
- Esperar sincronización Gradle.
- Ejecutar **Build > Make Project**.

Con Gradle Wrapper:

```bash
./gradlew :app:assembleRelease
```

En Windows:

```powershell
.\gradlew.bat :app:assembleRelease
```

O con el script del proyecto:

```powershell
.\scripts\build-release.ps1
```

Ese script ejecuta primero `.\scripts\check-static.ps1` y después comprueba el entorno Android.
Al terminar también ejecuta `.\scripts\verify-apk.ps1` para confirmar que la APK release:

- usa el package correcto;
- declara solo permisos mínimos;
- contiene solo `arm64-v8a`;
- registra IME y `RecognitionService`;
- no embebe modelos/assets;
- se mantiene por debajo de 4 MB.

Para compilar la APK ligera, instalar en un dispositivo conectado y abrir Whspr:

```powershell
.\scripts\install-release.ps1
```

Ese script también ejecuta `.\scripts\verify-device.ps1` después de instalar y abre `MainActivity` con `am start -W`.
Para agilizar la prueba local por `adb`, también intenta conceder `RECORD_AUDIO`. Si el dispositivo lo bloquea, Whspr lo pedirá al abrir la app.

Para desarrollo puedes seguir usando `.\scripts\build-debug.ps1` o `.\scripts\install-debug.ps1`, pero la APK recomendada para usar es `app-release.apk`: va minificada, sin modelos embebidos y firmada localmente con la debug key para poder instalarla por `adb`.

Para verificar en el dispositivo que cumple API mínima, es `arm64-v8a` y que Android ve el IME y el proveedor de voz:

```powershell
.\scripts\verify-device.ps1
```

Ese check también informa del permiso de micrófono. `install-release.ps1` lo exige solo si pudo concederlo por `adb`; si no, abre Whspr para permitirlo manualmente.

Si hay más de un dispositivo/emulador conectado:

```powershell
.\scripts\install-release.ps1 -Serial <adb-serial>
.\scripts\verify-device.ps1 -Serial <adb-serial>
```

Para comprobar rápido qué falta en Windows antes de compilar:

```powershell
.\scripts\check-android-env.ps1
```

Para pasar checks básicos que no necesitan Android Studio:

```powershell
.\scripts\check-static.ps1
```

Comprueba balance simple de Kotlin/C++, XML válido, sintaxis PowerShell, strings/recursos, componentes y permisos mínimos, identidad de app/JNI, wiring de voz, contexto de atribución, support-check/model-download callback del dictado y guards de API modernos, catálogo de modelos, release minificada firmada localmente, versiones de build fijadas, build `arm64-v8a`, ausencia de dependencias extra, `.gitignore` Android correcto, ausencia de clientes directos de red/telemetría, retornos peligrosos en `synchronized` y referencias viejas del vendor.

Para inspeccionar la APK release ya generada:

```powershell
.\scripts\verify-apk.ps1
```

Para comprobar manualmente que las URLs del catálogo de modelos siguen vivas y que el tamaño remoto cuadra:

```powershell
.\scripts\verify-model-catalog.ps1
```

Este check usa red, por eso no forma parte del build por defecto.

En Windows, los scripts intentan usar primero `JAVA_HOME`/`java` y, si no existe, el Java incluido con Android Studio.

Si Android Studio no deja `ANDROID_HOME`, crea `local.properties` con:

```properties
sdk.dir=C\:\\Users\\TU_USUARIO\\AppData\\Local\\Android\\Sdk
```

O deja que el proyecto lo cree si encuentra el SDK:

```powershell
.\scripts\write-local-properties.ps1
```

Plan de prueba manual: `TEST_PLAN.md`.

## Contribuir

Antes de tocar el código, lee `AGENTS.md`: stack, comandos e invariantes que no hay que romper.
Ejecuta `.\scripts\check-static.ps1` antes de proponer cambios; es la verificación rápida del proyecto.

## Licencia

MIT — ver `LICENSE`.

Incluye `whisper.cpp` (MIT) vendorizado en `third_party/whisper.cpp`. Avisos de terceros en `THIRD_PARTY_NOTICES.md`.
