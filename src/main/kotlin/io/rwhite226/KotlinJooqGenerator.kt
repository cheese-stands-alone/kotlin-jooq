package io.rwhite226

import com.squareup.kotlinpoet.*
import org.jooq.Constants
import org.jooq.SortOrder
import org.jooq.codegen.GeneratorStrategy
import org.jooq.codegen.GeneratorStrategy.Mode
import org.jooq.codegen.JavaGenerator
import org.jooq.codegen.JavaWriter
import org.jooq.meta.ColumnDefinition
import org.jooq.meta.Database
import org.jooq.meta.Definition
import org.jooq.meta.TableDefinition
import java.io.File
import java.util.*
import javax.xml.bind.DatatypeConverter

open class KotlinJooqGenerator : JavaGenerator() {

    companion object {
        val uniqueConstraintAnnotation = ClassName.bestGuess("javax.persistence.UniqueConstraint")
            .copy(nullable = false)
        val indexAnnotation = ClassName.bestGuess("javax.persistence.Index")
            .copy(nullable = false)
        val digitsAnnotation = ClassName.bestGuess("javax.validation.constraints.Digits")
            .copy(nullable = false)
        val notNullAnnotation = ClassName.bestGuess("javax.validation.constraints.NotNull")
            .copy(nullable = false)
        val sizeAnnotation = ClassName.bestGuess("javax.validation.constraints.Size")
            .copy(nullable = false)
        val generatedAnnotation = ClassName.bestGuess("javax.annotation.Generated")
            .copy(nullable = false)
        val entityAnnotation = ClassName.bestGuess("javax.persistence.Entity")
            .copy(nullable = false)
        val tableAnnotation = ClassName.bestGuess("javax.persistence.Table")
            .copy(nullable = false)
        val introspectedAnnotation = ClassName.bestGuess("io.micronaut.core.annotation.Introspected")
            .copy(nullable = false)
        val columnAnnotation = ClassName.bestGuess("javax.persistence.Column")
            .copy(nullable = false)
        val idAnnotation = ClassName.bestGuess("javax.persistence.Id")
            .copy(nullable = false)
        val generatedValueAnnotation = ClassName.bestGuess("javax.persistence.GeneratedValue")
            .copy(nullable = false)
        val identityType = ClassName.bestGuess("javax.persistence.GenerationType.IDENTITY")
            .copy(nullable = false)
    }

    val db: Database by lazy {
        JavaGenerator::class.java.getDeclaredField("database").apply {
            isAccessible = true
        }.get(this) as Database
    }

    // Same thing as isoDate in superclass but that is private
    open val date: String = DatatypeConverter.printDateTime(Calendar.getInstance(TimeZone.getTimeZone("UTC")))

    open val micronaut by lazy { db.properties.getProperty("micronaut") == "true" }
    open val dataClasses by lazy { db.properties.getProperty("dataclasses") == "true" }
    open val copy by lazy { db.properties.getProperty("copy") == "true" }
    open val destructuring by lazy { db.properties.getProperty("destructuring") == "true" }
    open val introspected by lazy { db.properties.getProperty("introspected") == "true" }

    /**
     *  overrideing the strategy to get around issues with how kotlin deals with getter and setters for paramaters that
     *  start with "is"
     */
    private var strategyInstance: GeneratorStrategy? = null

    override fun getStrategy(): GeneratorStrategy {
        strategyInstance?.let { return it }
        val actual = super.getStrategy()
        val strat = object : GeneratorStrategy by actual {

            fun checkName(name: String?): String? {
                if (
                    name != null &&
                    name.length > 6 &&
                    name[3] == 'I' &&
                    name[4] == 's' &&
                    name[5].isUpperCase()
                ) {
                    return if (name.first() == 'g') name.removePrefix("get").decapitalize()
                    else name.removeRange(3, 5)
                }
                return name
            }

            override fun getJavaGetterName(definition: Definition, mode: Mode): String? =
                checkName(actual.getJavaGetterName(definition, mode))


            override fun getJavaSetterName(definition: Definition, mode: Mode): String? =
                checkName(actual.getJavaSetterName(definition, mode))
        }
        strategyInstance = strat
        return strat
    }

