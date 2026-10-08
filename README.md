# Whspr

Teclado Android con **dictado 100% local**. Sin nube: descarga un modelo Whisper al almacenamiento propio de la app y transcribe en el dispositivo con `whisper.cpp`.

[![Static checks](https://github.com/jorgehn98/whspr-phone/actions/workflows/static-checks.yml/badge.svg)](https://github.com/jorgehn98/whspr-phone/actions/workflows/static-checks.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android%209%2B-3DDC84)
![ABI](https://img.shields.io/badge/ABI-arm64--v8a-blue)

## Qué hace

- **Teclado QWERTY completo** (ES/EN, símbolos, tildes en pulsación larga) con una tecla de micrófono.
- **Dictado en el teclado**: pulsas el micro, hablas y el texto se inserta en el campo activo. Se puede cancelar mientras graba o mientras transcribe.
- **Proveedor de voz Android** (`RecognitionService`): otras apps y teclados pueden usar Whspr como reconocedor.
- **Privado**: el audio nunca sale del dispositivo. No hay servidores, cuentas ni telemetría.

## Uso

Al abrir Whspr, la pantalla principal muestra cuatro pasos con su estado real (`[x]` hecho, `[ ]` pendiente):

1. **Permitir micrófono.**
2. **Descargar modelo.** Muestra el porcentaje de descarga; la papelera cancela la descarga o borra el modelo.
3. **Activar el teclado Whspr** en los ajustes de Android.
4. **Elegir Whspr como teclado.**

Debajo se eligen el modelo y el idioma de dictado, y hay un campo para probar el teclado sin salir de la app.

### Modelos

| Modelo | Tamaño | Cuándo elegirlo |
| --- | --- | --- |
| Rápido (`tiny`) | 31 MB | Móviles modestos o frases cortas donde prima la velocidad. |
| Equilibrado (`base`), por defecto | 57 MB | Uso general. |
| Preciso (`small`) | 181 MB | Máxima precisión; tarda bastante más en transcribir. |

Todos son multilingües y cuantizados (`q5_1`). Cada uno se valida por SHA-256 antes de usarse.

### En el teclado

- **Micrófono**: empieza a escuchar. Toca la onda para terminar o **Cancelar** para descartar. La grabación se detiene sola a los 60 s.
- **Mayúsculas**: automáticas al empezar frase; un toque en SHIFT para una letra, dos para bloquear.
- **Espacio dos veces**: punto y espacio.
- **Pulsación larga**: tildes y variantes en las letras; `? ! ¿ ¡ : ;` en el punto; selector de teclados del sistema en el globo.
- **Globo**: alterna entre el teclado ES y EN.
- **`!#1`**: símbolos, con dos páginas.

**Más ajustes** permite elegir el lado del punto respecto al espacio (la coma ocupa el otro), mostrar u ocultar la fila de números, y consultar las licencias de terceros.

Whspr no dicta en campos de contraseña.

## Privacidad

- El audio vive solo en memoria mientras se transcribe: nunca se escribe en disco.
- La única conexión de red es la descarga del modelo, con el `DownloadManager` del sistema.
- Permisos: `RECORD_AUDIO` e `INTERNET` (este último solo para descargar el modelo).
- El manifiesto declara `<queries>` para poder comprobar el permiso de micrófono de la app que pide un dictado al proveedor de voz. No se usa para nada más.
- Copias de seguridad y transferencia entre dispositivos desactivadas.

## Arquitectura

```text
InputMethodService / RecognitionService
            ↓
   AudioRecorder (WAV 16 kHz mono PCM16, detección de voz)
            ↓
   LocalTranscriber (valida SHA-256, transcribe, filtra)
            ↓
      Kotlin → JNI/CMake
            ↓
 whisper.cpp (arm64, CPU-only)
            ↓
      texto en la aplicación
```

- Kotlin sin Compose ni AndroidX; las vistas se construyen a mano.
- `whisper.cpp` vendorizado y recortado a `arm64-v8a` CPU-only.
- El modelo se descarga a una zona de paso, se valida por SHA-256 mientras se copia al almacenamiento interno de la app y Whisper solo lo carga desde ahí.
- El modelo cargado se mantiene en memoria entre dictados y se libera tras 5 minutos sin usar.
- Whspr nunca descarga un modelo por orden de otra app: solo desde su propia pantalla.
- Una grabación en la que no se detecta voz no llega al modelo: Whisper inventa frases cuando solo recibe silencio.

Detalles de diseño en `DESIGN.md`; reglas para quien toque el código en `AGENTS.md`.

## Build

Requisitos (Fedora/Linux):

- JDK 17 o superior (un JRE no basta: hace falta `javac`).
- Android SDK con plataforma 36, Build Tools 36.x, NDK `28.2.13676358` y CMake.

Comprueba qué falta:

```bash
scripts/check-android-env.py
```

Si el SDK está instalado pero el proyecto no lo encuentra:

```bash
scripts/write-local-properties.py
```

Compilar la release:

```bash
scripts/build-release.py
```

Ese script ejecuta los checks estáticos, Android lint (los avisos son errores), compila la APK minificada y la verifica con `scripts/verify-apk.py`: package y permisos, solo `arm64-v8a`, IME y `RecognitionService` registrados, sin modelos embebidos y por debajo de 4 MB.

Instalar en un dispositivo conectado y abrir la app:

```bash
scripts/install-release.py            # o --serial <adb-serial> si hay varios
```

Otros comandos:

| Acción | Comando |
| --- | --- |
| Checks estáticos | `scripts/check-static.py` |
| Tests de los scripts | `python3 -m unittest discover -s scripts/tests -v` |
| Verificar dispositivo y registro Android | `scripts/verify-device.py` |
| Verificar el catálogo remoto de modelos (usa red) | `scripts/verify-model-catalog.py` |
| Build de depuración | `scripts/build-debug.py` |

La integración continua ejecuta los checks estáticos y compila, pasa lint y verifica la release en cada PR.

### Firma de release

Por defecto la release se firma con la debug key local, suficiente para instalar por `adb`. Esa clave es distinta en cada máquina: una APK compilada en otro equipo no actualiza una instalación previa.

Para distribuir la app con una clave estable, crea `keystore.properties` en la raíz del proyecto (no se versiona) y el build lo usará automáticamente:

```properties
storeFile=/ruta/a/whspr-release.jks
storePassword=...
keyAlias=whspr
keyPassword=...
```

## Pruebas

El plan de prueba manual está en `TEST_PLAN.md`, junto con la forma de verificar la app en un emulador cuando no hay un dispositivo arm64 a mano.

## Contribuir

Lee `AGENTS.md` antes de tocar el código: stack, comandos e invariantes que no hay que romper. Ejecuta `scripts/check-static.py` antes de proponer cambios.

## Licencia

MIT — ver `LICENSE`.

Incluye `whisper.cpp` (MIT) vendorizado en `third_party/whisper.cpp` e iconos derivados de Lucide (ISC). Avisos de terceros en `THIRD_PARTY_NOTICES.md` y dentro de la app, en **Más ajustes**.
