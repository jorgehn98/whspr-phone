package dev.jorgex.whspr

/** Idioma del layout del teclado (letras). Distinto del idioma de dictado de Whisper. */
enum class KeyboardLanguage { ES, EN }

/** Lado del espacio donde se coloca la tecla de punto en la fila inferior. */
enum class PeriodSide { LEFT, RIGHT }

/** Capa visible del teclado: letras o una de las dos páginas de símbolos. */
enum class KeyboardLayer { LETTERS, SYMBOLS_1, SYMBOLS_2 }

/** Tipo de acción que dispara una tecla. */
enum class KeyType {
    CHAR,
    SHIFT,
    BACKSPACE,
    LAYER_SYMBOLS,
    LAYER_ABC,
    LAYER_PAGE,
    GLOBE,
    SPACE,
    PERIOD,
    MIC,
    ENTER,
}

/**
 * Una tecla del grid. [label] es el texto mostrado (o el código de acción para teclas
 * sin texto propio). [weight] es el ancho relativo dentro de su fila (estilo Samsung:
 * la mayoría vale 1f, SHIFT/BACKSPACE/SPACE/ENTER valen más). [longPress] son las
 * variantes accesibles con pulsación larga, en orden de aparición.
 */
data class Key(
    val label: String,
    val type: KeyType,
    val weight: Float = 1f,
    val longPress: List<String> = emptyList(),
)

/** Layout completo de una capa: filas de teclas, de arriba a abajo. */
data class KeyboardLayout(val rows: List<List<Key>>)

/**
 * Layouts declarativos del teclado por idioma y capa. Datos puros; el grid de vistas
 * (KeyboardView) los renderiza sin lógica adicional.
 */
object KeyboardLayouts {

    fun layoutFor(
        language: KeyboardLanguage,
        layer: KeyboardLayer,
        periodSide: PeriodSide,
        showNumberRow: Boolean,
    ): KeyboardLayout {
        return when (layer) {
            KeyboardLayer.LETTERS -> when (language) {
                KeyboardLanguage.ES -> lettersEs(periodSide, showNumberRow)
                KeyboardLanguage.EN -> lettersEn(periodSide, showNumberRow)
            }
            // Los símbolos siempre incluyen la fila numérica, sea cual sea el
            // ajuste: showNumberRow solo afecta a las letras.
            KeyboardLayer.SYMBOLS_1 -> symbols1(periodSide)
            KeyboardLayer.SYMBOLS_2 -> symbols2(periodSide)
        }
    }

    private fun charKey(label: String, longPress: List<String> = emptyList()) =
        Key(label = label, type = KeyType.CHAR, longPress = longPress)

    private fun digitRow() = listOf(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
    ).map { charKey(it) }

    // Las teclas con icono propio (SHIFT, BACKSPACE, GLOBE, MIC, ENTER) llevan
    // label vacío: KeyboardView decide el render (icono vectorial tintado) por
    // KeyType, no por texto. contentDescription cubre la accesibilidad.
    // El punto (con long-press de coma en ambos lados) va a la izquierda o
    // derecha del espacio según AppSettings.periodSide.
    private fun bottomRow(firstLabel: String, firstType: KeyType, periodSide: PeriodSide): List<Key> {
        val first = Key(label = firstLabel, type = firstType, weight = 1.5f)
        val globe = Key(label = "", type = KeyType.GLOBE, weight = 1.5f)
        val space = Key(label = " ", type = KeyType.SPACE, weight = 4f)
        val period = Key(label = ".", type = KeyType.PERIOD, weight = 1f, longPress = listOf(","))
        val mic = Key(label = "", type = KeyType.MIC, weight = 1.5f)
        val enter = Key(label = "", type = KeyType.ENTER, weight = 1.5f)
        return when (periodSide) {
            PeriodSide.LEFT -> listOf(first, globe, period, space, mic, enter)
            PeriodSide.RIGHT -> listOf(first, globe, space, period, mic, enter)
        }
    }

