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

### Burbuja del micro (`BubbleMicView`)

Orbe de **ASCII art** dibujado con `Canvas`: una rejilla de caracteres
monoespaciados (` .:-=+*#%@`) que ondula. El centro brilla con el acento y los
bordes se atenúan. Estados (`Mode`):

- **IDLE**: ondulación lenta, brillo tenue. Ámbar `accent`.
- **LISTENING**: ondas rápidas y amplias, núcleo brillante. `accentBright`.
- **PROCESSING**: parpadeo/escaneo rápido. `accentDeep`.
- **DISABLED**: gris `disabled`, estático (campos de contraseña).

Animación por tiempo (no reactiva al audio). El IME fija el estado con `setMode`.
Sin texto "toca para dictar": la afordancia es obvia. La etiqueta inferior solo
aparece para "Transcribiendo…" y campos no permitidos.

### Teclas (Espacio / Borrar / Intro / Siguiente)

Superficie redondeada (`Surface` + borde `SurfaceStroke`), texto `TextPrimary`,
ripple ámbar. Altura cómoda y separadas del borde inferior para no chocar con los
botones del sistema (cambio de teclado / ocultar).
