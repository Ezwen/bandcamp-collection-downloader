package bandcampcollectiondownloader.util

import java.nio.file.Path

interface IO {

    fun createFile(path: Path)

    fun append(path: Path, content: String)

    fun createDirectories(path: Path)

    fun downloadFile(fileURL: String, saveDir: Path, optionalFileName: String = "", timeout: Int): Path

    fun delete(path: Path)

    fun unzip(zipPath: Path, outputFolderPath: Path)

    fun readLines(path: Path) : List<String>
}