# Whspr test plan

Pruebas mínimas antes de dar la app por buena.

## Build

1. Ejecutar en Windows:

   ```powershell
   .\scripts\check-android-env.ps1
   ```

   Si falta solo la ruta del SDK y Android Studio está instalado:

   ```powershell
   .\scripts\write-local-properties.ps1
   ```

2. Confirmar:
   - Java OK
   - Android SDK platform `36` OK
   - Android SDK Build Tools `36.x` OK
   - NDK `28.2.13676358` OK
   - Android SDK CMake OK
   - adb OK

3. Compilar desde Android Studio:

   - Abrir el proyecto.
   - Esperar a que sincronice Gradle.
   - Ejecutar **Build > Make Project**.

   O con Gradle Wrapper:

   ```powershell
   .\gradlew.bat :app:assembleRelease
   ```

   O directamente:

   ```powershell
   .\scripts\build-release.ps1
   ```

   Debe terminar con:
   - APK package/perms/native ABI OK
   - APK manifest services/metadata/privacy OK
   - APK sin modelos/assets embebidos OK
   - APK size <= 4 MB

4. Si quieres validar el catálogo remoto de modelos, ejecutar:

   ```powershell
   .\scripts\verify-model-catalog.ps1
   ```

   Debe confirmar que cada URL es HTTPS, responde por red y el tamaño remoto cumple `minBytes`.

## Instalación

1. Conectar un Android arm64 con depuración USB.
2. Ejecutar:

   ```powershell
   .\scripts\install-release.ps1
   ```

   Si hay más de un dispositivo/emulador conectado, usar `-Serial <adb-serial>` o `ANDROID_SERIAL`.

3. Verificar dispositivo y registro Android:

   ```powershell
   .\scripts\verify-device.ps1
   ```

   Debe confirmar API Android 28+, ABI `arm64-v8a`, IME registrado y `RecognitionService` registrado. El permiso de micrófono debe salir como `OK` si `adb` pudo concederlo, o como aviso para permitirlo manualmente.
   `install-release.ps1` también debe abrir `MainActivity` con `am start -W` sin errores.

4. En Whspr, pulsar **Activar Whspr**.
5. Habilitar `Whspr`.
6. Volver a Whspr.
7. Confirmar `Micro: OK`. Si el permiso no está concedido, pulsar **Permitir micrófono**.
8. Descargar `Tiny multilingüe`.
9. Confirmar estado:
   - `Micro: OK`
   - `Modelo: OK`
10. Si la descarga no arranca, confirmar que Whspr muestra un aviso y no queda en estado `descargando`.
11. Empezar a descargar un modelo, cambiar a otro modelo y confirmar que la descarga anterior no sigue como pendiente ni deja un archivo parcial visible.
12. Mientras un modelo se descarga, confirmar que Whspr no lo marca como `OK` ni permite dictar aunque el archivo parcial ya pese bastante.
13. Si una descarga termina con archivo demasiado pequeño, confirmar que Whspr no permite dictar y limpia el parcial.
14. Si el archivo de modelo existe pero no se puede leer o falla SHA, confirmar que Whspr no se queda en `Transcribiendo…` y permite descargarlo otra vez.
15. Forzar un fallo de transcripción/modelo y confirmar que el botón vuelve a estar usable tras el error.
16. Si los ajustes guardados contienen un modelo o idioma desconocido, confirmar que Whspr vuelve a valores por defecto sin borrar archivos de otro modelo.

## Teclado

1. Abrir cualquier app con campo de texto normal.
2. Cambiar a Whspr.
3. Tocar micrófono.
4. Hablar una frase corta.
5. Tocar de nuevo para parar.
6. Confirmar que el texto aparece en el campo activo.
7. Confirmar que el dictado deja separación final sin duplicar espacios.
8. En un campo de búsqueda o chat, tocar **Intro** y confirmar que ejecuta la acción del campo si existe.
9. En un campo multilínea, tocar **Intro** y confirmar que inserta salto de línea.
10. Escribir un emoji con otro teclado, volver a Whspr y confirmar que **Borrar** elimina el emoji completo.
11. Tocar **Espacio** dos veces y confirmar que no duplica espacios seguidos.

## Dictado de voz Android

1. En Whspr, pulsar **Dictado Android**.
2. Si Android permite elegir proveedor de voz, seleccionar Whspr.
3. En Gboard/SwiftKey/u otro teclado, tocar su botón de micrófono.
4. Confirmar que el teclado/Android empieza a escuchar usando Whspr como proveedor.
5. Parar el dictado desde el teclado y confirmar que el texto vuelve al campo llamante.
6. Confirmar al menos que Whspr aparece como entrada/proveedor de voz Android.
7. Cancelar el dictado desde el teclado mientras Whspr está transcribiendo y confirmar que no aparece texto tarde.
8. Cambiar el idioma de dictado del teclado a español y confirmar que Whspr respeta español; con otro idioma, confirmar que cae a modo auto.
9. En Android 12+, confirmar que el indicador de micrófono atribuye el uso al flujo de dictado esperado y no deja el micro activo tras devolver resultado.
10. En Android 13+, confirmar que el teclado/cliente detecta soporte de voz después de descargar el modelo; antes de descargarlo debe fallar como no disponible, no quedarse colgado.
11. En Android 14+, si el cliente pide descarga de modelo vía `RecognitionService`, confirmar que Whspr responde éxito si ya está descargado y descarga agendada si falta.

## Seguridad y bordes

1. Abrir un campo de contraseña.
2. Confirmar que el micrófono aparece como no disponible.
3. Abrir un campo URL normal y confirmar que Whspr sí permite dictar.
4. Abrir una contraseña numérica/PIN y confirmar que Whspr no permite dictar.
5. Empezar dictado en un campo normal, parar, y cambiar rápido a otro campo.
6. Confirmar que el texto no se pega en el campo nuevo.
7. Intentar cambiar de teclado mientras está grabando/transcribiendo.
8. Confirmar que Whspr lo impide hasta terminar.
9. Girar el móvil en horizontal y confirmar que Whspr no abre una pantalla fullscreen de edición.
10. Cortar una descarga o dejar un modelo corrupto, intentar dictar y confirmar que Whspr lo rechaza y permite descargarlo otra vez.
11. Parar un dictado, abrir Whspr rápido y cambiar idioma/modelo mientras transcribe; confirmar que no borra el modelo equivocado ni pega texto en el campo equivocado.
12. Empezar/parar dictado varias veces rápido y confirmar que no se queda el micro bloqueado ni mezcla audio de intentos anteriores.

## Criterio de aceptación

La app se considera lista cuando:

- Compila sin errores.
- Descarga el modelo.
- Rechaza modelos corruptos.
- Graba desde el teclado.
- Se anuncia como entrada de voz Android y como `RecognitionService`.
- Transcribe localmente.
- Inserta texto en campos normales.
- No dicta en contraseñas.
- No pega texto en el campo equivocado si el foco cambia.
- La APK release local queda minificada y no incluye modelos embebidos.
