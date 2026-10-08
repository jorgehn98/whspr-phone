# Whspr — Diseño

Sistema de diseño mínimo de Whspr. Léelo antes de tocar UI. Tokens en
[`Theme.kt`](app/src/main/java/dev/jorgex/whspr/Theme.kt) (`WhsprColors`).

## Identidad

Tecnológica y sobria, estética terminal/ASCII. **Monocromo**: blancos y grises.
Nada de color de acento; la jerarquía se hace con tonos (claro↔oscuro) y densidad ASCII.

## Paleta

Dos paletas que siguen el modo del sistema (`WhsprColors.Dark` / `Light`). En oscuro
el contenido es claro sobre carbón; en claro, oscuro sobre blanco. Tokens (rol):

| Token | Rol | Oscuro | Claro |
| --- | --- | --- | --- |
| `background` / `backgroundTop` | Fondo (degradado) | `#0E0E10` / `#161619` | `#F5F5F6` / `#FFFFFF` |
| `surface` / `surfaceStroke` | Superficies y bordes | `#1B1B1F` / `#2E2E33` | `#FFFFFF` / `#E2E2E6` |
| `accent` | Texto/iconos accionables | `#F2F2F2` | `#1F1F22` |
| `accentBright` | Máximo brillo (escuchando, núcleo) | `#FFFFFF` | `#101012` |
| `accentDeep` | Procesando | `#B8B8C0` | `#44444A` |
| `accentMuted` | Estructura ASCII atenuada | `#6A6A72` | `#9A9AA0` |
| `glow` | Centro del núcleo / halo | `#FFFFFF` | `#1F1F22` |
| `onAccent` | Sobre el núcleo (glifo del micro) | `#0E0E10` | `#F5F5F6` |
| `textPrimary` / `textMuted` | Texto | `#F2F2F2` / `#9A9AA2` | `#1A1A1D` / `#6A6A70` |
| `disabled` | Deshabilitado | `#4A4A52` | `#C2C2C8` |

## Reglas

- Los colores se definen **solo** en `WhsprColors`. No hardcodear hex en las vistas.
- Paleta monocroma: sin color de acento. Contraste por tono, no por matiz.
- Claro/oscuro automático según el sistema; sin selector.
- Sin Compose ni AndroidX: todo se construye con vistas/`Canvas` a mano.

## Componentes

### Teclado QWERTY (`KeyboardView`)

Grid de teclas renderizado desde `KeyboardLayout` (datos puros, sin lógica).

- **Layouts por idioma**: ES (con ñ) y EN; tildes y variantes en pulsación larga.
- **Capas**: LETTERS, SYMBOLS_1 (operadores, puntuación) y SYMBOLS_2 (símbolos especiales).
  Los campos numéricos abren en SYMBOLS_1, que incluye los dígitos.
- **Fila inferior**: `!#1 · globo · punto · espacio · coma · micro · Intro`. El ajuste
  "Posición del punto" intercambia punto y coma. El punto ofrece `? ! ¿ ¡ : ;` en
  pulsación larga.
- **Estructura de cada tecla**: `FrameLayout` (fondo y caja táctil) con un `TextView` o
  `ImageView` centrado dentro — no `TextView` con compound drawable (con label vacío no
  centra verticalmente).
- **Toque**: lo resuelve `KeyboardView`, no cada tecla. Gana la tecla más cercana, así
  que no hay zonas muertas en los huecos; se puede corregir deslizando antes de soltar;
  y un dedo nuevo confirma la tecla que aún estuviera pulsada, para escribir rápido con
  dos pulgares. Cada tecla conserva su propio click solo para accesibilidad.
- **Feedback de pulsación**: sin ripple expansivo. `StateListDrawable` con dos shapes
  fijos — normal y `state_pressed`, un tono más resaltado — y `setExitFadeDuration(0)`:
  el cambio es instantáneo al tocar y al soltar. Además, vibración de tecla del sistema.
  `surfaceRippleBackground` (Ui.kt) se usa en los botones de las pantallas de la app.
