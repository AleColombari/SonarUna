package com.example.sonaruna.guia

import java.io.File

/** Reads the repository's data/ files, the same ones the app packages as assets and the web version serves. */
object TestData {
    private val dataDir: File by lazy {
        generateSequence(File(System.getProperty("user.dir")!!).absoluteFile) { it.parentFile }
            .map { File(it, "data") }
            .first { File(it, "unaerp.json").isFile }
    }

    fun read(name: String): String = File(dataDir, name).readText()

    /** A file from the repository root, such as casos/conversas.json. */
    fun repoFile(path: String): String = File(dataDir.parentFile, path).readText()

    val map: FloorMap by lazy { FloorMap.parse(read("unaerp.json")) }
    val gps: GpsPoints by lazy { GpsPoints.parse(read("unaerp-pontos.geojson"), map) }

    /** Instructions from a place, leaving it the way the person would (its exit heading). */
    fun route(from: String, to: String): List<String> =
        map.buildInstructions(map.exitHeading(from), map.planRoute(from, to)!!).map { it.texto }
}