    override fun generatePojo(table: TableDefinition, out: JavaWriter) {
        val pojoFile = out.file().parentFile.resolve(out.file().nameWithoutExtension + ".kt")
        val mode = Mode.POJO
        val fileSpec = FileSpec.builder(
            packageName = getStrategy().getJavaPackageName(table, mode),
            fileName = pojoFile.path
        )
        val superClass = out.ref(getStrategy().getJavaClassExtends(table, mode))?.let {
            if (it.isNotBlank()) it.toClassName().copy(nullable = false) else null
        }
        val pojoType = getStrategy().getJavaClassName(table, mode).toClassName().copy(nullable = false)
        val columns = table.columns.filterNotNull()
        val isDataClass = dataClasses && columns.size < 255
        val interfaceType = getStrategy().getFullJavaClassName(table, Mode.INTERFACE)?.let {
            if (it.isNotBlank() && generateInterfaces()) it.toClassName().copy(nullable = false) else null
        }
        fileSpec.addType(
            TypeSpec.classBuilder(pojoType)
                .apply {
                    if (superClass != null) superclass(superClass)
                    if (generateInterfaces() && interfaceType != null) addSuperinterface(interfaceType)
                    if (columns.size > 255) {
                        addModifiers(KModifier.OPEN)
                        if (generateImmutablePojos()) {
                            if (interfaceType == null) throw Exception(
                                "Immutable pojo generated with more than 255 columns must have an interface"
                            )
                            primaryConstructor(
                                FunSpec.constructorBuilder().addParameter("value", interfaceType).build()
                            )
                        } else {
                            primaryConstructor(FunSpec.constructorBuilder().build())
                            if (interfaceType != null) addFunction(
                                FunSpec.constructorBuilder()
                                    .addParameter("value", interfaceType)
                                    .apply {
                                        columns.forEach {
                                            val name = it.getPropertyName()
                                            addStatement("$name = value.$name")
                                        }
                                    }
                                    .callThisConstructor()
                                    .build()
                            )
                        }
                    } else {
                        if (isDataClass) addModifiers(KModifier.DATA)
                        else addModifiers(KModifier.OPEN)
                        primaryConstructor(
                            FunSpec.constructorBuilder()
                                .addParameters(
                                    columns.map { columnDefinition ->
                                        ParameterSpec.builder(
                                            name = columnDefinition.getPropertyName(),
                                            type = getJavaType(columnDefinition.getType(resolver(Mode.POJO))).toClassName()
                                        )
                                            .defaultValue("null")
                                            .build()
                                    }
                                ).build()
                        )
                        if (interfaceType != null) addFunction(
                            FunSpec.constructorBuilder()
                                .addParameter("value", interfaceType)
                                .apply {
                                    if (columns.size < 255) callThisConstructor(
                                        *columns.map { "value." + it.getPropertyName() }.toTypedArray()
                                    )
                                    else columns.forEach {
                                        val name = it.getPropertyName()
                                        addStatement("$name = value.$name")
                                    }
                                }.build()
                        )
                    }

                    if (!generateImmutableInterfaces() && interfaceType != null) {
                        addFunction(
                            buildFrom(interfaceType)
                                .addModifiers(KModifier.OVERRIDE)
                                .apply {
                                    columns.forEach {
                                        val name = it.getPropertyName()
                                        addStatement("$name = from.$name\n")
                                    }
                                }
                                .build()
                        )
                        addFunction(
                            buildInto(interfaceType)
                                .addModifiers(KModifier.OVERRIDE)
                                .addStatement("into.from(this)")
                                .addStatement("return into")
                                .build()
                        )
                    }

                    if (!isDataClass && generatePojosEqualsAndHashCode()) {
                        addFunction(buildHashcode(columns).build())
                        addFunction(buildEquals(pojoType.simpleName, columns).build())
                    }
                    if (!isDataClass && generatePojosToString())
                        addFunction(buildToString(pojoType.simpleName, columns).build())

                    if (copy && !isDataClass) buildCopyFun(columns, pojoType, interfaceType)
                        .forEach { addFunction(it.build()) }

                    if (destructuring) buildDestructuring(columns).forEach { addFunction(it.build()) }
                }
                .addSuperinterfaces(
                    getStrategy().getJavaClassImplements(table, mode).filter { !it.isNullOrBlank() }.map {
                        it.toClassName().copy(nullable = false)
                    }
                )
                .addProperties(
                    table.columns.filterNotNull().map { columnDefinition ->
                        val name = columnDefinition.getPropertyName()
                        PropertySpec.builder(
                            name = name,
                            type = getJavaType(columnDefinition.getType(resolver(mode))).toClassName()
                        )
                            .mutable(!generateImmutablePojos())
                            .apply {
                                if (generateInterfaces()) addModifiers(KModifier.OVERRIDE)
                                if (columns.size < 255) initializer(name)
                                else if (generateImmutablePojos() && interfaceType != null) initializer("value.$name")
                                else initializer("null")
                            }
                            .addKdoc(columnDefinition)
                            .addAnnotations(getPropertyAnnotations(columnDefinition, mode))
                            .build()
                    }
                )
                .addAnnotations(getClassAnnotations(table, fileSpec))
                .build()
        )
        pojoFile.writer().buffered().use { it.write(fileSpec.build().toString().replace("``", "`")) }
    }