    private fun lettersEs(periodSide: PeriodSide, showNumberRow: Boolean) = KeyboardLayout(
        rows = listOfNotNull(
            digitRow().takeIf { showNumberRow },
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p").map {
                when (it) {
                    "e" -> charKey(it, listOf("é", "è", "ë", "ê"))
                    "u" -> charKey(it, listOf("ú", "ù", "ü", "û"))
                    "i" -> charKey(it, listOf("í", "ì", "ï", "î"))
                    "o" -> charKey(it, listOf("ó", "ò", "ö", "ô", "õ"))
                    else -> charKey(it)
                }
            },
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "ñ").map {
                when (it) {
                    "a" -> charKey(it, listOf("á", "à", "ä", "â", "ã"))
                    "c" -> charKey(it, listOf("ç"))
                    else -> charKey(it)
                }
            },
            listOf(Key(label = "", type = KeyType.SHIFT, weight = 1.5f)) +
                listOf("z", "x", "c", "v", "b", "n", "m").map {
                    if (it == "c") charKey(it, listOf("ç")) else charKey(it)
                } +
                listOf(Key(label = "", type = KeyType.BACKSPACE, weight = 1.5f)),
            bottomRow(firstLabel = "!#1", firstType = KeyType.LAYER_SYMBOLS, periodSide = periodSide),
        ),
    )

    private fun lettersEn(periodSide: PeriodSide, showNumberRow: Boolean) = KeyboardLayout(
        rows = listOfNotNull(
            digitRow().takeIf { showNumberRow },
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p").map {
                when (it) {
                    "e" -> charKey(it, listOf("é", "è", "ë", "ê"))
                    "u" -> charKey(it, listOf("ú", "ù", "ü", "û"))
                    "i" -> charKey(it, listOf("í", "ì", "ï", "î"))
                    "o" -> charKey(it, listOf("ó", "ò", "ö", "ô", "õ"))
                    else -> charKey(it)
                }
            },
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l").map {
                when (it) {
                    "a" -> charKey(it, listOf("á", "à", "ä", "â", "ã"))
                    else -> charKey(it)
                }
            },
            listOf(Key(label = "", type = KeyType.SHIFT, weight = 1.5f)) +
                listOf("z", "x", "c", "v", "b", "n", "m").map {
                    when (it) {
                        "c" -> charKey(it, listOf("ç"))
                        "n" -> charKey(it, listOf("ñ"))
                        else -> charKey(it)
                    }
                } +
                listOf(Key(label = "", type = KeyType.BACKSPACE, weight = 1.5f)),
            bottomRow(firstLabel = "!#1", firstType = KeyType.LAYER_SYMBOLS, periodSide = periodSide),
        ),
    )

    private fun symbols1(periodSide: PeriodSide) = KeyboardLayout(
        rows = listOf(
            digitRow(),
            listOf("+", "×", "÷", "=", "/", "_", "<", ">", "[", "]").map { charKey(it) },
            listOf("!", "@", "#", "€", "%", "^", "&", "*", "(", ")").map { charKey(it) },
            listOf(Key(label = "1/2", type = KeyType.LAYER_PAGE, weight = 1.5f)) +
                listOf("-", "'", "\"", ":", ";", ",", "?").map { charKey(it) } +
                listOf(Key(label = "", type = KeyType.BACKSPACE, weight = 1.5f)),
            bottomRow(firstLabel = "ABC", firstType = KeyType.LAYER_ABC, periodSide = periodSide),
        ),
    )

    private fun symbols2(periodSide: PeriodSide) = KeyboardLayout(
        rows = listOf(
            digitRow(),
            listOf("`", "~", "\\", "|", "{", "}", "$", "£", "¥", "₩").map { charKey(it) },
            listOf("°", "•", "○", "●", "□", "■", "♤", "♡", "◇", "♧")
                .map { charKey(it) },
            listOf(Key(label = "2/2", type = KeyType.LAYER_PAGE, weight = 1.5f)) +
                listOf("☆", "▪", "¤", "《", "》", "¡", "¿").map { charKey(it) } +
                listOf(Key(label = "", type = KeyType.BACKSPACE, weight = 1.5f)),
            bottomRow(firstLabel = "ABC", firstType = KeyType.LAYER_ABC, periodSide = periodSide),
        ),
    )
}
