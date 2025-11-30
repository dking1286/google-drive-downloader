package dev.dking.googledrivedownloader

import kotlinx.coroutines.runBlocking

fun main(args: Array<String>): Unit =
  runBlocking {
    val exitCode = dev.dking.googledrivedownloader.cli.CliApplication.main(args)
    kotlin.system.exitProcess(exitCode)
  }
