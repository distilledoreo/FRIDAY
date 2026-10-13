package com.localfirst.assistant.tools

object CodeToolNames {
    const val START = "start_code_task"
    const val GET = "get_code_task"
    const val LIST = "list_code_projects"
    const val FOLLOW_UP = "send_code_task_message"
    const val STOP = "stop_code_task"
    /** Tools whose successful result is a coding task shown as a card that opens Code. */
    val CARDS = setOf(START, GET, FOLLOW_UP, STOP)
}
