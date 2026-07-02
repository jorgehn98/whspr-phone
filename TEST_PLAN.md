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

### Escritura manual

1. Abrir cualquier app con campo de texto normal.
2. Cambiar a Whspr.
3. Escribir una frase con letras, números, símbolos.
4. Confirmar que el texto aparece en el campo activo.
5. Pulsar **Borrar** y confirmar que elimina un carácter. Mantener pulsado y confirmar que repite (400ms inicio, 50ms intervalo).
6. Escribir un emoji con otro teclado, volver a Whspr y confirmar que **Borrar** lo elimina completo.
7. Pulsar **Espacio** dos veces y confirmar que no duplica espacios seguidos.
8. Pulsar **Globo** y confirmar que cambia entre ES (ñ, tildes directas) e EN (ñ/tildes en long-press).
8b. Confirmar que SHIFT, BACKSPACE, ENTER, GLOBE y MIC se ven como iconos vectoriales
    monocromos (mismo tono que el resto de teclas), sin emoji a color ni glifos de
    fuente finos o con tono distinto, y que quedan centrados en ambos ejes dentro de
    la tecla (ni desplazados hacia arriba ni hacia un lado).
8c. Confirmar que, por defecto, el punto (`.`) está a la IZQUIERDA del espacio en la
    fila inferior (`!#1 · globo · . · espacio · micro · Intro`).
8d. En Whspr, pulsar **Más ajustes** y, en la sección **Teclado**, abrir **Posición del
    punto** y cambiar a "Derecha". Volver al teclado (sin necesidad de reiniciar la app)
    y confirmar que el punto pasa a la derecha del espacio. Cambiar de nuevo a
    "Izquierda" y confirmar que vuelve. En ambos lados, confirmar que el long-press del
    punto sigue ofreciendo la coma.
8e. Confirmar que al tocar y soltar cualquier tecla, el resaltado de fondo cambia y
    se apaga de forma instantánea (sin onda expansiva ni fundido perceptible).
8f. Confirmar que, por defecto, LETTERS muestra la fila de números (1234567890) encima
    de las letras, y que SYMBOLS_1/SYMBOLS_2 siempre la muestran también.
8g. En Whspr, pulsar **Más ajustes** y, en la sección **Teclado**, abrir **Fila de
    números** y elegir "Ocultar". Volver al teclado (sin reiniciar la app) y confirmar
    que LETTERS pasa a 4 filas (sin números), con teclas proporcionalmente más altas, y
    que el ALTO TOTAL del teclado no cambia (compararlo con el alto que tenía antes, p.
    ej. mirando si la posición del espacio/enter en la fila inferior se mantiene).
    Confirmar que SYMBOLS_1/SYMBOLS_2 siguen mostrando su fila de números aunque el
    ajuste esté en "Ocultar". Volver a "Mostrar" y confirmar que LETTERS recupera las 5
    filas con el mismo alto total.
8h. Con el teclado abierto en un campo de texto, ir a Whspr y cambiar el idioma de
    dictado, o entrar en **Más ajustes** y cambiar posición del punto o fila de números.
    Volver al campo de texto SIN rotar ni reiniciar la app y confirmar que el teclado
    refleja el cambio en cuanto se vuelve a enfocar el campo.
8i. Desde Whspr, pulsar **Más ajustes**. Confirmar que se abre una pantalla nueva con
    encabezado de sección **Teclado** y los ajustes de posición del punto y fila de
    números. Confirmar que el back estándar del sistema vuelve a la pantalla principal
    sin perder el estado de Whspr.
9. Pulsar **!#1** y confirmar que muestra SYMBOLS_1 (operadores, puntuación).
10. Pulsar **1/2** para ir a SYMBOLS_2 (símbolos especiales); confirmar que el label
    cabe en una sola línea sin desbordar la tecla. Pulsar **2/2** para volver a SYMBOLS_1.
11. Pulsar **ABC** para volver a LETTERS.

### Mayúsculas

1. Pulsar **SHIFT** una vez. Confirmar que la siguiente letra se escribe en mayúsculas, el fondo de la tecla se resalta (un tono más claro) y el icono cambia a `accentBright`.
2. Después de escribir una letra mayúscula, confirmar que SHIFT se apaga automáticamente (fondo e icono vuelven a normal).
3. Pulsar **SHIFT** dos veces con el dedo, con un intervalo natural (hasta ~450ms) para activar CAPS_LOCK. Confirmar que la ventana de doble tap (500ms) es suficiente para un doble tap real con el dedo y ya no lo interpreta como activar+desactivar. Confirmar que la tecla se invierte (fondo claro sólido) con el icono de barra oscuro, y que se distingue claramente tanto del estado NONE como del SHIFT transitorio.
4. Escribir varias letras en mayúsculas.
5. Pulsar **SHIFT** de nuevo para apagar CAPS_LOCK.
6. Con SHIFT o CAPS activo, usar long-press para escribir tildes/acentos: deben salir en mayúsculas (É, Ñ, etc.).

### Long-press

1. Mantener pulsado **E** (ES/EN) y confirmar que aparece popup con variantes: e, é, è, ë, ê.
2. Pulsar una variante del popup; confirmar que aparece en el campo y el popup se cierra.
3. Confirmar que long-press en punto (`.`) ofrece también coma (`,`).
4. Confirmar que long-press en A ofrece á, à, ä, â, ã.
5. Confirmar que long-press en O ofrece ó, ò, ö, ô, õ.
6. Confirmar que long-press en Ñ (ES) y N (EN) ofrecen ñ.
7. Confirmar que long-press en C ofrece ç.

### Dictado de voz

1. Abrir un campo de texto y cambiar a Whspr.
2. Pulsar el micrófono. Confirmar que el teclado desaparece y muestra barras visualizadoras, y que el área del teclado NO cambia de alto al pasar de teclado a onda (ni al volver).
3. Hablar una frase corta con volumen normal. Confirmar que las barras reaccionan visiblemente (llenan buena parte del alto disponible, no solo un movimiento apenas perceptible) y con silencio quedan casi planas.
3b. Confirmar que la onda anima desde la PRIMERA grabación tras abrir el teclado (sin necesidad de una grabación previa), y también tras rotar el dispositivo a mitad de grabación.
4. Pulsar de nuevo (en la onda o el micrófono) para parar la grabación.
5. Confirmar que pasa a estado TRANSCRIBING (barras con barrido sinusoidal, accentDeep).
6. Esperar a que transcribe. Confirmar que el teclado reaparece y el texto se inserta en el campo.
7. Confirmar que el dictado deja separación final sin duplicar espacios.
8. En un campo de búsqueda o chat, pulsar **Intro** desde el teclado tras escribir/dictar, y confirmar que ejecuta la acción del campo si existe.
9. En un campo multilínea, pulsar **Intro** y confirmar que inserta salto de línea.
10. Dictar en silencio (sin hablar, unos segundos de grabación) y parar. Confirmar que no
    se pega ningún texto ni etiqueta tipo "[MÚSICA]"/"(music)"/"♪" en el campo, no aparece
    ningún Toast de error, y el teclado vuelve solo al estado normal.

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
