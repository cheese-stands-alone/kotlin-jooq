package io.rwhite226

import org.jooq.codegen.DefaultGeneratorStrategy
import org.jooq.codegen.GeneratorStrategy.Mode
import org.jooq.meta.*

class BetterNamingStrategy : DefaultGeneratorStrategy() {

    open fun getJavaClassName0(definition: Definition, mode: Mode): String = buildString {
        val words = definition.outputName
            .replace(' ', '_')
            .replace('-', '_')
            .replace('.', '_')
            .split('_')
            .map { it.capitalize() }
            .let { words ->
                if (words.all { it.isUpperCase() }) words.map { it.toLowerCase().capitalize() } else words
            }

        if (words.firstOrNull()?.firstOrNull()?.isDigit() == true) append('_')
        words.forEach { append(it) }

        when (mode) {
            Mode.RECORD -> append("Record")
            Mode.DAO -> append("Dao")
            Mode.INTERFACE -> insert(0, "I")
            Mode.POJO -> definition.database.properties.getProperty("pojo_append")?.let {
                if (it.isNotBlank()) append(it)
            }
        }
    }

    override fun getJavaClassName(definition: Definition, mode: Mode): String =
        getFixedJavaClassName(definition) ?: getJavaClassName0(definition, mode)


    override fun getJavaSetterName(definition: Definition, mode: Mode): String =
        "set${getterSetterSuffix(definition)}"


    override fun getJavaGetterName(definition: Definition, mode: Mode): String =
        "get${getterSetterSuffix(definition)}"


    open fun getterSetterSuffix(definition: Definition): String {
        if (javaBeansGettersAndSetters) {
            val name: String = getJavaMemberName(definition)
            if (Character.isUpperCase(name[0])) return name
            if (name.length > 1 && Character.isUpperCase(name[1])) return name
            val chars = name.toCharArray()
            chars[0] = Character.toUpperCase(chars[0])
            return String(chars)
        } else return getJavaClassName0(definition, Mode.DEFAULT)
    }

    open fun getFixedJavaClassName(definition: Definition?): String? =
        if (definition is CatalogDefinition && definition.isDefaultCatalog) "DefaultCatalog"
        else if (definition is SchemaDefinition && definition.isDefaultSchema) "DefaultSchema"
        else null

    override fun getJavaMethodName(definition: Definition, mode: Mode): String {
        if (definition is ForeignKeyDefinition) {
            val fk = definition
            val referenced: TableDefinition = fk.referencedTable
            if (fk.keyTable.getForeignKeys(referenced).size == 1) return getJavaMethodName(referenced, mode)
        }
        return getJavaClassName0(definition, Mode.DEFAULT).decapitalize()
    }

    override fun getJavaMemberName(definition: Definition, mode: Mode): String? =
        getJavaClassName0(definition, mode).decapitalize()
}
