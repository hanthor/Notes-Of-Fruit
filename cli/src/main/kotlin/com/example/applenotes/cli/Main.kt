package com.example.applenotes.cli

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(NofCli.run(args.toList(), System.out, System.err))
}
