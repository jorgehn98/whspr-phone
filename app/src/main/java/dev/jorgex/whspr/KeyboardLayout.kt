package dev.jorgex.whspr

/** Idioma del layout del teclado (letras). Distinto del idioma de dictado de Whisper. */
enum class KeyboardLanguage { ES, EN }

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
 * (tarea 03) los renderiza sin lógica adicional.
 */
object KeyboardLayouts {

    fun layoutFor(language: KeyboardLanguage, layer: KeyboardLayer): KeyboardLayout {
        return when (layer) {
            KeyboardLayer.LETTERS -> when (language) {
                KeyboardLanguage.ES -> lettersEs
                KeyboardLanguage.EN -> lettersEn
            }
            KeyboardLayer.SYMBOLS_1 -> symbols1
            KeyboardLayer.SYMBOLS_2 -> symbols2
        }
    }

    private fun charKey(label: String, longPress: List<String> = emptyList()) =
        Key(label = label, type = KeyType.CHAR, longPress = longPress)

    private fun digitRow() = listOf(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
    ).map { charKey(it) }

    // U+FE0E (selector de variación de texto) fuerza render monocromo en las
    // teclas de UI donde la fuente lo soporte (DESIGN.md exige monocromo, sin
    // color de acento). Solo en glifos de UI: los caracteres de contenido de
    // SYMBOLS_2 se insertan tal cual y no llevan selector.
    private const val TEXT_PRESENTATION = "︎"

    private fun bottomRow(firstLabel: String, firstType: KeyType) = listOf(
        Key(label = firstLabel, type = firstType, weight = 1.5f),
        Key(label = "🌐$TEXT_PRESENTATION", type = KeyType.GLOBE, weight = 1.5f),
        Key(label = " ", type = KeyType.SPACE, weight = 4f),
        Key(label = ".", type = KeyType.PERIOD, weight = 1f, longPress = listOf(",")),
        Key(label = "🎙$TEXT_PRESENTATION", type = KeyType.MIC, weight = 1.5f),
        Key(label = "⏎", type = KeyType.ENTER, weight = 1.5f),
    )

    private val lettersEs = KeyboardLayout(
        rows = listOf(
            digitRow(),
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
            listOf(Key(label = "⇧", type = KeyType.SHIFT, weight = 1.5f)) +
                listOf("z", "x", "c", "v", "b", "n", "m").map {
                    if (it == "c") charKey(it, listOf("ç")) else charKey(it)
                } +
                listOf(Key(label = "⌫", type = KeyType.BACKSPACE, weight = 1.5f)),
            bottomRow(firstLabel = "!#1", firstType = KeyType.LAYER_SYMBOLS),
        ),
    )

    private val lettersEn = KeyboardLayout(
        rows = listOf(
            digitRow(),
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
            listOf(Key(label = "⇧", type = KeyType.SHIFT, weight = 1.5f)) +
                listOf("z", "x", "c", "v", "b", "n", "m").map {
                    when (it) {
                        "c" -> charKey(it, listOf("ç"))
                        "n" -> charKey(it, listOf("ñ"))
                        else -> charKey(it)
                    }
                } +
                listOf(Key(label = "⌫", type = KeyType.BACKSPACE, weight = 1.5f)),
            bottomRow(firstLabel = "!#1", firstType = KeyType.LAYER_SYMBOLS),
        ),
    )

    private val symbols1 = KeyboardLayout(
        rows = listOf(
            digitRow(),
            listOf("+", "×", "÷", "=", "/", "_", "<", ">", "[", "]").map { charKey(it) },
            listOf("!", "@", "#", "€", "%", "^", "&", "*", "(", ")").map { charKey(it) },
            listOf(Key(label = "[1/2]", type = KeyType.LAYER_PAGE, weight = 1.5f)) +
                listOf("-", "'", "\"", ":", ";", ",", "?").map { charKey(it) } +
                listOf(Key(label = "⌫", type = KeyType.BACKSPACE, weight = 1.5f)),
            bottomRow(firstLabel = "ABC", firstType = KeyType.LAYER_ABC),
        ),
    )

    private val symbols2 = KeyboardLayout(
        rows = listOf(
            digitRow(),
            listOf("`", "~", "\\", "|", "{", "}", "$", "£", "¥", "₩").map { charKey(it) },
            listOf("°", "•", "○", "●", "□", "■", "♤", "♡", "◇", "♧")
                .map { charKey(it) },
            listOf(Key(label = "[2/2]", type = KeyType.LAYER_PAGE, weight = 1.5f)) +
                listOf("☆", "▪", "¤", "《", "》", "¡", "¿").map { charKey(it) } +
                listOf(Key(label = "⌫", type = KeyType.BACKSPACE, weight = 1.5f)),
            bottomRow(firstLabel = "ABC", firstType = KeyType.LAYER_ABC),
        ),
    )
}
