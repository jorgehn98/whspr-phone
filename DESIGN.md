# Whspr — Diseño

Sistema de diseño mínimo de Whspr. Léelo antes de tocar UI. Tokens en
[`Theme.kt`](app/src/main/java/dev/jorgex/whspr/Theme.kt) (`WhsprColors`).

## Identidad

Tecnológica, sobria, tipo HUD/reactor (referencia: interfaces de Iron Man).
**Nada de azul/violeta.** Paleta cálida ámbar/oro sobre carbón.

## Paleta (ARGB)

| Token | Hex | Uso |
| --- | --- | --- |
| `Background` | `#0E0E10` | Fondo base (carbón) |
| `BackgroundTop` | `#15151A` | Degradado superior del fondo |
| `Surface` | `#1A1A20` | Botones, tarjetas |
| `SurfaceStroke` | `#2C2C34` | Bordes de superficie |
| `Accent` | `#FFB020` | Ámbar/oro principal |
| `AccentBright` | `#FFC861` | Ámbar brillante (escuchando) |
| `AccentDeep` | `#FF7A1A` | Naranja (procesando) |
| `AccentMuted` | `#6E5320` | Ámbar apagado (estructura HUD) |
| `Glow` | `#FFE3A6` | Brillo cálido (núcleo, glow) |
| `TextPrimary` | `#F5F0E6` | Texto principal (crema) |
| `TextMuted` | `#9A958C` | Texto secundario |
| `Disabled` | `#4A4A52` | Estados deshabilitados |

## Reglas

- Los colores se definen **solo** en `WhsprColors`. No hardcodear hex en las vistas.
- Fondo siempre oscuro; acento ámbar reservado para lo accionable y lo "vivo".
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
