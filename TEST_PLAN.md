# Whspr — plan de pruebas

Qué comprobar antes de dar una versión por buena. La verificación automática (checks estáticos, lint, build y `verify-apk.py`) cubre que la app compila y está bien empaquetada; el comportamiento se comprueba a mano con este plan, en un dispositivo arm64 o en el emulador (ver el final).

## 1. Build

```bash
scripts/check-android-env.py     # JDK con javac, SDK 36, Build Tools 36.x, NDK 28.2.13676358, CMake, adb
scripts/build-release.py         # checks estáticos + lint + APK + verify-apk
scripts/verify-model-catalog.py  # opcional, usa red: URLs y tamaños del catálogo
```

`build-release.py` debe terminar con todas las líneas `OK` de `verify-apk.py` y un tamaño de APK por debajo de 4 MB.

## 2. Instalación y puesta en marcha

```bash
scripts/install-release.py       # --serial <adb-serial> si hay varios dispositivos
```

1. La app se abre y muestra los cuatro pasos de **Puesta en marcha**.
2. **Permitir micrófono** pide el permiso; al concederlo el paso pasa a `[x] Micrófono permitido`. Si el permiso se denegó de forma permanente, el botón abre los ajustes de la app.
3. **Descargar modelo** muestra `Descargando modelo… N %` y termina en `[x] Modelo descargado`.
4. Durante la descarga, la papelera la cancela y el paso vuelve a `[ ] Descargar modelo`. Con el modelo descargado, la papelera lo borra.
5. Cambiar de modelo a mitad de descarga cancela la descarga anterior y no deja un archivo parcial dado por bueno.
6. Sin red, o si la descarga falla, el paso muestra `La descarga falló. Reintentar` y no queda en "descargando".
7. **Activar el teclado Whspr** abre los ajustes de Android; al volver, el paso refleja si Whspr está activado.
8. **Elegir Whspr como teclado** abre el selector; al elegirlo el paso pasa a `[x] Whspr es el teclado actual`.
9. El campo **Pruébalo** abre el teclado Whspr y permite escribir y dictar.
10. **Más ajustes** muestra posición del punto, fila de números, licencias de terceros y la versión.

## 3. Teclado

### Escritura

1. Escribir una frase con letras, números y símbolos: aparece en el campo activo.
2. Tocar en el hueco entre dos teclas escribe la más cercana; no hay zonas muertas.
3. Escribir rápido con dos pulgares (segundo dedo abajo antes de levantar el primero): no se pierde ninguna letra y salen en orden.
4. Empezar a pulsar una tecla, deslizar a la vecina y soltar: se escribe la tecla donde se suelta.
5. Cada pulsación vibra si la vibración de teclado está activada en el sistema.
6. **Borrar** elimina un carácter al pulsar y repite al mantener (400 ms de espera, luego cada 50 ms). Un emoji escrito con otro teclado se borra entero.
7. **Intro** ejecuta la acción del campo (buscar, enviar) si existe; en un campo multilínea inserta salto de línea.
8. **Globo** alterna entre ES (con ñ) y EN; mantenerlo pulsado abre el selector de teclados del sistema.
9. **`!#1`** abre los símbolos; **1/2** y **2/2** cambian de página; **ABC** vuelve a letras.
10. Un campo numérico o de teléfono abre directamente la capa con dígitos; al pasar a un campo de texto vuelve a letras.

### Mayúsculas y puntuación

1. En un campo de texto vacío, SHIFT aparece activado y la primera letra sale en mayúscula; después se apaga solo.
2. Tras punto y espacio vuelve a activarse la mayúscula.
3. Dos espacios seguidos tras una palabra escriben punto y espacio. En campos de URL, correo o contraseña, no. Un solo espacio junto a otro que ya estaba en el texto (o tras un dictado) tampoco.
3b. Con la mayúscula automática activa, ir a `!#1` y volver con **ABC**: la mayúscula sigue activa.
4. Un toque en SHIFT pone una sola mayúscula; dos toques seguidos bloquean mayúsculas (tecla invertida) y un tercero las quita.
5. Con mayúsculas bloqueadas, pasar por símbolos y volver las conserva; un SHIFT de una sola letra se descarta.
6. En un campo de contraseña no hay mayúscula automática.

### Pulsación larga

1. `e`, `a`, `o`, `u`, `i` ofrecen sus tildes y variantes; `c` ofrece `ç`; `n` ofrece `ñ` en EN.
2. El punto ofrece `? ! ¿ ¡ : ;`.
3. Elegir una variante la escribe y cierra el popup; soltar sin elegir no escribe nada.
4. Con SHIFT activo, las variantes salen en mayúscula.

### Ajustes del teclado

1. Por defecto el punto está a la izquierda del espacio y la coma a la derecha. **Posición del punto → Derecha** los intercambia.
2. **Fila de números → Ocultar** deja 4 filas en letras sin cambiar el alto total; los símbolos siguen mostrando los dígitos.
3. Los cambios se aplican al volver a un campo de texto, sin reiniciar la app.

### Aspecto

1. Claro y oscuro siguen al sistema; todo es monocromo.
2. Los iconos de SHIFT, borrar, Intro, globo y micro son vectoriales, del mismo tono que las letras y centrados.
3. En horizontal el teclado ocupa como mucho media pantalla y no abre el modo de edición a pantalla completa.

## 4. Dictado desde el teclado

