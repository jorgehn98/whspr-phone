package dev.jorgex.whspr

import java.text.Collator
import java.util.Locale

/** Un idioma seleccionable: código Whisper + nombre en español. */
data class Language(val code: String, val name: String)

/**
 * Idiomas que soportan los modelos Whisper multilingües (los dos del catálogo).
 * La cobertura es la misma para ambos; solo cambia la precisión, no los idiomas.
 */
object Languages {
    const val AUTO = "auto"

    private val auto = Language(AUTO, "Auto (detectar idioma)")

    private val languages = listOf(
        Language("es", "Español"),
        Language("en", "Inglés"),
        Language("zh", "Chino"),
        Language("de", "Alemán"),
        Language("ru", "Ruso"),
        Language("ko", "Coreano"),
        Language("fr", "Francés"),
        Language("ja", "Japonés"),
        Language("pt", "Portugués"),
        Language("tr", "Turco"),
        Language("pl", "Polaco"),
        Language("ca", "Catalán"),
        Language("nl", "Neerlandés"),
        Language("ar", "Árabe"),
        Language("sv", "Sueco"),
        Language("it", "Italiano"),
        Language("id", "Indonesio"),
        Language("hi", "Hindi"),
        Language("fi", "Finés"),
        Language("vi", "Vietnamita"),
        Language("he", "Hebreo"),
        Language("uk", "Ucraniano"),
        Language("el", "Griego"),
        Language("ms", "Malayo"),
        Language("cs", "Checo"),
        Language("ro", "Rumano"),
        Language("da", "Danés"),
        Language("hu", "Húngaro"),
        Language("ta", "Tamil"),
        Language("no", "Noruego"),
        Language("th", "Tailandés"),
        Language("ur", "Urdu"),
        Language("hr", "Croata"),
        Language("bg", "Búlgaro"),
        Language("lt", "Lituano"),
        Language("la", "Latín"),
        Language("mi", "Maorí"),
        Language("ml", "Malabar"),
        Language("cy", "Galés"),
        Language("sk", "Eslovaco"),
        Language("te", "Telugu"),
        Language("fa", "Persa"),
        Language("lv", "Letón"),
        Language("bn", "Bengalí"),
        Language("sr", "Serbio"),
        Language("az", "Azerbaiyano"),
        Language("sl", "Esloveno"),
        Language("kn", "Canarés"),
        Language("et", "Estonio"),
        Language("mk", "Macedonio"),
        Language("br", "Bretón"),
        Language("eu", "Euskera"),
        Language("is", "Islandés"),
        Language("hy", "Armenio"),
        Language("ne", "Nepalí"),
        Language("mn", "Mongol"),
        Language("bs", "Bosnio"),
        Language("kk", "Kazajo"),
        Language("sq", "Albanés"),
        Language("sw", "Suajili"),
        Language("gl", "Gallego"),
        Language("mr", "Maratí"),
        Language("pa", "Panyabí"),
        Language("si", "Cingalés"),
        Language("km", "Jemer"),
        Language("sn", "Shona"),
        Language("yo", "Yoruba"),
        Language("so", "Somalí"),
        Language("af", "Afrikáans"),
        Language("oc", "Occitano"),
        Language("ka", "Georgiano"),
        Language("be", "Bielorruso"),
        Language("tg", "Tayiko"),
        Language("sd", "Sindhi"),
        Language("gu", "Guyaratí"),
        Language("am", "Amárico"),
        Language("yi", "Yidis"),
        Language("lo", "Lao"),
        Language("uz", "Uzbeko"),
        Language("fo", "Feroés"),
        Language("ht", "Criollo haitiano"),
        Language("ps", "Pastún"),
        Language("tk", "Turcomano"),
        Language("nn", "Nynorsk (noruego)"),
        Language("mt", "Maltés"),
        Language("sa", "Sánscrito"),
        Language("lb", "Luxemburgués"),
        Language("my", "Birmano"),
        Language("bo", "Tibetano"),
        Language("tl", "Tagalo"),
        Language("mg", "Malgache"),
        Language("as", "Asamés"),
        Language("tt", "Tártaro"),
        Language("haw", "Hawaiano"),
        Language("ln", "Lingala"),
        Language("ha", "Hausa"),
        Language("ba", "Baskir"),
        Language("jw", "Javanés"),
        Language("su", "Sundanés"),
    )

    /** Auto primero, luego los idiomas ordenados alfabéticamente (locale es). */
    val all: List<Language> = run {
        val collator = Collator.getInstance(Locale("es"))
        listOf(auto) + languages.sortedWith { a, b -> collator.compare(a.name, b.name) }
    }

    fun isValid(code: String): Boolean = all.any { it.code == code }

    fun nameFor(code: String): String =
        all.firstOrNull { it.code == code }?.name ?: languages.first().name
}
