package com.intuit.hooks.plugin.ksp

import arrow.core.sequenceValidated
import arrow.core.valueOr
import arrow.typeclasses.Semigroup
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import com.intuit.hooks.plugin.codegen.*
import com.intuit.hooks.plugin.codegen.generateClass
import com.intuit.hooks.plugin.codegen.generateImports
import com.intuit.hooks.plugin.codegen.generateProperty
import com.intuit.hooks.plugin.ksp.validation.validateProperty
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ksp.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

public class HooksProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        resolver.getNewFiles().forEach {
            it.accept(HooksVisitor(), Unit)
        }

        return emptyList()
    }

    private inner class HooksVisitor : KSVisitorVoid() {

        override fun visitFile(file: KSFile, data: Unit) {
            file.declarations.filter {
                it is KSClassDeclaration
            }.forEach {
                it.accept(this, Unit)
            }
        }

        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
            // TODO: This should really be restructured to follow KSP visitor pattern for members
            val superTypeNames = classDeclaration.superTypes
                .map(KSTypeReference::element)
                .filterIsInstance<KSClassifierReference>()
                .map(KSClassifierReference::referencedName)

            // TODO: Account for import aliases :P + how to avoid false positives? probably through resolve
            if (!superTypeNames.contains("Hooks") && !superTypeNames.contains("HooksDsl")) {
                classDeclaration.declarations.filter {
                    it is KSClassDeclaration && it.validate()
                }.forEach {
                    it.accept(this, Unit)
                }
            } else classDeclaration.findHooks().map { codeGen ->
                if (codeGen.firstOrNull() == null) return@map

                val packageName = classDeclaration.packageName
                val (classes, properties) = codeGen.map(::generateHookClass).unzip()
                val imports = createImportDirectives(classDeclaration, codeGen.toList())

                val visibility = classDeclaration.getVisibility().name.lowercase()
                val kind = classDeclaration.classKind.name.lowercase()
                val name =
                    "${classDeclaration.parentDeclaration?.simpleName?.asString() ?: ""}${classDeclaration.simpleName.asString()}Impl"
                val typeParameters = if (classDeclaration.typeParameters.isEmpty()) "" else "<${
                classDeclaration.typeParameters.joinToString(separator = ", ") { it.simpleName.asString() }
                }>"
                val fqName = classDeclaration.qualifiedName!!.asString()

                val resolvedPackageName = packageName.asString().takeIf(String::isNotEmpty)
                val newSource =
                    """|${resolvedPackageName?.let { "package $it" } ?: ""}
                       |
                       |$imports
                       |
                       |$visibility $kind ${name}Old$typeParameters : ${fqName}$typeParameters() {
                       |   ${properties.joinToString("\n", "\n", "\n") { it }}
                       |   ${classes.joinToString("\n", "\n", "\n") { it }} 
                       |}""".trimMargin()

                codeGenerator.createNewFile(
                    Dependencies(true, classDeclaration.containingFile!!),
                    packageName.asString(),
                    name + "Old",
                ).use {
                    newSource
                        .let(String::toByteArray)
                        .let(it::write)
                }

                /**
                 * Kotlin Poet Stuff
                 */
                val visibilityModifier = classDeclaration.getVisibility().toKModifier() ?: KModifier.PUBLIC
                val className = ClassName.bestGuess(name)

                val builder : (ClassName) -> TypeSpec.Builder = when(classDeclaration.classKind) {
                    ClassKind.INTERFACE -> TypeSpec.Companion::interfaceBuilder
                    ClassKind.CLASS -> TypeSpec.Companion::classBuilder
                    ClassKind.OBJECT -> TypeSpec.Companion::objectBuilder
                    else -> throw NotImplementedError("Hooks in constructs other than class, interface, and object aren't supported")
                }
                val (poetClasses, poetProperties) = codeGen.map(::generatePoetHookClass).unzip()

                val typeArguments = classDeclaration.typeParameters.map { it.toTypeVariableName() }
                val superclass = if(typeArguments.isNotEmpty()) classDeclaration.toClassName().parameterizedBy(typeArguments) else classDeclaration.toClassName()
                val hooksImplClass = builder(className)
                    .addModifiers(visibilityModifier)
                    .addTypeVariables(typeArguments)
                    .addProperties(poetProperties)
                    .addTypes(poetClasses)
                    .superclass(superclass)
                    .build()

                val file = FileSpec.builder(resolvedPackageName ?: "", name)
                    .addType(hooksImplClass)
                    .build()

                // TODO: somehow specify the original file as a dependency of this new file
                file.writeTo(codeGenerator, aggregating = false)

            }.valueOr { errors ->
                errors.forEach { logger.error(it.message, it.symbol) }
            }
        }
    }

    private fun KSClassDeclaration.findHooks() = getAllProperties()
        .filter {
            // Only process properties that are abstract b/c that's what we need for a concrete class
            it.modifiers.contains(Modifier.ABSTRACT)
        }
        .map(::validateProperty)
        .sequenceValidated(Semigroup.nonEmptyList())

    private fun generatePoetHookClass(hookInfo: HookInfo): Pair<TypeSpec, PropertySpec> {
        val classDefinition = hookInfo.generatePoetClass()
        val propertyDefinition = hookInfo.generatePoetProperty()

        return classDefinition to propertyDefinition
    }

    private fun generateHookClass(hookInfo: HookInfo): Pair<String, String> {
        val classDefinition = hookInfo.generateClass()
        val propertyDefinition = hookInfo.generateProperty()

        return classDefinition to propertyDefinition
    }

    private fun createImportDirectives(classDeclaration: KSClassDeclaration, hooks: List<HookInfo>): String {
        val existingImports = emptyList<String>() // TODO: Get imports -- this might be fixed with kotlin poet?
//        classDeclaration.containingFile?.
//        ?.removeHooksDslImport()
//        ?.map { it.text ?: "" }
//        ?: emptyList()

        val newImports = hooks.flatMap(HookInfo::generateImports)

        val hookStarImport = listOf("import com.intuit.hooks.*")

        return (hookStarImport + existingImports + newImports)
            .distinct()
            .joinToString("\n")
    }

    public class Provider : SymbolProcessorProvider {

        override fun create(environment: SymbolProcessorEnvironment): HooksProcessor = HooksProcessor(
            environment.codeGenerator,
            environment.logger,
        )
    }

    public class Exception(message: String, cause: Throwable? = null) : kotlin.Exception(message, cause)
}
