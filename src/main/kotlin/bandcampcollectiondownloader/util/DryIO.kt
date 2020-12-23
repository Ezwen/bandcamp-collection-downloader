package bandcampcollectiondownloader.util

import bandcampcollectiondownloader.core.BandCampDownloaderError
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

class DryIO : IO {

    private val createdFiles: MutableList<Path> = ArrayList()

    private fun exists(path: Path): Boolean {
        return Files.exists(path) || !createdFiles.none { p -> p == path }
    }

    private fun existsOrError(path: Path) {
        if (!exists(path)) throw BandCampDownloaderError(
            "File cannot exist at this point: " + path.toAbsolutePath().normalize().toString()
        )
    }

    private fun log(message: String) {
        Util.log("""[dry run] Would $message""")
    }

    override fun createFile(path: Path) {
        log("""create file ${path.toAbsolutePath().normalize()}""")
        createdFiles.add(path)
    }

    override fun append(path: Path, content: String) {
        log("""append "${content.trim()}" to file ${path.toAbsolutePath().normalize()}""")
        existsOrError(path)
    }

    override fun createDirectories(path: Path) {
        log("""create directories for path ${path.toAbsolutePath().normalize()}""")
        createdFiles.add(path)
    }

    override fun downloadFile(fileURL: String, saveDir: Path, optionalFileName: String, timeout: Int): Path {
        log("""download file from $fileURL to folder $saveDir""")
        existsOrError(saveDir)
        val fakeFile = saveDir.resolve("""unknownDownloadedFileName${Random().nextInt()}""")
        createdFiles.add(fakeFile)
        return fakeFile
    }

    override fun delete(path: Path) {
        log("""would delete ${path.toAbsolutePath().normalize()}""")
        existsOrError(path)
        createdFiles.removeIf { p ->
            p.toAbsolutePath().normalize().toString().equals(path.toAbsolutePath().normalize())
        }
    }

    override fun unzip(zipPath: Path, outputFolderPath: Path) {
        log(
            """would unzip file ${zipPath.toAbsolutePath().normalize()} into folder ${
                outputFolderPath.toAbsolutePath().normalize()
            }"""
        )
        existsOrError(zipPath)
        existsOrError(outputFolderPath)
    }

    override fun readLines(path: Path): List<String> {
        existsOrError(path)
        return emptyList()
    }
}