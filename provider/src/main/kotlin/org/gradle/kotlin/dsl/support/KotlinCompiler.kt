/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.kotlin.dsl.support

import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageUtil

import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinToJVMBytecodeCompiler.compileBunchOfSources
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoot

import org.jetbrains.kotlin.codegen.CompilationException

import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer.dispose
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer.newDisposable

import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.config.JVMConfigurationKeys.*

import org.jetbrains.kotlin.extensions.StorageComponentContainerContributor.Companion.registerExtension
import org.jetbrains.kotlin.name.NameUtils

import org.jetbrains.kotlin.samWithReceiver.CliSamWithReceiverComponentContributor

import org.jetbrains.kotlin.script.KotlinScriptDefinition

import org.jetbrains.kotlin.utils.PathUtil

import org.slf4j.Logger

import java.io.File


internal
fun compileKotlinScriptToDirectory(
    outputDirectory: File,
    scriptFile: File,
    scriptDef: KotlinScriptDefinition,
    classPath: List<File>,
    messageCollector: LoggingMessageCollector): String =

    withRootDisposable { rootDisposable ->

        withCompilationExceptionHandler(messageCollector) {

            val configuration = compilerConfigurationFor(messageCollector).apply {
                addKotlinSourceRoot(scriptFile.canonicalPath)
                put(RETAIN_OUTPUT_IN_MEMORY, false)
                put(OUTPUT_DIRECTORY, outputDirectory)
                setModuleName("buildscript")
                addScriptDefinition(scriptDef)
                classPath.forEach { addJvmClasspathRoot(it) }
            }
            val environment = kotlinCoreEnvironmentFor(configuration, rootDisposable).apply {
                HasImplicitReceiverCompilerPlugin.apply(project)
            }

            compileBunchOfSources(environment)
                || throw ScriptCompilationException(messageCollector.errors)

            NameUtils.getScriptNameForFile(scriptFile.name).asString()
        }
    }


private
object HasImplicitReceiverCompilerPlugin {

    fun apply(project: Project) {
        registerExtension(project, samWithReceiverComponentContributor)
    }

    val samWithReceiverComponentContributor =
        CliSamWithReceiverComponentContributor(
            listOf("org.gradle.api.HasImplicitReceiver"))
}


internal
fun compileToJar(
    outputJar: File,
    sourceFiles: Iterable<File>,
    logger: Logger,
    classPath: Iterable<File> = emptyList()): Boolean =

    compileTo(OUTPUT_JAR, outputJar, sourceFiles, logger, classPath)


internal
fun compileToDirectory(
    outputDirectory: File,
    sourceFiles: Iterable<File>,
    logger: Logger,
    classPath: Iterable<File> = emptyList()): Boolean =

    compileTo(OUTPUT_DIRECTORY, outputDirectory, sourceFiles, logger, classPath)


private
fun compileTo(
    outputConfigurationKey: CompilerConfigurationKey<File>,
    output: File,
    sourceFiles: Iterable<File>,
    logger: Logger,
    classPath: Iterable<File>): Boolean {

    withRootDisposable { disposable ->
        withMessageCollectorFor(logger) { messageCollector ->
            val configuration = compilerConfigurationFor(messageCollector).apply {
                addKotlinSourceRoots(sourceFiles.map { it.canonicalPath })
                put(outputConfigurationKey, output)
                setModuleName(output.nameWithoutExtension)
                classPath.forEach { addJvmClasspathRoot(it) }
                addJvmClasspathRoot(kotlinStdlibJar)
            }
            val environment = kotlinCoreEnvironmentFor(configuration, disposable)
            return compileBunchOfSources(environment)
        }
    }
}


private
val kotlinStdlibJar: File
    get() = PathUtil.getResourcePathForClass(Unit::class.java)


private inline
fun <T> withRootDisposable(action: (Disposable) -> T): T {
    val rootDisposable = newDisposable()
    try {
        return action(rootDisposable)
    } finally {
        dispose(rootDisposable)
    }
}


private inline
fun <T> withMessageCollectorFor(log: Logger, action: (MessageCollector) -> T): T {
    val messageCollector = messageCollectorFor(log)
    withCompilationExceptionHandler(messageCollector) {
        return action(messageCollector)
    }
}


