package dev.dking.googledrivedownloader

import dev.dking.googledrivedownloader.api.DriveClientConfig
import dev.dking.googledrivedownloader.api.FileField
import dev.dking.googledrivedownloader.api.impl.GoogleDriveClientImpl
import dev.dking.googledrivedownloader.api.impl.TokenManager
import kotlinx.coroutines.runBlocking
import java.nio.file.Paths

fun main(args: Array<String>): Unit =
  runBlocking {
//    val exitCode = dev.dking.googledrivedownloader.cli.CliApplication.main(args)
//    kotlin.system.exitProcess(exitCode)
    val driveClientConfig =
      DriveClientConfig(
        retryAttempts = 3,
        retryDelaySeconds = 5,
      )
    val tokenPath =
      Paths.get(
        System.getProperty("user.home"),
        ".google-drive-downloader",
        "tokens.json",
      )
    val driveClient =
      GoogleDriveClientImpl(
        config = driveClientConfig,
        clientId = "",
        clientSecret = "",
        tokenManager = TokenManager(tokenPath),
      )

    driveClient.authenticate(false)

    val fields =
      setOf(
        FileField.ID,
        FileField.NAME,
        FileField.MIME_TYPE,
        FileField.PARENTS,
        FileField.MD5_CHECKSUM,
        FileField.MODIFIED_TIME,
        FileField.SIZE,
        FileField.SHORTCUT_DETAILS,
      )
    val files = driveClient.listAllFiles(fields)
    println(files)

    runCatching {
      val filesList = files.getOrThrow()
      for (file in filesList) {
        println(file.id)
        println(file.name)
      }
    }
  }
