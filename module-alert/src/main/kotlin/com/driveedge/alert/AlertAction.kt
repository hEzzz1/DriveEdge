package com.driveedge.alert

import com.driveedge.event.center.EdgeEvent

fun interface AlertAction {
  fun execute(
    event: EdgeEvent,
    message: String,
  ): Boolean
}

data class AlertActionSet(
  val sound: AlertAction = NO_OP_ACTION,
  val vibration: AlertAction = NO_OP_ACTION,
  val uiPrompt: AlertAction = NO_OP_ACTION,
) {
  fun actionOf(channel: AlertChannel): AlertAction =
    when (channel) {
      AlertChannel.SOUND -> sound
      AlertChannel.VIBRATION -> vibration
      AlertChannel.UI_PROMPT -> uiPrompt
    }

  companion object {
    private val NO_OP_ACTION = AlertAction { _, _ -> true }
  }
}
