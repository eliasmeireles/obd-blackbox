package io.github.eliasmeireles.obdblackbox.i18n

/** Installed by the application; JVM tests install a resolver backed by the same XML resources. */
object TextResources {
    @Volatile var locale: () -> java.util.Locale = java.util.Locale::getDefault
    @Volatile var quantity: (Int, Int, Array<out Any>) -> String = { id, _, _ -> error("Plural resources not installed: $id") }
    @Volatile var resolve: (Int, Array<out Any>) -> String = { id, _ -> error("Text resources not installed: $id") }
}

fun text(id: Int, vararg args: Any): String = TextResources.resolve(id, args)

fun quantity(id: Int, count: Int, vararg args: Any): String = TextResources.quantity(id, count, args)