1. Pulsar el micro: el teclado se sustituye por el panel de dictado (`Escuchando… toca para terminar`, botón **Cancelar** y la onda) sin que cambie el alto.
2. Al hablar, las barras reaccionan; en silencio quedan casi planas.
3. Tocar la onda: pasa a `Transcribiendo…`, y al terminar vuelve el teclado con el texto insertado y un espacio final.
4. Dictar justo después de una palabra ya escrita: el texto dictado queda separado por un espacio, no pegado.
5. **Cancelar** mientras graba: vuelve el teclado, no se inserta nada y el indicador de micrófono del sistema se apaga.
6. **Cancelar** mientras transcribe: vuelve el teclado de inmediato y no aparece texto más tarde. Un dictado nuevo funciona con normalidad.
7. Grabar unos segundos sin hablar y terminar: aviso `No he oído nada.`, sin texto inventado.
8. Dejar correr la grabación hasta el límite (~60 s): se detiene sola y transcribe.
9. Ocultar el teclado (atrás) mientras graba: la grabación se cancela y el micrófono se libera.
10. Cambiar de campo mientras transcribe: el texto no se pega en el campo nuevo.
11. Girar el dispositivo mientras graba: si la app conserva el campo, la grabación continúa; si lo recrea (lo habitual), el dictado se cancela, el micrófono se libera y no se pega texto.
12. Sin permiso de micrófono o sin modelo: aviso y se abre Whspr para resolverlo.
13. Con el idioma de dictado en **Auto**, dictar en español y en inglés: se transcribe en el idioma hablado.
14. Modelo corrupto (archivo interno alterado, solo posible con root): `El modelo descargado no es válido`, el archivo se borra y se puede volver a descargar.
15. Empezar y parar varias veces seguidas: el micro no queda bloqueado ni se mezcla audio de intentos anteriores.

## 5. Proveedor de voz Android (`RecognitionService`)

Con otra app o teclado que use `SpeechRecognizer` apuntando a Whspr:

1. La escucha empieza (`onReadyForSpeech`) y el cliente recibe niveles (`onRmsChanged`). No debe llegar `ERROR_INSUFFICIENT_PERMISSIONS` si el cliente tiene permiso de micrófono.
2. Tras hablar y callar ~1,5 s la escucha termina sola y el cliente recibe el texto en `onResults` (`RESULTS_RECOGNITION`).
3. Sin hablar, a los ~8 s el cliente recibe `ERROR_NO_MATCH`, sin texto inventado.
4. Cancelar desde el cliente: no llega ningún resultado tardío y el micrófono se libera.
5. Con `EXTRA_LANGUAGE` (p. ej. `en-US`) se transcribe en ese idioma; con un idioma que Whisper no conoce, se detecta automáticamente y se transcribe igual.
6. Una petición de descarga de modelo desde el cliente (Android 14+) responde éxito si el modelo ya está instalado y error si no: Whspr nunca descarga por orden de otra app.
7. En Android 13+, el cliente detecta soporte solo cuando el modelo está instalado.
8. En Android 12+, el indicador de micrófono atribuye el uso correctamente y se apaga al devolver el resultado.

## 6. Seguridad y bordes

1. En campos de contraseña (texto y PIN numérico) la tecla de micro aparece atenuada y al pulsarla solo avisa.
2. En un campo de URL normal sí se puede dictar.
3. Una ventana superpuesta al teclado (overlay) no puede hacer que se pulsen teclas.
4. Ajustes guardados con un modelo o idioma desconocido: la app vuelve a los valores por defecto.
5. Tras 5 minutos sin dictar, la memoria del proceso del teclado baja (modelo liberado) y el siguiente dictado lo vuelve a cargar.

## Criterio de aceptación

- Compila, pasa lint y `verify-apk.py`.
- Los cuatro pasos de puesta en marcha reflejan el estado real.
- Se escribe con normalidad con el teclado.
- Dicta y transcribe en local, se puede cancelar, y no inserta nada cuando no hay voz.
- No dicta en contraseñas ni pega texto en un campo distinto del que inició el dictado.
- Otras apps pueden usar Whspr como reconocedor de voz.

## Verificar sin dispositivo (emulador)

La APK es solo `arm64-v8a`, pero las imágenes `x86_64` del emulador con Google APIs (API 30+) traducen binarios arm64, así que la app real se instala y transcribe ahí, más despacio que en un móvil.

1. Crear un AVD con una imagen `system-images;android-34;google_apis;x86_64` y arrancarlo con `-grpc 8554` (en equipos donde el emulador sin ventana falla, probar `-gpu host`).
2. `scripts/install-release.py --serial emulator-5554`.
3. El emulador no tiene micrófono real: para dictar hay que inyectar audio con la llamada `injectAudio` de su API gRPC (`emulator/lib/emulator_controller.proto` en el SDK), enviando un WAV de 16 kHz mono **mientras la app está grabando**. Inyectar sin una grabación activa puede hacer caer el emulador.
4. Para la sección 5 hace falta una app cliente mínima que llame a `SpeechRecognizer.createSpeechRecognizer(context, ComponentName("dev.jorgex.whspr", "dev.jorgex.whspr.WhsprRecognitionService"))`.

Lo que el emulador no sustituye: la sensación real de escritura, la vibración, la latencia de transcripción en un móvil y el comportamiento con un micrófono y ruido reales.
