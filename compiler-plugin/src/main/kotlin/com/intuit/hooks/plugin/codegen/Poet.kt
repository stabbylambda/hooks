package com.intuit.hooks.plugin.codegen

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toTypeName

internal val hookContext = ClassName.bestGuess("com.intuit.hooks.HookContext")
internal val experimentalCoroutinesAnnotation = ClassName.bestGuess("kotlinx.coroutines.ExperimentalCoroutinesApi")

internal fun HookInfo.createSuperClass(extraTypeName: TypeName? = null): ParameterizedTypeName {
    val lambdaParameter = lambdaTypeName.let { if (isAsync) it.copy(suspending = true) else it }
    val parameters : List<TypeName> = listOfNotNull(lambdaParameter, extraTypeName)

    // TODO: is there a way to avoid bestGuess here?
    return ClassName.bestGuess("com.intuit.hooks.${superType}")
        .parameterizedBy(parameters)
}

internal fun HookInfo.generatePoetClass(): TypeSpec {
    val callBuilder = FunSpec.builder("call")
        .addParameters(paramsWithTypesPoet)
        .apply {
            if(isAsync) {
                addModifiers(KModifier.SUSPEND)
            }
        }

    val (superclass, call) = when(hookType) {
        HookType.SyncHook -> {
            val superclass = createSuperClass()

            val call = callBuilder
                .returns(UNIT)
                .addStatement("return super.call { f, context -> f(context, ${paramsWithoutTypes}) }")

            Pair(superclass, call)
        }
        HookType.SyncBailHook -> {
            val superclass = createSuperClass(hookSignature.returnTypeTypePoet)

            val call = callBuilder
                .returns(hookSignature.nullableReturnTypeTypePoet)
                .addStatement("return super.call { f, context -> f(context, ${paramsWithoutTypes}) }")

            Pair(superclass, call)
        }
        HookType.SyncLoopHook, HookType.AsyncSeriesLoopHook -> {
            val superclass = createSuperClass(interceptParameterPoet)

            val call = callBuilder
                .returns(UNIT)
                .addCode("return super.call(invokeTap = %L, invokeInterceptor = %L)",
                    CodeBlock.of("{ f, context -> f(context, ${paramsWithoutTypes}) }"),
                    CodeBlock.of("{ f, context -> f(context, ${paramsWithoutTypes}) }")
                )

           Pair(superclass, call)
        }
        HookType.SyncWaterfallHook, HookType.AsyncSeriesWaterfallHook -> {
            val superclass = createSuperClass(hookSignature.parameters.first().type.toTypeName())

            val accumulatorName = params.first().withoutType
            val call = callBuilder
                .returns(hookSignature.returnTypePoet)
                .addCode("return super.call(%N, invokeTap = %L, invokeInterceptor = %L)",
                    accumulatorName,
                    CodeBlock.of("{ f, %N, context -> f(context, ${paramsWithoutTypes}) }", accumulatorName),
                    CodeBlock.of("{ f, context -> f(context, ${paramsWithoutTypes}) }")
                )

           Pair(superclass, call)
        }
        HookType.AsyncSeriesHook -> {
            val superclass = createSuperClass()

            val call = callBuilder
                .returns(UNIT)
                .addStatement("return super.call { f, context -> f(context, ${paramsWithoutTypes}) }")

           Pair(superclass, call)
        }
        HookType.AsyncParallelHook -> {
            val superclass = createSuperClass()

            val call = callBuilder
                .returns(UNIT)
                .addStatement("return super.call { f, context -> f(context, ${paramsWithoutTypes}) }")

           Pair(superclass, call)
        }
        HookType.AsyncParallelBailHook -> {
            val superclass = createSuperClass(hookSignature.returnTypeTypePoet)

            // force the concurrency parameter to be first
            callBuilder.parameters.add(0, ParameterSpec("concurrency", INT))

            val call = callBuilder
                .returns(hookSignature.nullableReturnTypeTypePoet)
                .addStatement("return super.call(concurrency) { f, context -> f(context, ${paramsWithoutTypes}) }")

           Pair(superclass, call)
        }
        HookType.AsyncSeriesBailHook -> {
            val superclass = createSuperClass(hookSignature.returnTypeTypePoet)

            val call = callBuilder
                .returns(hookSignature.nullableReturnTypeTypePoet)
                .addStatement("return super.call { f, context -> f(context, ${paramsWithoutTypes}) }")

           Pair(superclass, call)
        }
    }

    return TypeSpec.classBuilder(className)
        .addModifiers(propertyVisibility, KModifier.INNER)
        .addFunctions(tapMethodsPoet)
        .apply {
            if (hookType == HookType.AsyncParallelBailHook) {
                addAnnotation(experimentalCoroutinesAnnotation)
            }
        }
        .superclass(superclass)
        .addFunction(call.build())
        .build()
}

private val HookInfo.interceptParameterPoet get() = LambdaTypeName.get(
    parameters = listOf(ParameterSpec.unnamed(hookContext)) + paramsWithTypesPoet,
    returnType = UNIT
).let { if(isAsync) it.copy(suspending = true) else it }

private val HookInfo.lambdaTypeName get() = LambdaTypeName.get(
    null,
    listOf(
        ParameterSpec.unnamed(hookContext)
    ) + paramsWithTypesPoet,
    hookSignature.returnTypePoet,
)

private val HookInfo.tapMethodsPoet : List<FunSpec>
    get() {
        val returnType = STRING.copy(nullable = true)
        val nameParameter = ParameterSpec.builder("name", STRING).build()
        val idParameter = ParameterSpec.builder("id", STRING).build()
        val functionParameter = ParameterSpec.builder("f", hookSignature.hookFunctionSignatureType.toTypeName()).build()

        val tap = FunSpec.builder("tap")
            .returns(returnType)
            .addParameter(nameParameter)
            .addParameter(functionParameter)
            .addStatement("return tap(name, generateRandomId(), f)")
            .build()

        val tapWithId = FunSpec.builder("tap")
            .returns(returnType)
            .addParameter(nameParameter)
            .addParameter(idParameter)
            .addParameter(functionParameter)
            .addStatement("return super.tap(name, id) { _: HookContext, $paramsWithTypes -> f($paramsWithoutTypes)}")
            .build()

        return listOf(tap, tapWithId)
    }

internal val HookInfo.paramsWithTypesPoet get() = params.map {
    ParameterSpec.builder(it.withoutType, it.parameter.type.toTypeName()).build()
}

internal fun HookInfo.generatePoetProperty(): PropertySpec {
    val b = PropertySpec.builder(property.name, ClassName.bestGuess(className))
        .initializer("${className}()")
            // TODO: the visibility here might not be correct
        .addModifiers(KModifier.OVERRIDE, propertyVisibility)

    if(hookType == HookType.AsyncParallelBailHook) {
        b.addAnnotation(experimentalCoroutinesAnnotation)
    }

    return b.build()
}