    override fun generateInterface(table: TableDefinition, out: JavaWriter) {
        val interfaceFile = out.file().parentFile.resolve(out.file().nameWithoutExtension + ".kt")
        val mode = Mode.INTERFACE
        val fileSpec = FileSpec.builder(
            packageName = getStrategy().getJavaPackageName(table, mode),
            fileName = interfaceFile.path
        )
        val interfaceType = getStrategy().getJavaClassName(table, mode).toClassName().copy(nullable = false)
        fileSpec.addType(
            TypeSpec.interfaceBuilder(interfaceType)
                .addSuperinterfaces(
                    getStrategy().getJavaClassImplements(table, mode)
                        .filter { !it.isNullOrBlank() }
                        .map { it.toClassName().copy(nullable = false) }
                )
                .addProperties(table.columns.filterNotNull().map { columnDefinition ->
                    PropertySpec.builder(
                        name = columnDefinition.getPropertyName(),
                        type = getJavaType(columnDefinition.getType(resolver(mode))).toClassName()
                    )
                        .mutable(!generateImmutableInterfaces())
                        .addKdoc(columnDefinition)
                        .addAnnotations(getPropertyAnnotations(columnDefinition, mode))
                        .build()
                })
                .apply {
                    if (!generateImmutableInterfaces()) {
                        addFunction(buildFrom(interfaceType).addModifiers(KModifier.ABSTRACT).build())
                        addFunction(buildInto(interfaceType).addModifiers(KModifier.ABSTRACT).build())
                    }
                }
                .addAnnotations(getClassAnnotations(table, fileSpec))
                .build()
        )
        interfaceFile.writer().buffered().use { it.write(fileSpec.build().toString().replace("``", "`")) }
    }

    open fun String.toClassName(): ClassName = when {
        startsWith("java.lang.") -> Class.forName(this).kotlin.asClassName() // use kotlin version of primitives
        startsWith("java.") -> Class.forName(this).asClassName()
        this == "boolean[]" -> BooleanArray::class.asClassName()
        this == "byte[]" -> ByteArray::class.asClassName()
        this == "short[]" -> ShortArray::class.asClassName()
        this == "int[]" -> IntArray::class.asClassName()
        this == "long[]" -> LongArray::class.asClassName()
        this == "float[]" -> FloatArray::class.asClassName()
        this == "double[]" -> DoubleArray::class.asClassName()
        else -> ClassName.bestGuess(this)
    }.copy(nullable = true)

