package com.localfirst.assistant.tools.phoneuse

object PhoneUseToolNames {
    const val LIST_APPS = "list_apps"
    const val GET_APP_STATE = "get_app_state"
    const val CLICK = "click"
    const val SECONDARY_ACTION = "perform_secondary_action"
    const val SCROLL = "scroll"
    const val DRAG = "drag"
    const val TYPE_TEXT = "type_text"
    const val PRESS_KEY = "press_key"
    const val SET_VALUE = "set_value"
    /** Code mode, like Codex's computer-use REPL. */
    const val SCRIPT = "phone_script"

    /** Tools that change what is on screen. */
    val ACTIONS = setOf(CLICK, SECONDARY_ACTION, SCROLL, DRAG, TYPE_TEXT, PRESS_KEY, SET_VALUE, SCRIPT)
    /** Every phone-use tool that touches or reads an app's screen. */
    val SCREEN = ACTIONS + GET_APP_STATE
    val ALL = SCREEN + LIST_APPS
}
