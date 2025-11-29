package dev.dking.googledrivedownloader

import dev.dking.googledrivedownloader.api.DriveClientConfig
import dev.dking.googledrivedownloader.api.FileField
import dev.dking.googledrivedownloader.api.impl.DriveServiceFactory
import dev.dking.googledrivedownloader.api.impl.GoogleDriveClientImpl
import dev.dking.googledrivedownloader.api.impl.TokenManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import java.nio.file.Files
import java.nio.file.Paths

@Serializable
data class Config(
  val clientId: String,
  val clientSecret: String,
)

@OptIn(ExperimentalSerializationApi::class)
fun main(args: Array<String>): Unit =
  runBlocking {
//    val exitCode = dev.dking.googledrivedownloader.cli.CliApplication.main(args)
//    kotlin.system.exitProcess(exitCode)

    val tokenPath =
      Paths.get(
        System.getProperty("user.home"),
        ".google-drive-downloader",
        "tokens.json",
      )
    val tokenManager = TokenManager(tokenPath)

    val configPath =
      Paths.get(
        System.getProperty("user.home"),
        ".google-drive-downloader",
        "config.json",
      )
    val json =
      Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        namingStrategy = JsonNamingStrategy.SnakeCase
      }
    val configText = Files.readString(configPath)
    val config = json.decodeFromString<Config>(configText)
    val serviceFactory =
      DriveServiceFactory(
        clientId = config.clientId,
        clientSecret = config.clientSecret,
      )

    val driveClientConfig =
      DriveClientConfig(
        retryAttempts = 3,
        retryDelaySeconds = 5,
      )

    val baseDirectory =
      Paths.get(
        System.getProperty("user.home"),
        ".google-drive-downloader",
        "downloads",
      )
    Files.createDirectories(baseDirectory)

    val driveClient =
      GoogleDriveClientImpl(
        config = driveClientConfig,
        serviceFactory = serviceFactory,
        tokenManager = tokenManager,
        baseDirectory = baseDirectory,
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