    open fun getClassAnnotations(table: TableDefinition, fileSpec: FileSpec.Builder): List<AnnotationSpec> {
        val clazz = this::class.qualifiedName
        val annotations = mutableListOf<AnnotationSpec>()
        if (generateGeneratedAnnotation()) {
            annotations += AnnotationSpec.builder(generatedAnnotation)
                .apply {
                    addMember(
                        "value = %L",
                        mutableListOf<String>().apply {
                            add(""""jOOQ version:${Constants.VERSION}"""")
                            if (useSchemaVersionProvider() || useCatalogVersionProvider()) {
                                val catalogVersion =
                                    table.catalog.database.catalogVersionProvider.version(table.catalog)
                                if (!catalogVersion.isNullOrBlank()) {
                                    table.catalog.database.catalogVersionProvider.version(table.catalog)
                                    add("catalog version:$catalogVersion")
                                }
                                val schemaVersion = table.schema.database.schemaVersionProvider.version(table.schema)
                                if (!schemaVersion.isNullOrBlank()) {
                                    add("schema version:$schemaVersion")
                                }
                            }
                        }.joinToString(prefix = "[", postfix = "]", separator = ", ")
                    )
                }
                .addMember("date = %S", date)
                .addMember("comments = %S", "This class is generated by $clazz")
                .build()
        }
        if (generateJPAAnnotations()) {
            annotations += AnnotationSpec.builder(entityAnnotation).build()
            annotations += AnnotationSpec.builder(tableAnnotation)
                .addMember("name = %S", table.name)
                .apply {
                    if (!table.schema.isDefaultSchema) addMember("schema = %S", table.schema.name)

                    val uniqueKeys = table.uniqueKeys.filter { it.keyColumns.size > 1 }.map { uk ->
                        fileSpec.addImport(
                            "javax.persistence",
                            "UniqueConstraint"
                        )
                        AnnotationSpec.builder(uniqueConstraintAnnotation)
                            .addMember(
                                uk.keyColumns.joinToString(
                                    separator = ",\n",
                                    prefix = "columnNames = [\n⇥",
                                    postfix = "⇤\n]"
                                ) { "%S" },
                                *uk.keyColumns.map { it.name }.toTypedArray()
                            )
                            .build()
                            .toString()
                            .removePrefix("@javax.persistence.")
                    }.toTypedArray()

                    if (uniqueKeys.isNotEmpty()) {
                        addMember(
                            uniqueKeys.joinToString(
                                separator = ",\n",
                                prefix = "uniqueConstraints = [\n⇥",
                                postfix = "⇤\n]"
                            ) { "%L" },
                            *uniqueKeys
                        )
                    }

                    if (generateJPAVersion().isBlank() || "2.1" <= generateJPAVersion()) {
                        val indexes = table.indexes.map { index ->
                            fileSpec.addImport(
                                "javax.persistence",
                                "Index"
                            )
                            AnnotationSpec.builder(indexAnnotation)
                                .addMember("\nname = %S", index.outputName)
                                .addMember("\nunique = %L", index.isUnique)
                                .apply {
                                    val columnList = index.indexColumns.map {
                                        index.outputName + when {
                                            it.sortOrder == SortOrder.ASC -> " ASC"
                                            it.sortOrder == SortOrder.DESC -> " DESC"
                                            else -> ""
                                        }
                                    }.toTypedArray()
                                    if (columnList.isNotEmpty()) addMember(
                                        columnList.joinToString(
                                            separator = " +\n",
                                            prefix = "\ncolumnList = \n⇥",
                                            postfix = "⇤\n"
                                        ) { "%S" },
                                        *columnList
                                    )
                                }
                                .build()
                                .toString()
                                .removePrefix("@javax.persistence.")
                        }.toTypedArray()
                        if (indexes.isNotEmpty()) {
                            addMember(
                                indexes.joinToString(
                                    separator = ",\n",
                                    prefix = "indexes = [\n⇥",
                                    postfix = "⇤\n]"
                                ) { "%L" },
                                *indexes
                            )
                        }
                    }
                }
                .build()
        }
        if (table.database.properties.getProperty("micronaut") == "true" && introspected) {
            annotations.add(AnnotationSpec.builder(introspectedAnnotation).build())
        }
        return annotations
    }

    open fun getPropertyAnnotations(
        columnDefinition: ColumnDefinition,
        mode: Mode
    ): List<AnnotationSpec> {
        val annotations = mutableListOf<AnnotationSpec>()
        val propType = getJavaType(columnDefinition.getType(resolver(mode))).toClassName()
        val type = columnDefinition.getType(resolver())
        if (generateValidationAnnotations()) {
            if (!type.isNullable &&
                !type.isDefaulted &&
                !type.isIdentity
            ) annotations += AnnotationSpec.builder(notNullAnnotation)
                .useSiteTarget(AnnotationSpec.UseSiteTarget.GET)
                .build()

            if (propType.simpleName == "String") {
                val length = type.length
                if (length > 0) annotations += AnnotationSpec.builder(sizeAnnotation)
                    .addMember("max = %L", length)
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.GET)
                    .build()

            } else if (propType.simpleName == "BigDecimal" || propType.simpleName == "BigInteger") {
                val fraction = type.scale
                val integer = type.precision - fraction
                annotations += AnnotationSpec.builder(digitsAnnotation)
                    .addMember("integer = %L", integer)
                    .addMember("fraction = %L", fraction)
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.GET)
                    .build()
            }

            val pk = columnDefinition.primaryKey
            if (pk != null && pk.keyColumns.size == 1) {
                annotations += AnnotationSpec.builder(idAnnotation)
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.GET)
                    .build()
                if (pk.keyColumns.first()?.isIdentity == true)
                    annotations += AnnotationSpec.builder(generatedValueAnnotation)
                        .useSiteTarget(AnnotationSpec.UseSiteTarget.GET)
                        .addMember("strategy = %T", identityType)
                        .build()
            }

