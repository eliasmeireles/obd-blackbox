package io.github.eliasmeireles.obdblackbox

import io.github.eliasmeireles.obdblackbox.i18n.TextResources
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** Real translations, without an Android runtime or duplicated message fixtures. */
open class ResourceTest {
    init { TestStrings.install("pt-BR") }
}

object TestStrings {
    fun install(tag: String) {
        val folder = if (tag == "pt-BR") "values-pt-rBR" else "values"
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val stream = checkNotNull(javaClass.classLoader!!.getResourceAsStream("$folder/strings.xml"))
        val document = stream.use { factory.newDocumentBuilder().parse(it) }
        val fields = R.string::class.java.fields.filter { it.type == Int::class.javaPrimitiveType }.associate { it.name to it.getInt(null) }
        val strings = document.getElementsByTagName("string")
        val values = (0 until strings.length).associate { index ->
            val node = strings.item(index)
            fields.getValue(node.attributes.getNamedItem("name").nodeValue) to decode(node.textContent)
        }
        val plurals = document.getElementsByTagName("plurals")
        val quantities = (0 until plurals.length).associate { index ->
            val node = plurals.item(index)
            val id = R.plurals::class.java.getField(node.attributes.getNamedItem("name").nodeValue).getInt(null)
            val entries = node.childNodes
            id to (0 until entries.length).map { entries.item(it) }.filter { it.nodeName == "item" }
                .associate { it.attributes.getNamedItem("quantity").nodeValue to decode(it.textContent) }
        }
        val locale = Locale.forLanguageTag(tag)
        TextResources.locale = { locale }
        TextResources.resolve = { id, args ->
            val value = values.getValue(id)
            if (args.isEmpty()) value else String.format(locale, value, *args)
        }
        TextResources.quantity = { id, count, args ->
            val forms = quantities.getValue(id)
            String.format(locale, forms.getValue(if (count == 1) "one" else "other"), *args)
        }
    }

    private fun decode(value: String): String = value.removeSurrounding("\"")
        .replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
}
