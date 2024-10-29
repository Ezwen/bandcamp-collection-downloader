package bandcampcollectiodownloader.test

import bandcampcollectiondownloader.util.Logger
import bandcampcollectiondownloader.util.Util
import java.nio.file.Path
import java.nio.file.Paths

class UtilNoHomeDir(logger: Logger) : Util(logger) {

    override fun getUnixHomePath() : Path {
        return Paths.get("NOPE")
    }

}