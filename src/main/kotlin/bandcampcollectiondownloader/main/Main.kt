package bandcampcollectiondownloader.main

import bandcampcollectiondownloader.cli.Command
import bandcampcollectiondownloader.cli.ExceptionHandlers
import bandcampcollectiondownloader.core.Constants
import picocli.CommandLine
import kotlin.system.exitProcess


fun main(args: Array<String>) {
    System.setProperty("picocli.usage.width", Constants.LINESIZE.toString())
    val commandLine = CommandLine(Command())
    commandLine.executionExceptionHandler = ExceptionHandlers.ExecutionExceptionHandler()
    commandLine.parameterExceptionHandler = ExceptionHandlers.ParameterExceptionHandler()
    val exitCode: Int = commandLine.execute(*args)
    exitProcess(exitCode)
}

