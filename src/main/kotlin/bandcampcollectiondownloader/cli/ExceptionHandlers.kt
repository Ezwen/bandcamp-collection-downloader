package bandcampcollectiondownloader.cli

import picocli.CommandLine
import picocli.CommandLine.ParameterException
import java.io.PrintWriter

object ExceptionHandlers {
    fun getExitCode(cmd: CommandLine, ex: Exception?): Int {
        val spec = cmd.commandSpec
        return if (cmd.exitCodeExceptionMapper != null) cmd.exitCodeExceptionMapper
            .getExitCode(ex) else spec.exitCodeOnInvalidInput()
    }

    fun printErrorMessage(cmd: CommandLine, ex: Exception) {
        cmd.err.println(cmd.colorScheme.errorText("ERROR: " + ex.message))
    }

    fun printStackTrace(cmd: CommandLine, ex: Exception?) {
        cmd.err.println(cmd.colorScheme.stackTraceText(ex))
    }

    val isDebug: Boolean
        get() = "DEBUG".equals(System.getProperty("picocli.trace"), ignoreCase = true)

    internal class ParameterExceptionHandler : CommandLine.IParameterExceptionHandler {
        override fun handleParseException(ex: ParameterException, args: Array<String?>?): Int {
            val cmd = ex.commandLine
            val err: PrintWriter = cmd.err

            // Print error
            if (isDebug) {
                printStackTrace(cmd, ex)
            } else {
                printErrorMessage(cmd, ex)
            }

            // Print suggestions (does not seem to do much)
            CommandLine.UnmatchedArgumentException.printSuggestions(ex, err)

            // Suggest to use --help
            err.printf("Use '--help' for more information.%n")
            return getExitCode(cmd, ex)
        }
    }

    internal class ExecutionExceptionHandler : CommandLine.IExecutionExceptionHandler {
        override fun handleExecutionException(
            ex: Exception,
            cmd: CommandLine,
            parseResult: CommandLine.ParseResult?
        ): Int {
            if (isDebug) {
                printStackTrace(cmd, ex)
            } else {
                printErrorMessage(cmd, ex)
            }
            return getExitCode(cmd, ex)
        }
    }
}