            annotations += AnnotationSpec.builder(columnAnnotation)
                .useSiteTarget(AnnotationSpec.UseSiteTarget.GET)
                .apply {
                    val length = type.length
                    val precision = type.precision
                    if (length > 0) addMember("length = %L", length)
                    else if (precision > 0) {
                        addMember("precision = %L", precision)
                        val scale = type.scale
                        if (scale > 0) addMember("scale  = %L", scale)
                    }
                    addMember("nullable = %L", type.isNullable)
                    if (columnDefinition.uniqueKeys.firstOrNull()?.keyColumns?.size == 1)
                        addMember("unique = %L", true)
                    addMember("name = %S", columnDefinition.name)
                }
                .build()
        }
        return annotations
    }

    open fun ColumnDefinition.getPropertyName(): String {
        val name = getStrategy().getJavaMemberName(this)
        // backtick quote name that contain the $
        if (name.contains("$")) return "`$name`"
        return name
    }

    open fun PropertySpec.Builder.addKdoc(columnDefinition: ColumnDefinition) = addKdoc(
        "Field for [%L]. %L",
        // backtick quote name that contain the $ or starts with a number
        columnDefinition.qualifiedOutputName
            ?.split(".")
            ?.joinToString(separator = ".") {
                if (it.contains("$") || it.first().isDigit()) "`$it`"
                else it
            } ?: "",
        columnDefinition.comment ?: ""
    )

    open fun buildToString(className: String, columns: List<ColumnDefinition>) =
        FunSpec.builder("toString")
            .addModifiers(KModifier.OVERRIDE)
            .beginControlFlow("return buildString {")
            .addStatement("""append("$className(")""")
            .apply {
                if (columns.size > 1) columns.take(columns.size - 1).forEach {
                    val name = it.getPropertyName()
                    addStatement("""append("${name.replace("\$", "\\\$").removePrefix("`").removeSuffix("`")}=")""")
                    addStatement("append($name)")
                    addStatement("""append(", ")""")
                }
                columns.last().let {
                    val name = it.getPropertyName()
                    addStatement("""append("${name.replace("\$", "\\\$").removePrefix("`").removeSuffix("`")}=")""")
                    addStatement("append($name)")
                }
            }
            .addStatement("""append(")")""")
            .endControlFlow()
            .returns(String::class)

    open fun buildHashcode(columns: List<ColumnDefinition>) =
        FunSpec.builder("hashCode")
            .addModifiers(KModifier.OVERRIDE)
            .returns(Int::class)
            .addStatement("val prime = 31")
            .addStatement("var result = 1")
            .apply {
                columns.forEach {
                    val name = it.getPropertyName()
                    addStatement(
                        "result = prime * result + " +
                                if (it.type.isArray) "java.util.Arrays.hashCode(name)" else "($name?.hashCode() ?: 0)"
                    )
                }
            }
            .addStatement("return result")

    open fun buildEquals(type: String, columns: List<ColumnDefinition>) =
        FunSpec.builder("equals")
            .addModifiers(KModifier.OVERRIDE)
            .returns(Boolean::class)
            .addParameter(
                ParameterSpec.builder(name = "other", type = Any::class.asClassName().copy(nullable = true)).build()
            )
            .beginControlFlow("return when {")
            .addStatement("this === other -> true")
            .addStatement("other == null -> false")
            .beginControlFlow("other is $type -> when {")
            .apply {
                columns.forEach {
                    val name = it.getPropertyName()
                    addStatement("$name != other.$name -> false")
                }
            }
            .addStatement("else -> true")
            .endControlFlow()
            .addStatement("else -> false")
            .endControlFlow()

    open fun buildFrom(interfaceType: ClassName) = FunSpec.builder("from").addParameter("from", interfaceType)

    open fun buildInto(interfaceType: ClassName): FunSpec.Builder {
        val typeVariableName = TypeVariableName.invoke("E", interfaceType)
        return FunSpec.builder("into")
            .addTypeVariable(typeVariableName)
            .addParameter("into", typeVariableName)
            .returns(typeVariableName)
    }

    override fun newJavaWriter(file: File): JavaWriter {
        return if (generateSpringAnnotations() && micronaut) {
            object : JavaWriter(file, generateFullyQualifiedTypes(), targetEncoding, generateJavadoc(), null) {
                override fun ref(clazzOrId: String?): String? {
                    if (clazzOrId == "org.springframework.beans.factory.annotation.Autowired") {
                        return super.ref("javax.inject.Inject")
                    } else if (clazzOrId == "org.springframework.stereotype.Repository") {
                        super.println("@%s", super.ref("javax.inject.Singleton"))
                        return super.ref("io.micronaut.context.annotation.Parallel")
                    }
                    return super.ref(clazzOrId)
                }
            }
        } else super.newJavaWriter(file)
    }

    open fun buildCopyFun(
        columns: List<ColumnDefinition>,
        pojoType: ClassName,
        interfaceType: ClassName?
    ): List<FunSpec.Builder> {
        return if (columns.size < 255) listOf(
            FunSpec.builder("copy")
                .addModifiers(KModifier.OPEN)
                .addParameters(columns.map {
                    val name = it.getPropertyName()
                    ParameterSpec.builder(
                        name = name,
                        type = getJavaType(it.getType(resolver(Mode.POJO))).toClassName()
                    )
                        .defaultValue("%L", "this.$name")
                        .build()
                })
                .returns(pojoType)
                .addStatement("return %L", columns.joinToString(
                    prefix = "$pojoType(",
                    postfix = ")",
                    separator = ", "
                ) { it.getPropertyName() })
        )
        else if (!generateImmutablePojos()) {
            columns.chunked(200).mapIndexed { index, columnChunk ->
                FunSpec.builder("copy${index + 1}")
                    .addModifiers(KModifier.OPEN)
                    .addParameters(columnChunk.map {
                        val name = it.getPropertyName()
                        ParameterSpec.builder(
                            name = name,
                            type = getJavaType(it.getType(resolver(Mode.POJO))).toClassName()
                        )
                            .defaultValue("%L", "this.$name")
                            .build()
                    })
                    .returns(pojoType)
                    .addStatement("val copy = $pojoType()")
                    .addStatement(
                        "%L",
                        columns.joinToString(separator = "\n") {
                            val name = it.getPropertyName()
                            "copy.$name = $name"
                        }
                    )
                    .addStatement("return copy")
            }
        } else {
            columns.chunked(200).mapIndexed { index, columnChunk ->
                FunSpec.builder("copy${index + 1}")
                    .addModifiers(KModifier.OPEN)
                    .addParameters(columnChunk.map {
                        val name = it.getPropertyName()
                        ParameterSpec.builder(
                            name = name,
                            type = getJavaType(it.getType(resolver(Mode.POJO))).toClassName()
                        )
                            .defaultValue("this.$name")
                            .build()
                    })
                    .returns(pojoType)
                    .addStatement(
                        "return $pojoType(%L)",
                        TypeSpec.anonymousClassBuilder()
                            .addProperties(columns.map { column ->
                                val name = column.getPropertyName()
                                PropertySpec.builder(
                                    name = name,
                                    type = getJavaType(column.getType(resolver(Mode.POJO))).toClassName()
                                )
                                    .addModifiers(KModifier.OVERRIDE)
                                    .mutable(false)
                                    .apply {
                                        if (columnChunk.contains(column)) initializer(name)
                                        else initializer("this@$pojoType.$name")
                                    }
                                    .build()
                            })
                            .addSuperinterface(interfaceType!!)
                            .build()
                    )
            }
        }
    }

    open fun buildDestructuring(columns: List<ColumnDefinition>): List<FunSpec.Builder> {
        return if (columns.size <= 5) {
            columns.mapIndexed { index, columnDefinition ->
                FunSpec.builder("component${index + 1}")
                    .addModifiers(KModifier.OPERATOR, KModifier.OPEN)
                    .returns(getJavaType(columnDefinition.getType(resolver(Mode.POJO))).toClassName())
                    .addStatement("return %L", columnDefinition.getPropertyName())
            }
        } else emptyList()
    }
}
