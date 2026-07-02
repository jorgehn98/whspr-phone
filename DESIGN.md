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

Grid de teclas renderizado dinámicamente desde `KeyboardLayout` (datos puros sin
lógica). Características:

- **Layouts por idioma**: ES (con ñ, tildes completas) e EN (con ñ/tildes en long-press).
- **Capas**: LETTERS (ES/EN), SYMBOLS_1 (operadores, puntuación), SYMBOLS_2 (símbolos especiales).
- **Teclas especiales**: SHIFT (con doble tap para CAPS_LOCK), BACKSPACE (repetición
  hold 400ms/50ms), SPACE, PERIOD (coma en long-press), ENTER, MIC (micrófono).
- **Long-press**: variantes de tildes/acentos (é, ú, ñ, etc.) sobre caracteres.
- **Tipografía**: monoespaciada, sin Compose, todo con `TextView` en `LinearLayout`.
- **Colores**: superficie redondeada (`Surface` + `SurfaceStroke`), texto `TextPrimary`.
- **Iconos vectoriales**: SHIFT, BACKSPACE, ENTER, GLOBE y MIC se renderizan con
  `vector` drawables en `res/drawable/` (`ic_key_shift`, `ic_key_shift_caps`,
  `ic_key_backspace`, `ic_key_enter`, `ic_key_globe`, `ic_key_mic`), derivados de
  Lucide (ver `THIRD_PARTY_NOTICES.md`). Se tintan en runtime vía
  `compoundDrawableTintList` con el mismo tono que el texto del resto de teclas —
  nunca a color, nunca dependientes del render de emoji de la fuente del fabricante.
- **Estados de SHIFT** (`KeyboardView.ShiftState`), pensados para ser
  inconfundibles entre sí en dispositivo real:
  - `NONE`: icono `ic_key_shift` tintado como el resto de teclas (`textPrimary`).
  - `SHIFT` (transitorio, una letra): fondo de la tecla resaltado a
    `surfaceStroke` (un paso más claro que `surface`) e icono a `accentBright`.
  - `CAPS_LOCK` (doble tap, fijo): tecla invertida — fondo `accentBright`, icono
    `ic_key_shift_caps` (con barra superior) tintado `onAccent` para mantener contraste.

### Visualizador de voz (`VoiceWaveView`)

Barras verticales (19 unidades, finas) centradas y simétricas, dibujadas con `Canvas`.
Estados (`Mode`):

- **RECORDING**: barras reactivas al nivel de audio (RMS 0..1 suavizado), color `accentBright`.
  Las barras centrales responden más (simulan ecualizador). Jitter para animar.
- **TRANSCRIBING**: barrido sinusoidal de barras, color `accentDeep`. Anima mientras procesa.

Suavizado con attack rápido / decay lento sobre el nivel crudo. El nivel se
actualiza desde el hilo de audio (`setLevel`, solo escritura de un `@Volatile
Float`, nunca invalida la vista). Antes de dibujar, `onDraw` remapea (ganancia)
el rango útil de voz normal (~0.02..0.4 del RMS normalizado) a 0..1 con
saturación, para que la onda se note con voz normal en vez de quedarse casi
plana; el suavizado attack/decay ocurre sobre el valor crudo, la ganancia se
aplica después y es puramente de render. La vista se anima automáticamente con
`postInvalidateOnAnimation`.

**Altura constante del IME**: el contenedor del teclado y `VoiceWaveView` comparten
la misma altura fija (`KeyboardView.HEIGHT_DP`, derivada del número de filas del
grid). Alternar entre teclado y onda (`WhsprInputMethodService.applyState`) solo
cambia qué vista es `VISIBLE`/`GONE`, nunca el alto del contenedor — evita que la
app de debajo dé un salto de layout al empezar o terminar el dictado.
