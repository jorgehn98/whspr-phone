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
- **Estado visual**: shift activo se marca con `accentBright`; CAPS_LOCK igual.
- **Tipografía**: monoespaciada, sin Compose, todo con `TextView` en `LinearLayout`.
- **Colores**: superficie redondeada (`Surface` + `SurfaceStroke`), texto `TextPrimary`.

### Visualizador de voz (`VoiceWaveView`)

Barras verticales (9 unidades) centradas y simétricas, dibujadas con `Canvas`.
Estados (`Mode`):

- **RECORDING**: barras reactivas al nivel de audio (RMS 0..1 suavizado), color `accentBright`.
  Las barras centrales responden más (simulan ecualizador). Jitter para animar.
- **TRANSCRIBING**: barrido sinusoidal de barras, color `accentDeep`. Anima mientras procesa.

Suavizado con attack rápido / decay lento. El nivel se actualiza desde el hilo de
audio (volátil). La vista se anima automáticamente con `postInvalidateOnAnimation`.
Sustituye visualmente a la antigua burbuja ASCII — sin rejilla, solo barras monótonas.