- **Tipografía**: monoespaciada.
- **Iconos vectoriales**: SHIFT, BACKSPACE, ENTER, GLOBE y MIC son `vector` drawables
  derivados de Lucide (ver `THIRD_PARTY_NOTICES.md`), tintados en runtime con el mismo
  tono que el texto de las teclas — nunca a color ni dependientes del emoji del fabricante.
- **Estados de SHIFT** (`KeyboardView.ShiftState`), inconfundibles entre sí:
  - `NONE`: icono `ic_key_shift` en `textPrimary`.
  - `SHIFT` (una letra; lo activa el usuario o la mayúscula automática): fondo
    `surfaceStroke` e icono `accentBright`.
  - `CAPS_LOCK` (doble toque en 500 ms): tecla invertida — fondo `accentBright`, icono
    `ic_key_shift_caps` en `onAccent`.
  Cambiar de estado actualiza las teclas existentes, sin reconstruir el grid.

### Panel de dictado (`DictationView`)

Sustituye al teclado mientras se graba o transcribe. Cabecera de 40 dp con el estado a la
izquierda (`Escuchando… toca para terminar` / `Transcribiendo…`, monoespaciado,
`textMuted`) y el botón **Cancelar** a la derecha (superficie con borde, como una tecla);
debajo, el visualizador de voz ocupa el resto y termina la grabación al tocarlo.

### Visualizador de voz (`VoiceWaveView`)

19 barras verticales finas, centradas y simétricas, dibujadas con `Canvas`.

- **RECORDING**: barras reactivas al nivel de audio, color `accentBright`. Las centrales
  responden más que las de los extremos; un jitter leve las mantiene vivas.
- **TRANSCRIBING**: barrido sinusoidal, color `accentDeep`.

El nivel llega desde el hilo de audio (`setLevel` solo escribe un `@Volatile Float`,
nunca invalida la vista). `onDraw` suaviza con attack rápido y decay lento sobre el valor
crudo y después remapea el rango útil de voz normal (~0.02..0.4) a 0..1, para que la onda
se note sin gritar.

### Altura del IME

Teclado y panel de dictado comparten la misma altura (`KeyboardView.heightDp`): 240 dp en
vertical y, en horizontal, como mucho media pantalla. Alternar entre ambos solo cambia
qué vista es visible, así la app de debajo no da un salto de layout al empezar o terminar
un dictado. Las filas se reparten ese alto a partes iguales: con la fila numérica oculta
quedan 4 filas más altas.

### Pantalla principal (`MainActivity`)

Una columna con tres secciones:

- **Puesta en marcha**: cuatro pasos como botones monoespaciados alineados a la
  izquierda, con casilla ASCII (`[x]` hecho, `[ ]` pendiente) y texto que describe el
  estado real o la acción pendiente. El paso del modelo muestra el porcentaje de descarga
  y lleva al lado la papelera (cancelar descarga o borrar modelo).
- **Dictado**: modelo e idioma, cada uno con su selector.
- **Pruébalo**: un campo de texto para probar teclado y dictado sin salir de la app.

**Más ajustes** (`SettingsActivity`) agrupa los ajustes del teclado y la sección
**Acerca de** (licencias de terceros y versión).

### Icono de la app (launcher)

Adaptive icon vectorial (`mipmap-anydpi` + drawables), derivado del visualizador de voz:
9 barras verticales blancas (`#FFFFFF`) con la envolvente simétrica del modo RECORDING,
sobre fondo carbón `#0E0E10` (token `background` oscuro). Menos barras que
`VoiceWaveView` (9 frente a 19) a propósito: a tamaño launcher las 19 barras finas
pierden definición. La capa `monochrome` reutiliza el foreground, así que los themed
icons de Android 13+ salen gratis. Los hex van en los drawables del icono porque los
recursos de launcher no pueden leer `WhsprColors`; son los mismos valores de la paleta
oscura.