private inline
fun <T> withCompilationExceptionHandler(messageCollector: MessageCollector, action: () -> T): T {
    try {
        return action()
    } catch (ex: CompilationException) {
        messageCollector.report(
            CompilerMessageSeverity.EXCEPTION,
            ex.localizedMessage,
            MessageUtil.psiElementToMessageLocation(ex.element))

        throw IllegalStateException("Internal compiler error: ${ex.localizedMessage}", ex)
    }
}


private
fun compilerConfigurationFor(messageCollector: MessageCollector): CompilerConfiguration =
    CompilerConfiguration().apply {
        put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, messageCollector)
    }


private
fun CompilerConfiguration.setModuleName(name: String) {
    put(CommonConfigurationKeys.MODULE_NAME, name)
}


private
fun CompilerConfiguration.addScriptDefinition(scriptDef: KotlinScriptDefinition) {
    add(JVMConfigurationKeys.SCRIPT_DEFINITIONS, scriptDef)
}


private
fun kotlinCoreEnvironmentFor(configuration: CompilerConfiguration, rootDisposable: Disposable) =
    KotlinCoreEnvironment.createForProduction(rootDisposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)


internal
fun messageCollectorFor(log: Logger, pathTranslation: (String) -> String = { it }): LoggingMessageCollector =
    LoggingMessageCollector(log, pathTranslation)


internal
data class ScriptCompilationError(val message: String, val location: CompilerMessageLocation?)


internal
data class ScriptCompilationException(val errors: List<ScriptCompilationError>) : RuntimeException() {
    override val message: String?
        get() {
            val errorPlural = if (errors.size > 1) "errors" else "error"
            val maxLineNumberLen = errors.mapNotNull { it.location?.line }.max().toString().length
            return (listOf("Script compilation $errorPlural:") + errors.map { error ->
                if (error.location != null) {
                    "Line ${error.location.line.toString().padStart(maxLineNumberLen, '0')}: ${error.location.lineContent}\n" +
                        "${" ".repeat(5 + maxLineNumberLen + 1 + error.location.column)}^- ${error.message}"
                } else {
                    error.message
                }
            }.map { it.prependIndent("  ") } + "${errors.size} $errorPlural").joinToString("\n\n")
        }

    val firstErrorLine
        get() = errors.firstOrNull { it.location != null }?.location!!.line
}


internal
class LoggingMessageCollector(
    private val log: Logger,
    private val pathTranslation: (String) -> String) : MessageCollector {

    val errors = arrayListOf<ScriptCompilationError>()

    override fun hasErrors() =
        synchronized(errors) {
            errors.isNotEmpty()
        }

    override fun clear() =
        synchronized(errors) {
            errors.clear()
        }

    override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageLocation?) {

        fun msg() =
            location?.run {
                path.let(pathTranslation).let { path ->
                    when {
                        line >= 0 && column >= 0 -> compilerMessageFor(path, line, column, message)
                        else -> "$path: $message"
                    }
                }
            } ?: message

        fun taggedMsg() =
            "${severity.presentableName[0]}: ${msg()}"

        when (severity) {
            CompilerMessageSeverity.ERROR, CompilerMessageSeverity.EXCEPTION -> {
                recordError(message, location)
                log.error { taggedMsg() }
            }
            in CompilerMessageSeverity.VERBOSE -> log.trace { msg() }
            CompilerMessageSeverity.STRONG_WARNING -> log.info { taggedMsg() }
            CompilerMessageSeverity.WARNING -> log.info { taggedMsg() }
            CompilerMessageSeverity.INFO -> log.info { msg() }
            else -> log.debug { taggedMsg() }
        }
    }

    /**
     * Safely record a script compilation error.
     * There'are no guarantee that `MessageCollector`s are not invoked concurrently.
     */
    private
    fun recordError(message: String, location: CompilerMessageLocation?) =
        synchronized(errors) {
            errors += ScriptCompilationError(message, location)
        }
}


internal
fun compilerMessageFor(path: String, line: Int, column: Int, message: String) =
    "$path:$line:$column: $message"
